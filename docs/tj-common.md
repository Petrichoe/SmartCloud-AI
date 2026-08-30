# tj-common

> 全项目公共基础库：所有业务服务依赖它获得工具类、统一响应/异常体系、自动配置（MQ、MyBatis Plus、Redisson、Swagger、XXL-Job、MVC）。

## 1. 模块概述

- **模块职责**：提供跨服务复用的公共能力——工具类、统一返回体与异常、自动装配的基础设施配置（RabbitMQ 消息工具、MyBatis Plus 字段填充、分布式锁注解、接口文档、XXL-Job 客户端等）。
- **服务形态**：公共依赖库（无 `spring.application.name`，不独立部署、无端口）。
- **数据库/存储**：无。
- **装配方式**：通过 `src/main/resources/META-INF/spring.factories` 注册自动配置类，业务服务引入依赖即自动生效。

## 2. 模块结构

`com.tianji.common` 下主要包：

| 包 | 职责 |
|---|---|
| `annotations` | `@NoWrapper`：标记 Controller 方法跳过统一响应包装 |
| `autoconfigure.mq` | MQ 自动配置与消息发送工具 |
| `autoconfigure.mvc` | MVC 自动配置：JSON（Jackson 长整型转字符串等）、统一响应包装、参数校验 |
| `autoconfigure.mybatis` | MyBatis Plus 配置：分页插件、字段自动填充 |
| `autoconfigure.redisson` | Redisson 配置 + `@Lock` 分布式锁注解与切面 |
| `autoconfigure.swagger` | Knife4j/OpenAPI 文档配置 |
| `autoconfigure.xxljob` | XXL-Job 执行器配置 |
| `constants` | `Constant`、`MqConstants`、`RegexConstants`、`ErrorInfo` |
| `domain` | `R<T>` 统一响应、`PageDTO`/`PageQuery` 分页对象、`LoginUserDTO` 等 |
| `enums` | `BaseEnum`、`CommonStatus`、`UserType` |
| `exceptions` | 业务异常体系 |
| `filters` | `RequestIdFilter`：为请求生成链路 ID |
| `utils` | 30+ 工具类 |
| `validate` | `@EnumValid`、`@ParamChecker` 注解与校验器 |

### spring.factories 注册的自动配置

```properties
MqConfig, JsonConfig, MvcConfig, ParamCheckerConfig,
MybatisConfig, RedissonConfig, Knife4jConfiguration, XxlJobConfig
```

## 3. 对外暴露的关键组件

### 3.1 统一响应与异常

- **`R<T>`**（`com.tianji.common.domain.R`）：统一响应体，`code/data/msg`；`WrapperResponseBodyAdvice` 自动把 Controller 返回值包装为 `R`，`@NoWrapper` 可跳过。
- **异常体系**（`com.tianji.common.exceptions`），由 `CommonExceptionAdvice` 统一拦截转换为 `R`：
  - `BadRequestException`（400）、`UnauthorizedException`（401）、`ForbiddenException`（403）
  - `CommonException`（通用业务异常，可指定 code）、`BizIllegalException`（业务非法）
  - `DbException`（数据库异常）、`RequestTimeoutException`（超时）

### 3.2 MQ 消息工具（`autoconfigure.mq`）

- **`RabbitMqHelper`**：核心发送工具，方法：
  - `send(exchange, routingKey, t)`：同步发送
  - `sendDelayMessage(exchange, routingKey, t, delay)`：发送 TTL+死信 延迟消息（配合 `DelayedMessageProcessor`）
  - `sendAsyn(...)`：异步发送（带/不带重试时间）
- **`BasicIdMessageProcessor`**：为消息设置全局唯一 `Message.id`（用于消费端幂等）。
- **`MqConfig`**：当 `spring.rabbitmq.listener.type=simple` 时生效；注册 `RabbitMqHelper`、消息转换器（JSON）、重试/幂等相关 Bean。
- **`MqConstants`**：全项目交换机与 routing key 常量（各业务模块共用）：
  - 交换机：`course.topic`、`order.topic`、`learning.topic`、`sms.direct`、`error.topic`、`pay.topic`、`trade.delay.topic`、`like.record.topic`、`promotion.topic`
  - 课程类 key：`course.new/up/down/expire/delete`
  - 交易类 key：`order.pay`、`order.refund`、`pay.success`、`refund.status.change`、`delay.order.query`（订单支付超时查询延迟消息）
  - 学习类 key：`sign.in`、`section.learned`、`write.note`、`note.gathered`
  - 点赞类 key：`QA.times.changed`、`NOTE.times.changed`（模板 `{}.times.changed`）
  - 营销类 key：`coupon.receive`（异步领券）
  - 错误兜底：`error.{}.queue`、`error.#`
  - 短信：`sms.message`

### 3.3 MyBatis Plus（`autoconfigure.mybatis`）

- **`MybatisConfig`**：注册分页插件等。
- **`BaseMetaObjectHandler` / `MyBatisAutoFillInterceptor`**：插入/更新时自动填充公共字段（`createTime`、`updateTime` 等），业务表 DO 无需手动赋值。

### 3.4 分布式锁（`autoconfigure.redisson`）

- **`@Lock`** 注解 + **`LockAspect`** 切面：方法级分布式锁，支持 SpEL 表达式 key（配合 `SPELUtils`）。
- **`LockType`**：可重入锁/公平锁等类型。
- **`LockStrategy`**：获取锁失败策略：
  - `SKIP_FAST`：快速跳过
  - `FAIL_FAST`：快速抛异常
  - `SKIP_AFTER_RETRY_TIMEOUT`：重试超时后跳过
  - `FAIL_AFTER_RETRY_TIMEOUT`（默认）：重试超时后抛异常

### 3.5 其他自动配置

- **`JsonConfig`**：Jackson 全局配置（长整型序列化为字符串防前端精度丢失、日期时间格式等）。
- **`MvcConfig`** / **`WrapperResponseMessageConverter`**：统一响应包装与 Feign 场景下的消息转换。
- **`ParamCheckerConfig`** + **`CheckerAspect`** + `validate` 包：`@ParamChecker` 参数校验切面。
- **`Knife4jConfiguration`**：`tj.swagger.enable=true` 时启用 OpenAPI 文档；`tj.swagger.package-path` 等由 `SwaggerConfigProperties` 承载。
- **`XxlJobConfig`** / **`XxlJobProperties`**：XXL-Job 执行器注册。
- **`RequestIdFilter`** / **`RequestIdUtil`**：请求链路 ID 生成与传递。

### 3.6 常用工具类（`utils`）

| 工具类 | 用途 |
|---|---|
| `UserContext` | 基于 ThreadLocal 的当前登录用户存取（`setUser/getUser/removeUser`），由认证 SDK 的拦截器写入 |
| `BeanUtils` | 对象拷贝（cp / cpIgnoreNull 等） |
| `CollUtils` | 集合操作（按字段转 Map、分组、拼接等） |
| `DateUtils` | `LocalDateTime` 便捷计算 |
| `JsonUtils` | 基于 Jackson 的 JSON 互转 |
| `StringUtils` / `NumberUtils` / `ArrayUtils` / `ByteUtils` | 基础类型工具 |
| `RandomUtils` | 随机数/随机字符串（验证码等） |
| `WebUtils` / `RequestUtils` / `HttpUtils` | 请求响应读写、HTTP 调用 |
| `SignUtils` / `EncryptUtils 相关` | 签名、摘要 |
| `TreeDataUtils` | 平铺列表 ↔ 树结构转换（分类树等） |
| `SPELUtils` | 解析 SpEL（配合 `@Lock` 等注解取参数值） |
| `SqlWrapperUtils` | MyBatis Plus 条件构造辅助 |
| `EnumUtils` / `BooleanUtils` / `ObjectUtils` / `Convert` | 类型转换 |
| `QrCodeUtils` | 二维码生成（支付二维码等） |
| `AssertUtils` / `ViolationUtils` | 断言、校验违规处理 |
| `CookieBuilder` / `IoUtils` / `ReflectUtils` / `AspectUtils` / `MarkedRunnable` / `TokenContext` / `TjTemporalConverter` / `SwaggerUtils` | 各场景辅助 |

## 4. 核心实现要点

- **延迟消息**：`RabbitMqHelper.sendDelayMessage` 采用「TTL 队列 + 死信交换机」方案，`DelayedMessageProcessor` 负责声明延迟队列并绑定死信路由；典型使用方是 tj-trade 的订单支付超时查询（`delay.order.query`）。
- **消息幂等**：发送侧统一用 `BasicIdMessageProcessor` 写入消息 ID，消费侧可据此做幂等判断。
- **自动填充**：所有继承 Model/BaseDO 的实体在 insert/update 时自动填 `createTime`/`updateTime`，业务代码不要重复赋值。
- **响应包装对 Feign 的影响**：内部 Feign 调用解析的是被包装后的 `R` 结构，`tj-api` 的解码配置依赖该包装格式，修改 `WrapperResponseBodyAdvice`/`R` 结构需全链路回归。

## 5. 数据模型

无数据库。通用对象：`R`、`PageDTO<T>`、`PageQuery`（分页查询基类，`pageNo/pageSize/sortBy`）、`BaseDTO`、`IdNameDTO`、`LoginUserDTO`。

## 6. 配置说明

各自动配置均有条件开关，业务服务在 `application.yml` 中按需启用：

| 配置项 | 作用 |
|---|---|
| `tj.swagger.enable` | 启用 Knife4j 文档（还有 `package-path`、`title` 等） |
| `spring.rabbitmq.listener.type=simple` | 启用 MQ 自动配置与 RabbitMqHelper |
| XXL-Job 相关 | `XxlJobProperties` 绑定的执行器配置 |
| Redisson | 连接配置由各服务 yml 提供，`RedissonConfig` 自动创建客户端 |

（完整元数据见 `src/main/resources/META-INF/spring-configuration-metadata.json`。）

## 7. 依赖关系

- **被依赖**：所有业务服务（tj-user/course/learning/…/tj-aigc）与 tj-api 都依赖 tj-common。
- **外部依赖**：spring-boot-starter-web/amqp/data-redis、mybatis-plus、redisson、knife4j、xxl-job-core、hutool 等（见该模块 `pom.xml`）。

## 8. 注意事项

1. 修改 `spring.factories` 或自动配置类会影响**全部服务**，需全局回归。
2. `UserContext` 基于 ThreadLocal，异步线程/线程池中需手动传递用户上下文，否则取不到用户。
3. `@Lock` 默认策略是获取锁失败抛异常，若希望静默跳过需显式指定 `lockStrategy`。
4. 统一响应包装依赖 `MvcConfig`，若某接口不需要包装必须加 `@NoWrapper`，否则前端/Feign 解析结构不一致。
