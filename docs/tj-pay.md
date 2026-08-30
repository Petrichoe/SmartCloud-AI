# tj-pay

> 支付中心：统一封装微信/支付宝等第三方支付渠道，管理支付单与退款单，处理异步回调，支付结果经 MQ 通知交易服务。

## 1. 模块概述

- **模块职责**：支付单申请（生成支付二维码/预支付单）、支付状态查询、第三方支付回调验签与落库、退款单管理、渠道配置管理；支付成功后发 MQ（`pay.success`）驱动订单状态更新。
- **子模块（DDD 风格）**：
  | 子模块 | 职责 |
  |---|---|
  | `tj-pay-api` | **对外 SDK**：`PayClient` 接口 + DTO（PayApplyDTO/PayResultDTO/RefundApplyDTO…）+ 常量（PayChannel/PayType），供 tj-trade 以 SDK 直连（本地 Jar/Feign 复用，以实现为准） |
  | `tj-pay-domain` | 领域层：PayOrder、RefundOrder、PayChannel PO |
  | `tj-pay-service` | 服务实现：controller/service/third 渠道适配/tasks 定时对账 |
- **服务名/端口**：`pay-service`，端口 **8087**。
- **网关路由前缀**：`/ps/**`（回调接口通常走独立域名/内网，见 notify 配置）。
- **数据库**：MySQL `tj_pay`（表：pay_order、refund_order、pay_channel）。

## 2. 模块结构

```
com.tianji.pay
├── controller      # PayOrderController / PayChannelController / RefundOrderController / NotifyController
├── service(+impl)  # IPayOrderService、IRefundOrderService、IPayChannelService、INotifyService
├── domain.po       # PayOrder、RefundOrder、PayChannel
├── third           # 渠道适配层：IPayService（渠道接口）
│   ├── ali         # AliPayService + AliPayConfiguration/AliPayProperties（支付宝当面付 precreate 扫码）
│   ├── wx          # 微信（以实际代码为准）
│   └── model       # PrepayResponse、PayStatusResponse、PayStatus、RefundResponse
├── tasks           # PayOrderCheckTask、RefundOrderCheckTask（XXL-Job 对账）
├── constants       # NotifyStatus 等
└── CommonPayProperties（notifyHost 等）
```

## 3. 对外接口

### PayOrderController（`/pay-orders`）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/pay-orders` | **发起支付**：入参 PayApplyDTO（bizOrderNo、payChannel、payType、amount…），创建支付单并调渠道下单，返回支付二维码/链接 |
| GET | `/pay-orders/{bizOrderId}/status` | 查询支付结果（PayResultDTO） |

### NotifyController（`notify`）——第三方异步回调（网关放行，公网可达）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/notify/{ALI_CHANNEL_CODE}` | 支付宝支付结果回调（验签→幂等→更新支付单→发 MQ） |
| POST | `/notify/{WX_CHANNEL_CODE}` | 微信支付回调 |
| POST | `/notify/refund/{WX_CHANNEL_CODE}` | 微信退款回调 |

### 其他

- `PayChannelController`：支付渠道配置 CRUD（启停、配置参数）。
- `RefundOrderController`：退款单管理（申请退款、查询退款结果）。

## 4. 核心业务逻辑

### 4.1 支付主流程

1. tj-trade 经 SDK `PayClient.applyPayOrder` → 本服务创建 `PayOrder`（状态待支付，含 bizOrderNo 与 payOrderNo 映射）。
2. `third.AliPayService.createPrepayOrder`：支付宝**当面付 precreate** 生成二维码；回调地址 `notifyHost + /notify/aliPayChannelCode`（`CommonPayProperties`）。
3. 用户扫码支付 → 第三方回调 `NotifyController` → `NotifyServiceImpl` 验签、按通知号幂等（`NotifyStatus`）→ 更新 PayOrder 为已支付 → **发 MQ** `pay.topic` + `pay.success`（消息体 PayResultDTO）。
4. tj-trade 的 `PayMessageHandler` 消费 `pay.success` 更新订单并发 `order.pay` → tj-learning 入课表。
5. 兜底：`PayOrderCheckTask`（XXL-Job `payOrderCheckHandler`）定时查询第三方对账，补发状态；退款同理（`RefundOrderCheckTask`）。

### 4.2 退款

- `RefundOrderServiceImpl`：创建退款单 → `IPayService.refundOrder` 调渠道退款 → 渠道回调/定时任务查询退款状态 → 发 `refund.status.change`。

### 4.3 SDK（tj-pay-api）

- `PayClient` 接口：`applyPayOrder`、`queryPayResult`、`listAllPayChannels`、退款相关。
- tj-trade 依赖该 SDK 调用支付能力（不经 tj-api 的 Feign 列表）。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `PayOrder` | `payOrderNo`、`bizOrderNo`（业务订单号）、`payChannel`（aliPayChannelCode/wxPayChannelCode…）、`payType`、`amount`、`status` |
| `RefundOrder` | `payOrderNo`、`refundOrderNo`、`refundAmount`、`status` |
| `PayChannel` | 渠道编码、名称、启停状态、配置 |

## 6. 配置说明

- 端口 8087；`tj.jdbc.database: tj_pay`。
- `tj.pay.*`：`CommonPayProperties`（notifyHost 回调域名等）；支付宝/微信密钥经 `AliPayProperties` 等（Nacos）。
- Knife4j 文档开启（`tj.swagger.enable: true`）。
- XXL-Job：`payOrderCheckHandler`、退款对账任务。

## 7. 依赖关系

- 依赖：tj-common、RabbitMQ（发布 `pay.success`、`refund.status.change`）、支付宝 SDK（alipay-easysdk）、微信支付 SDK、XXL-Job。
- 被依赖：tj-trade（PayClient SDK 直连）；MQ 消费方为 tj-trade 的 PayMessageHandler。

## 8. 注意事项

1. **回调接口必须公网可达且不走网关鉴权**（第三方无法带 JWT），`notifyHost` 配置与内网穿透/生产域名要保持一致；本工作区 `PayOrderController`、`PayServiceImpl`（trade 侧）有未提交修改，以磁盘为准。
2. 回调处理必须幂等（同一通知可能重复推送），幂等依据见 `NotifyServiceImpl`/`NotifyStatus`。
3. 金额单位在渠道间不同（支付宝字符串元 vs 内部分），统一经 `transferStringAmount2Int`/`transferAmount2String` 转换，新增渠道时沿用。
4. 支付对账任务需在 XXL-Job 平台注册，否则回调丢失时无兜底。
