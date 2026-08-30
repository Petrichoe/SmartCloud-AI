# tj-message

> 消息中心：短信发送（阿里云/腾讯云/UC 多渠道）、短信模板与第三方平台参数管理、公告/通知任务与用户收件箱。DDD 多子模块结构。

## 1. 模块概述

- **模块职责**：
  - **短信**：统一短信发送入口（`POST /sms/message`），按 `SmsInfoDTO` 渲染模板并分发到第三方渠道（AliSmsHandler/TencentSmsHandler/UcSmsHandler）。
  - **模板/平台管理**：短信模板（MessageTemplate）、第三方短信平台配置（SmsThirdPlatform）的 CRUD。
  - **通知**：公告模板（NoticeTemplate）、通知任务（NoticeTask，定时/批量触达）、公共公告（PublicNotice）、用户收件箱（UserInbox）。
- **子模块（DDD 风格）**：
  | 子模块 | 职责 |
  |---|---|
  | `tj-message-api` | 对外接口定义与客户端：`MessageClient`、`AsyncSmsClient` + `MessageClientConfiguration` |
  | `tj-message-domain` | 领域模型（PO/DTO/枚举） |
  | `tj-message-service` | 服务实现：controller/service/thirdparty |
- **服务名/端口**：`message-service`，端口 **8085**。
- **网关路由前缀**：`/sms/**`。
- **数据库**：MySQL `tj_message`。
- **外部依赖**：阿里云短信、腾讯云短信、UC 短信平台 SDK；RabbitMQ（`sms.direct` 交换机，异步短信）。

## 2. 模块结构

```
com.tianji.message
├── api            # tj-message-api：MessageClient、AsyncSmsClient（Feign/异步短信入口）
├── controller     # SmsController、MessageTemplateController、SmsThirdPlatformController、
│                  #   NoticeTemplateController、NoticeTaskController、UserInboxController
├── service(+impl) # ISmsService、IMessageTemplateService、ISmsThirdPlatformService、
│                  #   INoticeTemplateService、INoticeTaskService、IPublicNoticeService、IUserInboxService
├── thirdparty     # ISmsHandler 接口 + ali/AliSmsHandler + tencent/TencentSmsHandler + uc/UcSmsHandler
├── domain         # dto: SmsInfoDTO、MessageTemplateDTO/FormDTO、NoticeTaskDTO…、enums: SmsTemplate
├── config         # MessageConfig、MessageProperties
└── constants      # MessageErrorInfo
```

## 3. 对外接口

| Controller | 路径 | 用途 |
|---|---|---|
| `SmsController` | `POST /sms/message` | **发送短信**（内部接口：验证码、通知类短信，入参 SmsInfoDTO） |
| `MessageTemplateController` | `/message-templates` | 短信模板 CRUD |
| `SmsThirdPlatformController` | `/sms-third-platforms` | 第三方短信平台配置 CRUD（密钥、签名、额度） |
| `NoticeTemplateController` | `/notice-templates` | 站内通知模板 CRUD |
| `NoticeTaskController` | `/notice-tasks` | 通知任务管理（创建/启停定时批量通知） |
| `UserInboxController` | `/user-inbox` | 用户收件箱（站内信查询/已读） |

## 4. 核心业务逻辑

### 4.1 短信发送链路

1. 业务方（如 tj-user 验证码）经 **MessageClient/AsyncSmsClient** 或 MQ（`sms.direct` + `sms.message`）发起发送请求（SmsInfoDTO：手机号、模板编码、参数）。
2. `SmsServiceImpl` 按 `SmsTemplate` 枚举/模板表渲染内容。
3. 选择渠道：`ISmsHandler` 多实现（Ali/Tencent/Uc），由平台配置（`SmsThirdPlatform` 表 + `tj.sms.ali.*` 配置）决定；`thirdparty.ali.AliSmsHandler` 为当前主用渠道（yml 中 `tj.sms.ali` 配置）。
4. 调第三方 SDK 发送，记录发送结果（失败进入错误队列 `error.topic` 兜底）。

### 4.2 通知/公告

- `NoticeTaskServiceImpl`：通知任务按模板+人群生成站内信，批量写 `UserInbox`；支持定时执行。
- `PublicNoticeServiceImpl`：全站公共公告管理与查询。

## 5. 数据模型

| 表 | 用途 |
|---|---|
| 短信模板表 | 模板编码、内容模板、渠道参数 |
| 第三方平台表 | 渠道类型、密钥、签名、启停 |
| 通知模板/任务表 | 通知模板内容；任务（模板、人群、执行时间、状态） |
| 用户收件箱表 | userId、标题、内容、是否已读 |

## 6. 配置说明

- 端口 8085；`tj.jdbc.database: tj_message`；`tj.sms.ali.*`：阿里云短信 AccessKey/签名/模板（Nacos）。
- `MessageProperties`/`MessageConfig`：消息中心通用配置。

## 7. 依赖关系

- 被依赖：tj-user（验证码短信）、tj-trade/tj-promotion 等业务通知，经 `MessageClient`（tj-message-api 提供，被引用方直接依赖）或 MQ `sms.message`。
- 外部中间件：阿里云/腾讯云短信 SDK、RabbitMQ、MySQL。

## 8. 注意事项

1. `POST /sms/message` 是内部接口（可触发任意短信），必须收敛调用方并保持鉴权，严禁网关放行。
2. 第三方短信按量计费，注意平台额度与限流；验证码类短信需配合 tj-user 的 Redis TTL 防刷。
3. DDD 子模块：新接口加在 `tj-message-api`（client + dto），实现放 `tj-message-service`，保持分层。
