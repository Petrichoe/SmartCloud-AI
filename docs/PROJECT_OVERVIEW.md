# 天机学堂（TJXT）项目概览

> 本文档用于记录项目的技术栈、模块架构、服务协作方式和 AIGC 服务设计。具体实现以当前磁盘代码为准；开发代理的强制约束请以根目录 `AGENTS.md` 为准。

## 项目概述

天机学堂（TJXT）是一个基于 Spring Cloud 的微服务架构在线教育平台，使用 Java 17 和 Spring Boot 3.3.5 开发。

## 技术栈

- **核心框架**：Spring Boot 3.3.5、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.3.2
- **数据库**：MySQL 8.0.23、MyBatis Plus 3.5.9、MongoDB（AIGC 对话存储）
- **缓存**：Redisson 3.13.6
- **搜索**：Elasticsearch 7.12.1（含向量数据库功能）
- **消息队列**：Spring AMQP（RabbitMQ）
- **任务调度**：XXL-Job 2.3.1
- **文档**：Knife4j 4.5.0（OpenAPI 3）
- **工具库**：Hutool 5.8.36
- **AI 框架**：Spring AI BOM、Spring AI Alibaba 1.0.0-M6.1、阿里云通义千问 DashScope SDK 2.19.0、OpenAI API
- **云服务**：阿里云 OSS、支付宝 SDK、腾讯云 SDK

## 项目架构

### 模块结构

项目采用多模块 Maven 结构，分为以下主要模块。

#### 基础模块

- **tj-common**：公共模块，包含：
  - `annotations`：自定义注解
  - `autoconfigure`：自动配置类
  - `constants`：常量定义
  - `domain`：通用领域对象（如分页、响应包装类）
  - `enums`：枚举类
  - `exceptions`：异常处理
  - `filters`：过滤器
  - `utils`：工具类
  - `validate`：校验相关

- **tj-api**：API 接口模块，包含：
  - `client`：各服务的 Feign 客户端（跨服务调用）
  - `dto`：数据传输对象
  - `constants`：API 常量
  - `cache`：缓存相关
  - 使用 Spring Cloud OpenFeign 实现服务间调用，集成 Sentinel 做熔断降级

- **tj-gateway**：网关服务（端口 10010）
  - 使用 Spring Cloud Gateway
  - 路由配置：各服务通过路径前缀区分（`/us/**`、`/cs/**`、`/ls/**` 等）
  - 集成全局 CORS 配置
  - 使用 `StripPrefix=1` 过滤器去除路径前缀

#### 认证授权模块（tj-auth）

多子模块结构：

- **tj-auth-common**：认证公共组件
- **tj-auth-service**：认证服务（独立部署）
- **tj-auth-resource-sdk**：资源服务器 SDK（被其他服务依赖）
- **tj-auth-gateway-sdk**：网关认证 SDK（被网关依赖）

#### 业务服务模块

每个服务都是独立的微服务，通过网关路由访问：

- **tj-user**（us）：用户服务
- **tj-course**（cs）：课程服务（端口 8086）
- **tj-learning**（ls）：学习服务
- **tj-exam**（es）：考试服务
- **tj-trade**（ts）：交易服务
- **tj-pay**（ps）：支付服务
- **tj-promotion**（prs）：营销服务
- **tj-search**（ss）：搜索服务
- **tj-media**（ms）：媒体服务
- **tj-data**（ds）：数据服务
- **tj-remark**（rs）：评论服务
- **tj-message**（sms）：消息服务
- **tj-aigc**（ags）：AIGC 服务（端口 8094）

部分服务采用 DDD 分层结构（如 `tj-message`、`tj-pay`）：

- `*-domain`：领域层（实体、值对象）
- `*-api`：API 接口定义
- `*-service`：服务实现层

### 服务间通信

1. **同步调用**：使用 Feign Client（定义在 `tj-api` 模块）
   - 例如：`UserClient`、`CourseClient`、`TradeClient` 等
   - Feign 客户端按业务域组织在 `tj-api/client` 目录下

2. **异步消息**：使用 RabbitMQ（Spring AMQP）

3. **网关路由**：所有外部请求通过 `tj-gateway` 统一入口

### 配置管理

- 每个服务有多环境配置：
  - `application.yml`：主配置
  - `application-local.yml`：本地开发
  - `application-dev.yml`：开发环境
  - `application-test.yml`：测试环境
- 使用 Spring Cloud Bootstrap 加载配置
- 通过 `spring.profiles.active` 切换环境

### 数据库设计

- 每个服务有独立数据库（通过 `tj.jdbc.database` 配置）
- 例如：course-service 使用 `tj_course` 数据库
- 使用 MyBatis Plus 作为 ORM 框架

## 开发参考

### 服务端口分配

- `tj-gateway`：10010
- `tj-course`：8086
- `tj-aigc`：8094
- 其他服务端口参考各自的 `application.yml`

### API 文档

- 使用 Knife4j（Swagger 3）
- 配置在各服务的 `application.yml` 中：

  ```yaml
  tj:
    swagger:
      enable: true
      package-path: com.tianji.xxx.controller
      title: 服务标题
  ```

- 访问地址：`http://localhost:{port}/doc.html`

### 认证与鉴权

- 使用自定义认证框架（`tj-auth`）
- 通过 SDK 方式集成：
  - 网关集成 `tj-auth-gateway-sdk`
  - 资源服务集成 `tj-auth-resource-sdk`
- 配置项：`tj.auth.resource.enable`

### 代码分层

典型 Controller 层路径：

```text
src/main/java/com/tianji/{service}/controller
```

### 常见依赖

- 所有服务都依赖 `tj-common` 获取公共工具
- 需要跨服务调用时依赖 `tj-api` 中的 Feign Client
- Lombok 用于减少样板代码
- 使用 Jakarta EE 9+ 规范（`jakarta.servlet`）

## AIGC 服务

### 服务概览

`tj-aigc` 是天机学堂的 AI 生成式内容服务，提供智能对话、课程推荐、语音合成等 AI 能力。

### 多智能体架构

采用路由智能体架构，根据用户意图动态分发到不同专业智能体：

- **RouteAgent（路由智能体）**：负责分析用户意图，将请求路由到合适的专业智能体
- **RecommendAgent（课程推荐智能体）**：基于用户需求和 Elasticsearch 向量检索推荐课程
- **ConsultAgent（课程咨询智能体）**：解答课程相关问题
- **BuyAgent（课程购买智能体）**：处理下单购买流程，集成交易服务
- **KnowledgeAgent（知识讲解智能体）**：提供知识点讲解和答疑

智能体类型定义：`com.tianji.aigc.enums.AgentTypeEnum`

### 混合存储架构

采用 Redis + MongoDB 混合存储方案优化对话管理：

- **Redis 热数据存储**：
  - 存储最近 20 轮对话作为 AI 上下文
  - 提供毫秒级响应速度
  - 防止 Token 超限（Context Window Exceeded）
  - 自动过期时间：3 天

- **MongoDB 冷数据存储**：
  - 持久化全量历史对话记录
  - 支持前端查询完整聊天历史
  - 集合：`chat_message`
  - 索引字段：`sessionId`、`createTime`

核心实现：

- `HybridChatMemory`：混合存储实现（实现 Spring AI 的 `ChatMemory` 接口）
- `RedisChatMemory`：Redis 存储实现
- 数据模型：`ChatMessagePO`、`ChatSession`

### 向量检索

集成 Elasticsearch 作为向量数据库：

- 用于课程内容的语义检索
- 支持课程推荐智能体的相似度匹配
- 依赖：`spring-ai-elasticsearch-store-spring-boot-starter`
- Elasticsearch 版本：8.15.5

### AI 模型集成

支持多种 AI 模型接入：

- **阿里云通义千问**：
  - Spring AI Alibaba Starter 1.0.0-M6.1
  - DashScope SDK 2.19.0
  - 配置类：`DashScopeProperties`

- **OpenAI API**：
  - Spring AI OpenAI Starter
  - 支持 ChatGPT 模型和 TTS（Text-to-Speech）
  - 实现类：`OpenAIAudioServiceImpl`

配置类：`SpringAIConfig`、`AIProperties`

### 流式响应

- 支持 SSE 流式输出，实现打字机效果
- 接口：`POST /chat`（produces = `text/event-stream`）
- 返回类型：`Flux<ChatEventVO>`（响应式编程）
- 事件类型：`ChatEventTypeEnum`（消息内容、工具调用、结束标记等）

### 文字转语音

- 提供流式 TTS 接口
- 接口：`POST /audio/tts-stream`（produces = `audio/mp3`）
- 返回类型：`ResponseBodyEmitter`
- 实现：基于 OpenAI TTS API

### 系统提示词管理

系统提示词（System Prompt）通过 Nacos 配置中心动态管理：

```yaml
tj:
  ai:
    prompt:
      system:
        route-agent:
          data-id: route-agent-system-message.txt
        recommend-agent:
          data-id: recommend-agent-system-message.txt
        buy-agent:
          data-id: buy-agent-system-message.txt
        consult-agent:
          data-id: consult-agent-system-message.txt
        knowledge-agent:
          data-id: knowledge-agent-system-message.txt
```

配置类：`SystemPromptConfig`

### 工具调用

智能体可调用外部工具增强能力：

- **CourseTools**：课程查询工具（集成 Elasticsearch 向量检索）
- **OrderTools**：下单工具（集成 TradeClient）
- 工具结果存储：`ToolResultHolder`

### 主要接口

- **对话接口**：`POST /chat` - SSE 流式对话
- **停止对话**：`POST /chat/stop` - 中断当前会话
- **文本对话**：`POST /chat/text` - 非流式对话
- **会话管理**：`GET /sessions` - 查询会话列表
- **会话详情**：`GET /sessions/{id}` - 查询历史消息
- **语音合成**：`POST /audio/tts-stream` - 文字转语音流式输出
- **向量化**：嵌入相关接口（`EmbeddingController`）

### 数据库

- **MySQL 数据库**：`tj_aigc`
  - 表：`chat_session`（会话元数据）
  - Mapper：`ChatSessionMapper`

- **MongoDB 集合**：`chat_message`
  - 文档结构：`sessionId`、`type`、`content`、`createTime`

### 关键配置

```yaml
server:
  port: 8094

spring:
  application:
    name: aigc-service

tj:
  jdbc:
    database: tj_aigc
  ai:
    user-id: 9999  # 默认用户 ID
  auth:
    resource:
      enable: true
```

### 开发注意事项

1. MongoDB 写入建议使用异步方式，避免阻塞流式响应。
2. Redis 中的对话数量限制为 20 条，防止超出模型 Token 限制。
3. MongoDB 的 `sessionId` 和 `createTime` 字段必须建立索引。
4. 系统提示词存储在 Nacos，修改后需重启服务生效。
5. 该服务允许循环引用（`allow-circular-references: true`）。
6. 大量使用 Reactor（Flux/Mono），需熟悉响应式编程模型。

## 相关文档

- 模块文档索引：[`docs/README.md`](README.md)
- AIGC 对话存储改造：`tj-aigc/src/main/resources/doc/README_CHAT_REFACTOR.md`
- 优惠券异步领券：`tj-promotion/src/main/resources/doc/COUPON_ASYNC_RECEIVE.md`
- 优惠券 Lua 优化：`tj-promotion/src/main/resources/doc/COUPON_LUA_OPTIMIZATION.md`
- 兑换码安全重构：`tj-promotion/src/main/resources/doc/exchange-code-security-refactoring.md`
- VMware 本地操作手册：[`docs/VMWARE_LOCAL_ACCESS.md`](VMWARE_LOCAL_ACCESS.md)
