# tj-trade

> 交易中心：购物车、下单（普通/免费课报名）、订单状态流转、支付发起与结果查询、退款申请；通过 MQ 与 tj-pay、tj-learning 协作完成「支付成功 → 入课表」链路。

## 1. 模块概述

- **模块职责**：购物车 CRUD；预下单确认页数据（课程信息+折扣）；创建订单并调用 tj-pay 发起支付；免费课直接报名；支付结果轮询查询与超时关单；退款申请与进度跟踪。
- **服务名/端口**：`trade-service`，端口 **8088**。
- **网关路由前缀**：`/ts/**`。
- **数据库**：MySQL `tj_trade`（表：cart、order、order_detail、refund_apply）。
- **外部依赖**：RabbitMQ（`pay.topic`、`order.topic`、`trade.delay.topic`）、Feign（CourseClient、PromotionClient、PayClient）。

> 说明：与早期文档描述不同，**tj-trade 当前是单模块工程**（非 domain/api/service 子模块结构）。

## 2. 模块结构

```
com.tianji.trade
├── controller   # CartController / OrderController / OrderDetailController / PayController / RefundApplyController
├── service(+impl) # IOrderService、ICartService、IOrderDetailService、IPayService、IRefundApplyService
├── domain.po    # Cart、Order、OrderDetail、RefundApply
├── handler      # PayMessageHandler（MQ 监听）
├── config / constants / mapper
```

## 3. 对外接口

### OrderController（`/orders`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/orders/page` | 我的订单分页 |
| GET | `/orders/{id}` | 订单详情 |
| GET | `/orders/{id}/status` | 查询下单结果/支付状态（下单后轮询） |
| GET | `/orders/prePlaceOrder` | **预下单**：确认页数据（课程快照 + 优惠券折扣，经 PromotionClient） |
| POST | `/orders/placeOrder` | **下单**：创建订单 + 明细，发起支付，返回订单与支付单信息 |
| POST | `/orders/freeCourse/{courseId}` | 免费课直接报名（0 元单，直接完成） |
| PUT | `/orders/{id}/cancel` | 取消订单 |
| DELETE | `/orders/{id}` | 删除订单 |

### CartController（`/carts`）

`POST /carts` 加入购物车、`GET /carts` 我的购物车、`DELETE /carts/{id}` 删除单项、`DELETE /carts` 清空。

### PayController（`pay`）

- 支付渠道列表、发起支付（`applyPayOrder` 返回支付二维码/跳转 url）、延迟查单入口（见 4.3）。

### OrderDetailController（`/order-details`）

- 内部查询接口（供 tj-api 的 TradeClient 使用）：`enrollNum`（课程报名人数）、`enrollCourse`（用户已购课程）、`course/{id}`、`purchaseInfo` 等。

### RefundApplyController（`/refund-apply`）

- 退款申请提交、进度查询（管理端审批流以代码为准）。

## 4. 核心业务逻辑

### 4.1 下单流程（`OrderServiceImpl.placeOrder`）

1. 校验课程上架状态（`CourseClient` 拉取课程信息）。
2. 组装优惠券（`PromotionClient` 计算折扣）。
3. 写 `order` + `order_detail`（课程快照价格）。
4. 清购物车中对应课程。
5. 返回订单信息，前端继续调 `PayController` 发起支付。
- 免费课 `enrolledFreeCourse`：直接生成 0 元完成订单并发 `order.pay`，不经过支付。

### 4.2 支付结果同步（`PayServiceImpl` + `PayMessageHandler`）★核心链路

- `applyPayOrder`：经 **PayClient**（tj-pay 提供的 SDK 直连）发起支付拿到支付 url，**随即发送 TTL 延迟消息** `trade.delay.topic` + `delay.order.query`。
- `queryPayResult`（消费延迟消息）：调 `payClient.queryPayResult` 查询支付状态：
  - 已支付 → 标记订单成功，发 `order.topic` + `order.pay`（tj-learning 入课表）。
  - 未支付 → **再次发送延迟消息循环查询**，直到支付或超时关单（`cancelOrder`）。
- `PayMessageHandler` 同时监听 `pay.topic`：
  - `pay.success`：支付服务主动回调，更新订单并触发 `order.pay`。
  - `refund.status.change`：退款状态变更，更新退款单。
  - `delay.order.query`：延迟查单入口。

### 4.3 退款（`RefundApplyServiceImpl`）

- 用户提交退款申请 → 审批通过后调 tj-pay 退款 → tj-pay 回发 `refund.status.change` → 更新退款单 → 审批通过时向 `order.topic` 发 `order.refund`（tj-learning 移除课表）。

### 4.4 购物车

- 基于 `cart` 表（数据库）存储，购物车容量上限在 `CartServiceImpl`/常量中控制；下单成功后按课程删除购物车项。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `Order` | `userId`、`totalAmount`、`realAmount`、`status`（待支付/已支付/已关闭/已完成…）、`payTime` |
| `OrderDetail` | `orderId`、`courseId`、`courseName`、`price`（下单快照） |
| `Cart` | `userId`、`courseId`、快照字段 |
| `RefundApply` | `orderId`、`refundAmount`、`status`（审批/退款进度） |

## 6. 配置说明

- 端口 8088；`tj.jdbc.database: tj_trade`；Nacos 共享配置 + 多环境 yml。

## 7. 依赖关系

- **Feign**：CourseClient（课程信息/校验上架）、PromotionClient（优惠券折扣计算）、UserClient；对 tj-pay 走 **tj-pay 的 SDK（PayClient）直连**而非 tj-api。
- **MQ 发布**：`order.pay`、`order.refund`（→ `order.topic`）、`delay.order.query`（→ `trade.delay.topic`）。
- **MQ 消费**：`pay.success`、`refund.status.change`、`delay.order.query`（`pay.topic`）。
- 被依赖：tj-api 的 TradeClient/CartClient（learning 校验报名、aigc 下单等）。

## 8. 注意事项

1. **延迟查单循环**依赖 RabbitMQ 延迟消息可靠性；MQ 不可用时订单可能停留在待支付，需人工对账或依赖 tj-pay 的 `pay.success` 回调兜底。
2. 订单/明细存课程价格快照，课程改价不影响历史订单。
3. 免费课报名不发支付流程，直接完成并触发 `order.pay`，注意与正常单状态机区分。
4. `PayServiceImpl` 在本工作区有未提交修改，以磁盘代码为准。
