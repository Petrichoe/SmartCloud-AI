

-----

# AIGC 模块对话存储改造技术方案

## 1\. 背景与现状

当前 `tj-aigc` 模块使用 Redis (`RedisChatMemory`) 作为唯一的对话上下文存储介质。随着业务发展，该方案暴露了以下问题：

* **存储成本高**：Redis 内存资源昂贵，不适合存储海量历史对话数据。
* **持久化能力弱**：Redis 主要用于缓存，存在数据丢失风险，且难以进行长期的数据分析和回溯。
* **上下文限制**：为了防止 Token 超限，Redis 中的数据必须定期截断，导致用户无法在前端查看完整的历史聊天记录。

## 2\. 改造目标

采用 **Redis + MongoDB 混合存储架构**，实现“热数据快读、冷数据久存”的目标：

1.  **短期记忆 (Hot Memory)**：利用 Redis 存储最近 N 轮对话，确保 AI 接口响应速度和 Token 效率。
2.  **长期归档 (Cold Storage)**：利用 MongoDB 异步持久化所有对话记录，支持全量历史查询和数据分析。
3.  **成本优化**：大幅降低 Redis 内存占用，利用 MongoDB 低成本存储海量文本。

## 3\. 总体架构设计

### 3.1 核心组件变化

| 组件 | 改造前 (Current) | 改造后 (Target) | 职责 |
| :--- | :--- | :--- | :--- |
| **ChatMemory** | `RedisChatMemory` | **`HybridChatMemory`** | 统一管理对话数据的读写分发 |
| **热存储** | Redis (全量) | Redis (最近 20 条) | 提供 AI 上下文 (Context) |
| **冷存储** | 无 | **MongoDB** | 对话持久化、前端历史记录查询 |

### 3.2 数据流向图

```mermaid
graph TD
    User[用户] -->|1. 发送消息| Controller
    Controller -->|2. 调用| AI_Service
    AI_Service -->|3. 获取上下文| ChatClient
    ChatClient -->|4. 读取(Get)| HybridChatMemory
    HybridChatMemory -->|5. 仅读取热数据| Redis[(Redis - Hot)]
    
    AI_Service -->|6. 生成回复| LLM[大模型]
    
    ChatClient -->|7. 保存(Add)| HybridChatMemory
    HybridChatMemory -->|8. 写入热数据(Trim & TTL)| Redis
    HybridChatMemory -->|9. 异步写入冷数据| MongoDB[(MongoDB - Cold)]
    
    User -->|10. 查看历史记录| HistoryController
    HistoryController -->|11. 分页查询| MongoDB
```

## 4\. 详细设计与实现

### 4.1 引入依赖

在 `tj-aigc/pom.xml` 中引入 MongoDB 支持：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

### 4.2 数据模型定义

在 MongoDB 中创建 `chat_message` 集合，用于持久化存储。

```java
// com.tianji.aigc.domain.po.ChatMessagePO.java
@Data
@Builder
@Document(collection = "chat_message")
public class ChatMessagePO {
    @Id
    private String id;

    @Indexed // 建立索引加速 sessionId 查询
    private String sessionId; 

    private String type;    // "USER" 或 "ASSISTANT"
    private String content; // 对话内容
    
    @Indexed
    private LocalDateTime createTime; // 消息时间
}
```

### 4.3 混合存储实现 (HybridChatMemory)

新建 `HybridChatMemory` 类，实现 Spring AI 的 `ChatMemory` 接口。

**核心逻辑：**

* **add()**: 双写。写入 MongoDB 做归档；写入 Redis 并执行 `trim` 操作（仅保留最近 20 条），同时设置过期时间（如 3 天）。
* **get()**: 只读 Redis。确保传给大模型的 Prompt 上下文既快又不会超 Token。
* **clear()**: 仅清理 Redis。保留 MongoDB 数据以供历史查阅。

<!-- end list -->

```java
@Slf4j
@RequiredArgsConstructor
public class HybridChatMemory implements ChatMemory {

    private final StringRedisTemplate redisTemplate;
    private final MongoTemplate mongoTemplate;
    private static final String PREFIX = "CHAT:";
    private static final int MAX_CONTEXT_SIZE = 20; // 限制上下文保留条数

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 1. 异步/同步写入 MongoDB (全量保存)
        saveToMongo(conversationId, messages);

        // 2. 写入 Redis (上下文缓存)
        String key = PREFIX + conversationId;
        var listOps = redisTemplate.boundListOps(key);
        messages.forEach(m -> listOps.rightPush(MessageUtil.toJson(m)));
        
        // 3. 维护 Redis 容量 (防 Token 爆炸)
        if (listOps.size() > MAX_CONTEXT_SIZE) {
            listOps.trim(-MAX_CONTEXT_SIZE, -1);
        }
        redisTemplate.expire(key, 3, TimeUnit.DAYS);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        // 仅从 Redis 读取热数据给 AI
        String key = PREFIX + conversationId;
        // ... redis get logic
        return messages; 
    }
    
    // ... clear implementation
}
```

### 4.4 配置切换

修改 `SpringAIConfig.java`，将默认的 `InMemory` 或 `RedisChatMemory` 替换为自定义的 `HybridChatMemory`。

```java
@Bean
public ChatMemory chatMemory(StringRedisTemplate redisTemplate, MongoTemplate mongoTemplate) {
    return new HybridChatMemory(redisTemplate, mongoTemplate);
}
```

### 4.5 历史记录查询改造

修改 `ChatSessionServiceImpl` 中的 `getSessionDetailById` 方法，不再调用 `chatMemory.get()`，而是直接查询 MongoDB。

```java
@Override
public List<MessageVO> getSessionDetailById(String sessionId) {
    String conversationId = ChatService.getConversationId(sessionId);
    
    // 改为查 MongoDB，支持查询全量历史，不再受 Redis 截断影响
    Query query = Query.query(Criteria.where("sessionId").is(conversationId))
                       .with(Sort.by(Sort.Order.asc("createTime")));
    
    List<ChatMessagePO> list = mongoTemplate.find(query, ChatMessagePO.class);
    
    return list.stream().map(this::convertToVO).toList();
}
```

## 5\. 方案收益评估

1.  **性能 (Performance)**:
    * AI 对话接口延迟不受历史记录长度影响，维持在毫秒级（Redis 读取耗时）。
2.  **稳定性 (Stability)**:
    * 有效防止因历史记录过长导致的 `ContextWindowExceededException`（Token 超限错误）。
    * Redis 内存占用预计下降 **90%** 以上（仅存几十条 vs 存几千条）。
3.  **功能性 (Functionality)**:
    * 前端支持查看“即便是很久以前”的完整对话记录。
    * 为后续开发“会话分析”、“用户画像提取”等功能提供了数据基础。

## 6\. 注意事项

* **数据迁移**：上线前需评估是否需要将现有的 Redis 存量数据迁移至 MongoDB。如果历史数据不重要，可选择冷启动。
* **异步处理**：建议在 `HybridChatMemory.add` 中使用 `@Async` 或线程池执行 MongoDB 的写入操作，避免 I/O 阻塞影响流式输出的响应速度。
* **索引优化**：务必为 MongoDB 的 `sessionId` 字段创建索引。