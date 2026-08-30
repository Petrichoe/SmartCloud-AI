# tj-aigc

> AI 智能对话服务：多智能体（Route → Recommend/Consult/Buy/Knowledge）课程顾问，SSE 流式输出，Redis+MongoDB 混合对话记忆，ES 向量课程检索，TTS 语音合成；同时为 tj-learning 提供 AI 自动回复能力。

## 1. 模块概述

- **模块职责**：智能对话（意图路由 + 专业智能体）、会话管理、向量嵌入与语义检索、文字转语音；对外提供 SSE 流式接口，对内提供 AigcClient 供学习模块调用。
- **服务名/端口**：`aigc-service`，端口 **8094**。
- **网关路由前缀**：`/ais/**`。
- **存储**：
  - MySQL `tj_aigc`（`chat_session` 会话元数据）
  - **MongoDB**（`chat_message` 全量历史）
  - **Redis**（最近对话上下文，key 前缀 `CHAT:`，TTL 3 天）
  - **Elasticsearch**（课程向量库，spring-ai-elasticsearch-store）
- **AI 平台**：阿里云通义千问（Spring AI Alibaba + DashScope SDK）、OpenAI 兼容接口（TTS）。
- **外部依赖**：Feign（TradeClient、CourseClient、UserClient）、Nacos（系统提示词）。

## 2. 模块结构

```
com.tianji.aigc
├── controller   # ChatController（SSE 对话）、ChatSessionController（会话）、AudioController（TTS）、EmbeddingController（向量）
├── agent        # Agent 接口 + AbstractAgent + RouteAgent / RecommendAgent / ConsultAgent / BuyAgent / KnowledgeAgent
├── memory       # HybridChatMemory（Redis 热 + Mongo 冷）、RedisChatMemory、RedisMessage、MyAssistantMessage、MessageUtil
├── tools        # CourseTools（查课）、OrderTools（下单）+ result: CourseInfo、PrePlaceOrder
├── service.impl # AgentServiceImpl（★当前生效的多智能体实现）、ChatServiceImpl（单智能体旧版，@Service 已注释）、
│                #   ChatSessionServiceImpl、OpenAIAudioServiceImpl
├── advisor      # RecordOptimizationAdvisor（请求记录/优化切面）
├── config       # SpringAIConfig、AIProperties、DashScopeProperties、SystemPromptConfig、SessionProperties、AsyncConfig、ToolResultHolder
├── domain       # po: ChatSession、ChatMessagePO；vo: ChatEventVO、SessionVO、MessageVO…；enums: AgentTypeEnum、ChatEventTypeEnum、MessageTypeEnum
├── mapper       # ChatSessionMapper
└── org.springframework.ai.autoconfigure.chat.client  # MyChatClientAutoConfiguration（自定义 ChatClient 装配）
```

## 3. 对外接口

### ChatController（`/chat`）

| 方法 | 路径 | 用途 | 备注 |
|---|---|---|---|
| POST | `/chat` | **流式对话** | `produces=text/event-stream`，返回 `Flux<ChatEventVO>` |
| POST | `/chat/stop` | 停止当前会话生成 | |
| POST | `/chat/text` | 非流式对话（一次性返回文本） | |
| GET | `/chat/templates` | 会话引导模板（首屏推荐问题等） | |

### ChatSessionController（`/session`）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/session` | 创建会话（返回标题/示例） |
| GET | `/session/hot` | 热门会话/推荐 |
| GET | `/session/{sessionId}` | 会话详情 |
| GET | `/session/history` | 历史会话列表（分页，MongoDB 全量消息） |

### AudioController（`/audio`）

- `POST /audio/tts-stream`（produces=`audio/mp3`）：流式 TTS，返回 `ResponseBodyEmitter`，基于 OpenAI TTS API（`OpenAIAudioServiceImpl`）。

### EmbeddingController（`/embedding`）

- `POST /embedding`：向量化写入；`GET /embedding`、`GET /embedding/search`、`/search/all`：ES 向量相似检索。

## 4. 核心业务逻辑

### 4.1 多智能体路由（`AgentServiceImpl` ★当前生效实现）

1. 请求进入 `chat(question, sessionId)`，先交给 **RouteAgent**：LLM 按意图输出目标智能体名。
2. `AgentTypeEnum.agentNameOf(result)` 解析为 `ROUTE/RECOMMEND/CONSULT/BUY/KNOWLEDGE`：
   | 智能体 | 职责 | 依赖 |
   |---|---|---|
   | RouteAgent | 意图识别/路由 | 仅提示词 |
   | RecommendAgent | 课程推荐 | CourseTools（ES 向量检索） |
   | ConsultAgent | 课程咨询答疑 | CourseTools |
   | BuyAgent | 引导下单 | OrderTools（TradeClient 预下单/下单） |
   | KnowledgeAgent | 知识讲解 | 仅提示词 |
3. `findAgentByType` 从 Spring 容器按 `getAgentType()` 匹配 Agent Bean（`Agent` 接口 + `AbstractAgent` 模板：持有 ChatClient、memory、提示词）。
4. 目标智能体 `process` 返回 `Flux<ChatEventVO>`（流式 token + 工具调用事件 + STOP_EVENT）。
5. `stop(sessionId)` 通过 RouteAgent 取消进行中的生成。
- 旧版单智能体 `ChatServiceImpl` 保留在代码中（`@Service` 注释），可作降级参考实现。

### 4.2 混合对话记忆（`HybridChatMemory implements ChatMemory`）★

- **写入**（`add`）：
  1. Redis List（`CHAT:{conversationId}`）追加消息并设置 **TTL 3 天**（热数据，供 LLM 上下文，防 Token 超限）。
  2. **异步**批量写 MongoDB `chat_message` 集合（冷数据永久归档，失败仅记日志不阻断流式响应）。
- **读取**（`get(conversationId, lastN)`）：从 Redis 取最近 N 条作为上下文。
- **清理**（`clear`）：只删 Redis，MongoDB 保留。
- 辅助类：`RedisMessage`（序列化结构）、`MyAssistantMessage`（携带工具调用等扩展字段）、`MessageUtil`（消息转换）。

### 4.3 工具调用（Function Calling）

- `CourseTools`：`@Tool` 注解方法（如 `queryCourseById`），底层 ES 向量检索课程；描述常量在 `Constant.Tools`。
- `OrderTools`：调 **TradeClient** 完成预下单/下单（返回 `PrePlaceOrder`）。
- `ToolResultHolder`：跨线程保存工具结果（响应式上下文传递）。

### 4.4 系统提示词（`SystemPromptConfig`）

各智能体提示词存 Nacos，`application.yml` 配置 data-id：

```yaml
tj:
  ai:
    prompt:
      system:
        route-agent:       # data-id: route-agent-system-message.txt
        recommend-agent:   # data-id: recommend-agent-system-message.txt
        buy-agent:         # data-id: buy-agent-system-message.txt
        consult-agent:     # data-id: consult-agent-system-message.txt
        knowledge-agent:   # data-id: knowledge-agent-system-message.txt
```

### 4.5 向量检索与 TTS

- 向量：`spring-ai-elasticsearch-store`（ES 8.x 客户端）存储课程嵌入，`EmbeddingController` 触发写入/检索；Recommend/Consult 借助 CourseTools 语义找课。
- TTS：`OpenAIAudioServiceImpl` 走 OpenAI 兼容 TTS 端点，`ResponseBodyEmitter` 边合成边推流（mp3）。
- `RecordOptimizationAdvisor`：对话记录优化（压缩/改写历史，降低 Token 消耗）。

## 5. 数据模型

| 存储 | 内容 |
|---|---|
| MySQL `chat_session`（ChatSession） | 会话元数据：sessionId、userId、标题、状态 |
| MongoDB `chat_message`（ChatMessagePO） | sessionId、type（user/assistant）、content、createTime；**sessionId + createTime 需建索引** |
| Redis `CHAT:{sessionId}`（List） | 最近对话（LLM 上下文），TTL 3 天 |
| ES 向量索引 | 课程内容嵌入（向量字段 + 元数据） |

## 6. 配置说明

- 端口 8094；`tj.jdbc.database: tj_aigc`；`tj.ai.user-id: 9999`（系统默认 AI 用户，tj_user 中需存在）。
- `AIProperties`/`DashScopeProperties`：模型名、API-KEY、TTS 端点等（Nacos）。
- `allow-circular-references: true`（与 course-service 循环依赖）。
- MongoDB 连接在 yml `spring.data.mongodb`；ES 向量仓库连接独立配置。
- `AsyncConfig`：异步线程池（Mongo 归档等）。

## 7. 依赖关系

- **Feign**：TradeClient（BuyAgent 下单）、CourseClient（课程信息）、UserClient。
- **被依赖**：tj-learning（AigcClient → 问答 AI 自动回复）。
- 外部：Redis、MongoDB、Elasticsearch(8.x)、DashScope/OpenAI、Nacos、RabbitMQ（可选通知）。

## 8. 注意事项

1. **上下文窗口保护**：Redis 只取最近 N 条进提示词；修改条数/前缀需同步 `RedisChatMemory`、`HybridChatMemory` 与前端会话展示。
2. MongoDB 写入为异步尽力而为，极端情况可能丟归档但不影响对话；查询历史走 `/session/history`。
3. 提示词在 Nacos 修改后**需重启**（`SystemPromptConfig` 启动时拉取）。
4. 流式接口使用 Reactor（Flux），注意不要在流管线中做阻塞调用；`stop` 依赖会话级取消标志。
5. `ChatService` 有两个实现，当前 `AgentServiceImpl` 生效（多智能体），`ChatServiceImpl` 已注释——排查行为时先确认注入的是哪个实现。
6. 本工作区 `ChatService`、`ChatSessionServiceImpl` 有未提交修改，以磁盘代码为准；详细设计见 `tj-aigc/src/main/resources/doc/README_CHAT_REFACTOR.md`。
