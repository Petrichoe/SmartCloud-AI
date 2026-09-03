# AIGC：qwen3.8-flash 调用返回 `url error`

## 记录信息

- 记录日期：2026-09-02
- 服务：`tj-aigc`（`aigc-service`）
- 依赖：Spring AI Alibaba `1.0.0-M6.1`
- 配置中心：Nacos
- 模型：DashScope `qwen3.8-flash`

## 现象

调用聊天接口 `/chat` 时，AIGC 服务返回以下错误：

```text
400 - {"code":"InvalidParameter","message":"url error, please check url！"}
```

错误会在不同会话、不同问题上重复出现，连普通文本 `你好` 也会失败。应用日志中还会出现：

```text
No acceptable representation
```

此前还出现过一次独立的额度错误：

```text
403 - {"code":"AllocationQuota.FreeTierOnly","message":"Free quota exhausted..."}
```

## 实际调用链

当前主聊天接口的调用路径是：

```text
POST /chat
  → AgentServiceImpl.chat()
  → RouteAgent.process()
  → AbstractAgent 中的 dashScopeChatClient
  → DashScope Chat API
```

`RouteAgent` 首先使用 `.call()` 判断用户意图，之后才可能进入推荐、咨询或购买智能体。当前这条调用链使用的是 DashScope ChatClient，不是 OpenAI ChatClient，也不是向量模型。

相关代码：

- `tj-aigc/src/main/java/com/tianji/aigc/service/impl/AgentServiceImpl.java`
- `tj-aigc/src/main/java/com/tianji/aigc/agent/AbstractAgent.java`
- `tj-aigc/src/main/java/com/tianji/aigc/controller/ChatController.java`

## 根因

Nacos 中配置了：

```yaml
spring:
  ai:
    dashscope:
      chat:
        enabled: true
        options:
          model: qwen3.8-flash
```

但没有配置：

```yaml
multi-model: true
```

`qwen3.8-flash` 是多模态模型。当前项目使用的 Spring AI Alibaba `1.0.0-M6.1` 中，`DashScopeChatOptions.multiModel` 默认值为 `false`。因此请求默认走纯文本接口：

```text
/api/v1/services/aigc/text-generation/generation
```

多模态模式打开后才会走：

```text
/api/v1/services/aigc/multimodal-generation/generation
```

模型类型和接口模式不匹配时，DashScope 返回 `url error`。这里的 `url error` 不是指用户问题中一定包含了 URL，也不是 Elasticsearch 地址或 OpenAI `base-url` 导致的。普通文本 `你好` 也失败，且路由智能体没有传入图片、视频等媒体内容，进一步排除了用户输入 URL 和工具媒体参数的可能性。

阿里云官方错误说明也把“模型与 API 接口不匹配”列为该错误的原因，并要求多模态模型使用多模态调用：

- [DashScope 错误码说明](https://help.aliyun.com/zh/model-studio/error-code)
- [qwen3.8-flash 模型说明](https://help.aliyun.com/zh/model-studio/qwen3-8-flash)

## 修复方法

### 方案一：继续使用 qwen3.8-flash

在 Nacos 的 `aigc-service.yaml` 中，在 `model` 同级增加 `multi-model: true`：

```yaml
spring:
  ai:
    dashscope:
      chat:
        enabled: true
        options:
          model: qwen3.8-flash
          multi-model: true
```

注意：YAML 配置项是 `multi-model`，不是 Java 方法名 `withMultiModel`。

修改后确认配置已经被 `aigc-service` 重新加载；如果 ChatModel Bean 没有动态刷新，需要重新启动 `aigc-service`。重启前应确认没有正在进行的用户请求。

### 方案二：使用纯文本模型

如果当前业务只需要文字对话，可以改成纯文本模型，例如：

```yaml
spring:
  ai:
    dashscope:
      chat:
        options:
          model: qwen-plus
          multi-model: false
```

这只是选择了与文本接口匹配的模型，并不代表系统只能使用 `qwen-plus`。

## 额度错误的单独处理

`AllocationQuota.FreeTierOnly` 与 `url error` 是两个不同问题。

当 DashScope 账户的免费额度耗尽，且控制台开启了“仅使用免费额度”模式时，会返回 403。此时需要在 DashScope 控制台关闭仅免费额度模式或为账户补充付费额度。切换模型不能保证绕过账户额度限制。

参考：[阿里云模型用量统计与免费额度说明](https://help.aliyun.com/zh/model-studio/model-usage-statistics)

## `No acceptable representation` 的含义

`/chat` 使用 SSE 返回：

```java
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
```

当上游 DashScope 已经抛出异常后，统一异常处理器又无法按照 `text/event-stream` 协商出可返回的错误格式，于是出现 `No acceptable representation`。它是上游调用失败后的二次报错，不是本问题的首要根因。

排障时应优先看最早出现的 DashScope 400/403 错误；模型调用恢复后，这条二次异常通常会消失。如果仍需优化错误体验，应单独为 SSE 接口设计流式错误事件或合适的异常响应。

## 验证清单

1. 在 Nacos 中确认 `spring.ai.dashscope.chat.options.model` 与 `multi-model` 同时生效。
2. 发送普通问题 `你好`，确认不再出现 `url error`。
3. 检查日志：
   - 没有 `InvalidParameter: url error`，说明接口模式已匹配。
   - 如果出现 `AllocationQuota.FreeTierOnly`，单独处理 DashScope 额度。
4. 测试课程推荐或课程咨询，确认向量检索和聊天生成均正常。
5. 检查向量配置维度保持一致：`text-embedding-v3` 输出维度与 Elasticsearch 向量库配置均为 `1024`。

## 容易误判的配置

- `spring.ai.openai.base-url`：当前主 `/chat` 路径不使用它；OpenAI ChatClient 主要由 `/chat/text` 使用。
- `spring.elasticsearch.uris`：这是向量库地址，不是 DashScope Chat API 地址。
- `text-embedding-v3`：负责文本向量化和相似度检索，不负责生成聊天回答，也不是本次 `url error` 的直接原因。

## 安全注意事项

排障日志和文档中不得记录真实 API Key、密码、Token 或私钥。如果密钥曾经被粘贴到聊天、日志或提交记录中，应立即撤销并重新生成；文档只保留变量名或 `<REDACTED>` 占位符。
