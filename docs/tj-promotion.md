# tj-promotion

> 营销中心：优惠券（发放/领取/核销）、兑换码、折扣计算方案。高并发领券采用「Lua 脚本原子校验 + MQ 异步落库」，折扣计算采用策略模式，供 tj-trade 下单时调用。

## 1. 模块概述

- **模块职责**：
  - 优惠券管理（CRUD、发放/暂停）与用户领券（异步化、高并发安全）。
  - 兑换码生成（加密位运算）与兑换（换课/换券）。
  - 折扣方案计算（订单可用券组合、各券优惠明细），供下单确认与订单详情查询。
  - 券核销/退回（订单支付/退款联动）。
- **服务名/端口**：`promotion-service`，端口 **8092**。
- **网关路由前缀**：`/prs/**`。
- **数据库**：MySQL `tj_promotion`（coupon、coupon_scope、user_coupon、exchange_code、promotion）。
- **外部依赖**：Redis（Lua 原子脚本）、RabbitMQ（`promotion.topic`）、XXL-Job（定时发放）。

## 2. 模块结构

```
com.tianji.promotion
├── controller   # CouponController / UserCouponController / ExchangeCodeController
├── service(+impl) # ICouponService、IUserCouponService、IExchangeCodeService、IDiscountService、IPromotionService、ICouponScopeService
├── handler      # PromotionMqHandler（MQ 消费）、CouponIssueTaskHandler/CouponJobHandler（XXL-Job 发放）
├── strategy.discount # Discount 策略：PriceDiscount（满减）、RateDiscount（折扣）、PerPriceDiscount（每减）、NoThresholdDiscount
├── strategy.scope    # Scope 策略：CourseScope/CategoryScope/NoScope + ScopeNameHandler
├── utils        # CodeUtil/BitConverter/Base32/AESUtil（兑换码）、MyLock*/RedisLock（分布式锁）、PermuteUtil（券组合枚举）
├── domain.po    # Coupon、CouponScope、UserCoupon、ExchangeCode、Promotion
└── resources/lua # receive_coupon.lua、exchange_coupon.lua
```

**设计文档**（`src/main/resources/doc/`，改券/兑换码逻辑前先读）：
- `COUPON_ASYNC_RECEIVE.md`：异步领券方案
- `COUPON_LUA_OPTIMIZATION.md`：Lua 脚本优化
- `exchange-code-security-refactoring.md`：兑换码安全重构

## 3. 对外接口

### CouponController（`/coupons`）——管理端

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/coupons` | 新增优惠券（含使用范围 CouponScopeDTO） |
| GET | `/coupons/page`、`/coupons/{id}` | 分页/详情 |
| PUT | `/coupons/{id}/issue` | **发放**（定时任务 `issueStartTime` 由 XXL-Job 触发实际投放） |
| PUT | `/coupons/{id}/pause` | 暂停发放 |
| GET | `/coupons/list` | 正在发放中的券（前台领取列表） |
| DELETE | `/coupons/{id}` | 删除（未发放才可删） |

### UserCouponController（`/user-coupons`）——用户侧

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/user-coupons/{couponId}/receive` | **领取优惠券**（Lua 校验 + MQ 异步落库） |
| POST | `/user-coupons/{code}/exchange` | **兑换码兑换**（Lua 校验 + MQ 落库） |
| GET | `/user-coupons/page` | 我的券分页（状态过滤） |
| POST | `/user-coupons/available` | 查询订单可用券（给预下单页） |
| POST | `/user-coupons/discount` | **计算折扣方案**（TradeClient 调用：多券组合最优方案 + 每张券优惠明细） |
| PUT | `/user-coupons/use` | 核销（下单成功后） |
| PUT | `/user-coupons/refund` | 退回（退款后） |
| GET | `/user-coupons/rules` | 折扣规则文案 |

### ExchangeCodeController（`/codes`）

- `GET /codes/page`：兑换码分页（管理端，按券/状态查询）。

## 4. 核心业务逻辑

### 4.1 异步领券（★核心链路，见 COUPON_ASYNC_RECEIVE.md）

1. `receiveCoupon(couponId)`：
   - 读券缓存（Redis Hash：`totalNum`、`issueEndTime`、`userLimit`），不存在直接拒绝（未开始/已结束）。
   - 执行 **`lua/receive_coupon.lua`** 原子校验（KEYS[1]=券 Hash，KEYS[2]=用户领取数 Hash）：
     1. 券缓存不存在 → 返回 1（拒绝）
     2. `totalNum <= 0` → 返回 2（已抢完）
     3. Redis 时间超过 `issueEndTime` → 返回 3（已结束，用 Redis 时间防本机时钟不一致）
     4. `HINCRBY` 用户领取数 +1 后超过 `userLimit` → 返回 4（超限）
     5. 全部通过：`HINCRBY totalNum -1`，返回 0
   - 校验通过 → **发 MQ** `promotion.topic` + `coupon.receive`（UserCouponDTO），立即返回成功。
2. `PromotionMqHandler.listenCouponReceiveMessage` 消费消息 → `checkAndCreateUserCoupon` 校验后写 `user_coupon` 表（真实库存以 DB 兜底，异常可回补 Redis）。

### 4.2 兑换码（见 exchange-code-security-refactoring.md）

- 生成：`ExchangeCodeServiceImpl.asyncGenerateCode`——券发放时按 `totalNum` 异步生成，`CodeUtil.generateCode(serialNum, couponId)` 用**位运算 + Base32 + AES** 把兑换目标与序列号编码为 24 位码，兑换位标记存 Redis（`updateExchangeMark` 防重复生成）。
- 兑换：`exchangeCoupon(code)` → `CodeUtil` 解码出 couponId → 执行 **`lua/exchange_coupon.lua`** 校验兑换状态 → 发 `coupon.receive` MQ 落库。
- 兑换码也可兑换课程（targetId 指向课程，`exchangeTargetId`），被 tj-learning 的兑换功能调用。

### 4.3 折扣计算（`DiscountServiceImpl` + 策略模式）

- `findDiscountSolution(orderCourses)`：
  1. 按券的使用范围（`Scope` 策略：CourseScope 指定课程 / CategoryScope 分类 / NoScope 无限制）筛选每门课可用券。
  2. `PermuteUtil` 枚举券组合，`Discount` 策略算优惠：`PriceDiscount`（满减）、`RateDiscount`（N 折）、`PerPriceDiscount`（每满减）、`NoThresholdDiscount`（无门槛）。
  3. 返回多个 `CouponDiscountDTO` 方案（含每张券的优惠明细 `calculateDiscountDetails` 按比例分摊到课程）。
- `queryDiscountDetailByOrder`：订单详情页按已用券回显优惠明细。
- 核销/退回：`writeOffCoupon`/`refundCoupon`（订单支付成功/退款时由 TradeClient 调用）。

### 4.4 定时发放

- `CouponIssueTaskHandler`（XXL-Job `couponIssueJobHandler`）：定时扫描到达 `issueStartTime` 的券，写缓存并置为发放中（`CouponJobHandler` 为旧实现，注解已注释）。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `Coupon` | `title`、`type`（满减/折扣/每减）、`discountValue`、`thresholdAmount`、`totalNum`、`userLimit`、`obtainType`（手动/兑换码）、`issueStartTime/issueEndTime`、`status` |
| `CouponScope` | 券使用范围：`scopeType`（课程/分类/无）、`scopeIds` |
| `UserCoupon` | `userId`、`couponId`、`status`（待使用/已使用/已过期）、`usedTime`、兑换来源标记 |
| `ExchangeCode` | `code`（加密串）、`serialNum`、`couponId`/`targetId`、`exchangeMark`（已兑换标记）、`status` |
| `Promotion` | 营销活动（预留/扩展） |

## 6. 配置说明

- 端口 8092；`tj.jdbc.database: tj_promotion`；Nacos 共享配置 + 多环境 yml。
- 兑换码加密密钥在 `ExchangeCodeConfig`/`CodeUtil` 相关配置中，**泄露可伪造兑换码**。
- XXL-Job：`couponIssueJobHandler`。

## 7. 依赖关系

- **被依赖**：tj-trade（预下单折扣计算、核销/退回）、tj-learning（兑换码换课，经 PromotionClient/本地 service）。
- **MQ**：发布/消费 `promotion.topic` + `coupon.receive`（自己发自己收，异步落库）。
- 外部中间件：Redis（Lua、缓存）、RabbitMQ、XXL-Job。

## 8. 注意事项

1. **Redis 是领券第一道闸**：Lua 判定通过≠落库成功，MQ 消费失败要能靠 error 队列/人工对账回补 `totalNum`。
2. 券缓存 key 与 Lua 脚本强耦合，改字段名（totalNum/userLimit/issueEndTime）必须同步改 Lua，否则校验失效。
3. 兑换码依赖密钥与位运算格式，不要改动 `CodeUtil` 编码规则而不历史兼容，否则存量码作废。
4. 本工作区 `CouponServiceImpl`、`UserCouponServiceImpl`、`DiscountServiceImpl`、`PromotionMqHandler`、`receive_coupon.lua` 均有未提交修改，本文档以磁盘现状为准。
