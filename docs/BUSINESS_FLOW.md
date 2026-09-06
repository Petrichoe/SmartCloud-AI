# 核心业务调用链：从购买课程到学习、积分与排行榜

> 本文梳理用户视角下最核心的一条业务主线：**选课（购物车/直购/免费报名）→ 优惠券计算 → 支付 → 报名入课表 → 学习记录 → 积分 → 排行榜**。
> 依据**当前磁盘代码**逐文件核实（2026-09-06），代码位置为仓库相对路径 + `类#方法`，不写行号（避免漂移）。工作区存在的本地改动（如 mock 支付）已在文中显式标注。

## 0. 全景总览

```
浏览器 tj-portal-src（Vue3，网关前缀 /ts /ps /prs /ls /cs /es，StripPrefix=1）
  │
  │ ① 加入购物车            POST /ts/carts ──────────────► tj-trade: cart 表（快照）
  │ ② 购物车勾选结算          GET  /ts/carts + GET /ts/orders/prePlaceOrder
  │        │                        └► tj-promotion: 查可用优惠券方案（初筛/细筛/排列组合/并行计算/择优）
  │ ③ 提交订单              POST /ts/orders/placeOrder
  │        ├► 写 order + order_detail（NO_PAY）
  │        ├► tj-promotion: 核销优惠券（PUT /user-coupons/use）
  │        └► 删除 cart 对应条目
  │ ④ 免费课直通道          POST /ts/orders/freeCourse/{courseId} ──► 直接 ENROLLED ─┐
  │ ⑤ 支付                  POST /ts/pay/order ─► tj-trade PayServiceImpl
  │        │       [当前工作区=mock 同步成功 | 真实=Feign→tj-pay→支付宝/微信→回调→pay.success]
  │        ▼
  │   支付成功：订单→PAYED，发 MQ order.topic[order.pay]
  │ ⑥ 报名                   tj-learning LessonChangeListener(learning.lesson.pay.queue)
  │        └► 写 learning_lesson（有效期=课程 validDuration 个月）→「我的课表」
  │ ⑥b 退款（逆操作）  申请→审批→tj-pay 渠道退款→回调→refund.status.change
  │        └► order.refund → 删课表（详见 §4.2）
  │ ⑦ 学习                   学习页每 15s POST /ls/learning-records 心跳
  │        └► Redis Hash 暂存 + DelayQueue 20s 合并落库；看够一半算完成小节
  │        └► learning_lesson.learned_sections+1，状态 NOT_BEGIN→LEARNING→FINISHED
  │ ⑧ 积分                   签到/写笔记/写回答等 → MQ learning.topic[...] → points_record
  │        └► 同步累加 Redis ZSet boards:yyyyMM
  │ ⑨ 排行榜                 GET /ls/boards：当前赛季读 ZSet，历史赛季读按赛季分表的 MySQL
  ▼
XXL-Job 月底三任务：建下赛季表 / ZSet 榜单落库(分片) / 清理上月 ZSet
```

## 1. 参与服务与基础设施

| 服务 | 端口 | 网关前缀 | 本主线中的职责 | 主要存储 |
|---|---|---|---|---|
| tj-gateway | 10010 | — | 统一入口、JWT 校验、StripPrefix | — |
| tj-course | 8086 | /cs | 课程信息/目录/小节数（被 Feign 调用） | tj_course |
| tj-promotion | 8092 | /prs | 优惠券：领券、折扣方案计算、核销/退还 | tj_promotion + Redis |
| tj-trade | 8088 | /ts | 购物车、订单、支付发起、支付结果处理 | tj_trade |
| tj-pay | 8087 | /ps | 支付单、渠道（支付宝/微信）、回调验签、退款 | tj_pay |
| tj-learning | 8090 | /ls | 课表、学习记录、签到、积分、赛季榜、问答/笔记 | tj_learning + Redis |
| tj-exam | 8089 | /es | 考试小节的答题（前端直接跳转，不走 learning） | tj_exam |
| tj-user | 8082 | /us | 榜单补齐用户昵称（UserClient） | tj_user |

**反向调用（学习数据反哺内容域）**：tj-course 在删除/管理课程前通过 `LearningClient#queryLearningRecordByCourse` 校验该课程是否已有学习记录；tj-media 通过 `LearningClient#isLessonValid(courseId)` 校验当前用户是否已报名（视频访问鉴权）。即 `tj-api` 中 LearningClient 的生产方在 tj-learning，消费方是 course/media。

**本主线用到的 MQ 消息**（常量定义 `tj-common/src/main/java/com/tianji/common/constants/MqConstants.java`）：

| 交换机 | RoutingKey | 队列（消费方） | 语义 |
|---|---|---|---|
| `pay.topic` | `pay.success` | trade.pay.success.queue | tj-pay 通知 trade 支付成功 |
| `pay.topic` | `refund.status.change` | trade.refund.result.queue | 退款结果 |
| `trade.delay.topic`（延迟插件） | `delay.order.query` | trade.delay.order.query | trade 延迟轮询支付结果 |
| `order.topic` | `order.pay` | learning.lesson.pay.queue | 报名：写课表 |
| `order.topic` | `order.refund` | learning.lesson.refund.queue | 退款：删课表 |
| `learning.topic` | `sign.in` / `note.new` / `note.gathered` / `reply.new` / `section.learned` | 各积分队列 | 学习行为积分 |
| `promotion.topic` | `coupon.receive` | promotion 侧队列 | 异步落库领券记录 |

## 2. 链路一：选课与下单（购物车 + 优惠券）

前端 API 定义：`tj-portal-src/src/api/order.js`（`/ts` 前缀）；页面：课程详情 `src/pages/classDetails/`、购物车 `src/pages/pay/carts.vue`、结算 `src/pages/pay/settlement.vue`。

> **下单入口有两个**：除了页面流程，AIGC 服务的 `BuyAgent`（`tj-aigc/agent/BuyAgent.java`）配合 `tools/OrderTools` 经 `TradeClient#prePlaceOrder` 完成 AI 对话中的预下单，再引导用户进入同一条下单支付流程——两条入口在 tj-trade 汇合。

### 2.1 加入购物车

`POST /ts/carts` → `tj-trade CartController#addCourse2Cart` → `CartServiceImpl#addCourse2Cart`：

1. 去重：按 `(userId, courseId)` count 查库，已存在静默返回；
2. 上限：购物车条目数 ≥ `TradeProperties.maxCourseAmount`（默认 **10**，`tj-trade/config/TradeProperties.java`）则报"购物车已满"；
3. 实时校验课程：Feign `CourseClient#getCourseInfoById` 查课程，不存在报错，`purchaseEndTime` 早于当前时间（过期）不让加；
4. 快照落库：`cart` 表冗余存课程名/封面/加购时价格。

> 注意：去重是"先查后插"，表上没有 `(user_id, course_id)` 唯一键，并发下可能重复插入。

### 2.2 购物车页与勾选

`GET /ts/carts` → `CartServiceImpl#getMyCarts`：条目基础信息用快照，但**现价与过期标记实时查课程服务**（`nowPrice`、`expired`），过期课程排序沉底。前端勾选是纯前端状态，过期条目 checkbox 禁用（`carts.vue` 的 `:disabled="item.expired"`）。点"去结算"把选中条目的 **courseId** 拼参跳结算页。

### 2.3 预下单（确认订单页）

`GET /ts/orders/prePlaceOrder?courseIds=...` → `OrderServiceImpl#prePlaceOrder`：

- Feign 查课程价格（`CourseClient#getSimpleInfoList`）；
- Feign 查可用优惠券方案（`PromotionClient#findDiscountSolution`，见 2.4）；
- 雪花算法预生成 `orderId` 返回前端（此时**不写订单表**）。

> 预下单只校验课程存在，**不校验上架/过期**；强校验在正式下单（见 2.5 末尾）。

### 2.4 优惠券折扣计算（tj-promotion）

入口 Feign 定义 `tj-api/.../client/promotion/PromotionClient.java`，实现 `UserCouponController` + `DiscountServiceImpl`。

**领券（前置）**：`POST /prs/user-coupons/{couponId}/receive` → `UserCouponServiceImpl#receiveCoupon`：Lua 脚本（`lua/receive_coupon.lua`）原子校验"发放总量/每人限领"后发 MQ `promotion.topic[coupon.receive]`，消费者异步落库 `user_coupon`（状态 `UNUSED`）。兑换码兑换同理（`exchange_coupon.lua`）。详见 `tj-promotion/src/main/resources/doc/COUPON_ASYNC_RECEIVE.md` 与 `COUPON_LUA_OPTIMIZATION.md`。

**查可用方案 `findDiscountSolution`（预下单时调用）**，`DiscountServiceImpl`：

1. 查我的未使用券；
2. **初筛**：按订单总价判断每张券门槛（策略 `DiscountStrategy#getDiscount` → `canUse`）；
3. **细筛** `findAvailableCoupon`：`specific=true` 的券查 `coupon_scope` 表限定范围（按课程分类 `cateId` 匹配），对可用课程总价再次 `canUse`；
4. **排列组合**：`PermuteUtil#permute` 生成多券组合 + 单券方案；
5. **并行计算**：每套方案提交线程池 `discountSolutionExecutor` 异步算折扣，`CountDownLatch` 最多等 1 秒；
6. **择优** `findBestSolution`：同券组合取优惠最大者 ∩ 同优惠金额取用券最少者，按优惠金额降序返回。

**按选定券算明细 `queryDiscountDetailByOrder`（下单时调用）**：逐张券对"剩余应付金额"再校验 `canUse`，`calculateDiscount` 得到该券优惠额；**明细分摊** `calculateDiscountDetails` 按课程价格比例分摊，最后一门课用"总额 − 已分摊"差额补齐，避免取整误差。

**券类型**（`DiscountType` 枚举 ↔ `strategy/discount/` 下策略类）：

| 类型 | 策略类 |
|---|---|
| 每满减 PER_PRICE_DISCOUNT(1) | `PerPriceDiscount` |
| 折扣 RATE_DISCOUNT(2) | `RateDiscount` |
| 无门槛 NO_THRESHOLD(3) | `NoThresholdDiscount` |
| 满减 PRICE_DISCOUNT(4) | `PriceDiscount` |

`user_coupon` 状态：`UNUSED(1) / USED(2) / EXPIRED(3)`。

### 2.5 提交订单

`POST /ts/orders/placeOrder` → `OrderServiceImpl#placeOrder`（事务内）：

1. `getOnShelfCourse` 强校验：课程必须**已上架**（`CourseStatus.SHELF`）且**未过期**（`purchaseEndTime.isBefore(now)` 报"课程已过期"）——这是过期判断的唯一强校验点；
2. 计算总价，Feign `PromotionClient#queryDiscountDetailByOrder` 按用户选定的券算优惠，写 `order`（状态 `NO_PAY(1)`）与 `order_detail`；`orderId` 复用预下单返回的 id，重复提交靠主键冲突 `DuplicateKeyException` 拦截；
3. 删除购物车对应课程条目 `CartServiceImpl#deleteCartByUserAndCourseIds`（**下单即清购物车，不等支付成功**；该方法内部吞异常，属尽力而为的旁路操作）；
4. Feign 核销优惠券 `PUT /user-coupons/use`（`UNUSED→USED`）。

### 2.6 免费课直通道

`POST /ts/orders/freeCourse/{courseId}` → `OrderServiceImpl#enrolledFreeCourse`：校验课程免费后直接建 `ENROLLED(5)` 终态订单（同样走 `getOnShelfCourse` 强校验），**不发支付**，在事务提交前直接发 MQ `order.topic[order.pay]`，与付费链路在报名环节汇合。

订单状态机（`trade/constants/OrderStatus.java`）：`NO_PAY(1) → PAYED(2) → FINISHED(4)`；旁路：`CLOSED(3)` 超时/取消、`ENROLLED(5)` 免费报名、`REFUNDED(6)` 退款。

## 3. 链路二：支付

入口 `POST /ts/pay/order` → `tj-trade PayController#applyPayOrder` → `PayServiceImpl#applyPayOrder`。校验订单存在、`NO_PAY` 状态、未超支付时限（`payOrderTTLMinutes` 默认 **30 分钟**）。

### 3.1 当前工作区状态：mock 直成功（重点）

`PayServiceImpl#applyPayOrder` 中真实支付逻辑被注释，当前代码在状态校验通过后**直接构造 `PayResultDTO`（payChannel=mock）同步调用 `OrderServiceImpl#handlePaySuccess` 并返回 `"mock_success"`**——本地联调用，点支付立即成功，真实回调链路不会执行。恢复真实支付需还原该方法中被注释的代码。

### 3.2 真实链路（当前被注释）

1. Feign `PayClient#applyPayOrder` → tj-pay `POST /pay-orders` → `PayOrderServiceImpl#applyPayOrder`：按渠道 `payChannelCode` 路由到 `AliPayService` / `WxPayService` 创建预支付单，返回二维码 URL；
2. trade 同时发**延迟消息** `trade.delay.topic[delay.order.query]`，由自己消费 `PayServiceImpl#queryPayResult` 轮询支付结果（间隔递增，重试耗尽仍未支付则 `cancelOrder` 超时关单并退还优惠券）；
3. 用户付款后渠道回调 tj-pay `NotifyController`（`/notify/wx`、`/notify/ali`）→ `NotifyServiceImpl#handleWxPayNotify / handleAliPayNotify`：**验签 → 金额校验 → Redis 分布式锁 + 乐观锁幂等**（`checkNotifyData`）→ 更新 `pay_order` → 发 MQ `pay.topic[pay.success]`；
4. trade `PayMessageHandler#listenPaySuccess`（队列 `trade.pay.success.queue`）消费，进入 `handlePaySuccess`。另有 `PayOrderCheckTask` 定时兜底核对第三方状态。

### 3.3 支付成功统一处理

`OrderServiceImpl#handlePaySuccess`：订单与明细置 `PAYED(2)`，记录支付时间/渠道/单号，然后发 **`order.topic[order.pay]`**（消息体 `OrderBasicDTO{orderId, userId, courseIds, finishTime}`）。

## 4. 链路三：报名与退款（MQ 解耦）

### 4.1 报名入课表

`tj-learning mq/LessonChangeListener`：

- `learning.lesson.pay.queue` 绑定 `order.pay` → `LearningLessonServiceImpl#addUserLessons`：Feign 回查课程有效期，`expireTime = now + validDuration 个月`，批量插入 `learning_lesson`（初始 `NOT_BEGIN(0)`）→ 用户"我的课表"出现该课；
- `learning.lesson.refund.queue` 绑定 `order.refund` → `deleteCourseFromLesson` 删除课表记录。

课表相关接口（`LearningLessonController`，前缀 `/ls/lessons`）：`GET /page` 我的课表分页（按最近学习时间倒序）、`GET /now` 正在学习的课、`GET /{courseId}` 指定课程报名信息、`DELETE /{courseId}` 移出课表、`POST /plans` 创建学习计划（每周频次）、`GET /plans` 我的学习计划。状态机 `LessonStatus`：`NOT_BEGIN(0) → LEARNING(1) → FINISHED(2)`，另有 `EXPIRED(3)`（当前无人触发，见 §8）。

### 4.2 退款全链路（购买主线的逆操作）

横跨 tj-trade / tj-pay / tj-learning，按"申请 → 审批 → 渠道退款 → 结果回传 → 移出课表"五步：

1. **申请** `POST /ts/refund-apply` → `RefundApplyServiceImpl#applyRefund`：校验免费课不可退、订单状态为 `PAYED/REFUNDED`、学员对同一明细最多申请 2 次、进行中不可重复申请；写 `refund_apply`——学员申请为 `UN_APPROVE`（待审批），管理员操作直接 `AGREE` 并**立即**发起退款；订单与明细同步置 `REFUNDED(6)`。
2. **审批** `PUT /ts/refund-apply/approval` → `approveRefundApply`：同意后经独立线程池 `sendRefundRequestExecutor` 异步执行 `sendRefundRequest` → Feign `PayClient#applyRefund`（tj-pay `POST /refund-orders` → 渠道退款）；拒绝则只更新审批状态，不动钱。学员也可 `PUT /refund-apply/cancel` 撤销未审批的申请。
3. **结果回传**：渠道退款回调 tj-pay `NotifyServiceImpl#handleWxPayRefundNotify` → 验签、幂等更新 `refund_order` → MQ `pay.topic[refund.status.change]` → trade `PayMessageHandler#listenRefundResult` → `handleRefundResult`：退款中（微信只回退款中）仅记录单号；成功则 `refund_apply` 置 `SUCCESS`、明细状态同步，并发 `order.topic[order.refund]`；失败记录原因。消费侧无登录态，方法内 `UserContext.setUser(approver)` 手工填充上下文。
4. **移出课表**：learning `LessonChangeListener#listenLessonRefund` 消费 `order.refund` → `deleteCourseFromLesson` 直接删除 `learning_lesson` 记录。
5. **兜底核对**：XXL-Job `refundRequestJobHandler`（`RefundJobHandler`，分片广播）扫描仍处 `AGREE` 的申请，`checkRefundStatus` 远程核对退款结果并复用 `handleRefundResult` 收敛状态，防回调丢失。

退款状态机 `RefundStatus`：`UN_APPROVE → AGREE/REJECT → SUCCESS/FAILED`（可 `CANCEL`）。注意：退款成功后**不会退还**下单时已核销的优惠券（`handleRefundResult` 中无退券调用）。

## 5. 链路四：学习与学习记录

前端学习页 `tj-portal-src/src/pages/learning/index.vue`；API 在 `src/api/class.js`。

1. **进入学习页**：`GET /ls/lessons/{courseId}`（拿 lessonId、进度）等接口初始化；
2. **进度心跳**：播放器每 **15 秒** `setInterval` 调 `POST /ls/learning-records`，body：`{lessonId, sectionId, moment(已播秒数), duration(小节时长), sectionType, commitTime}`；
3. **合并写**（`LearningRecordServiceImpl` + `LearningRecordDelayTaskHandler`）：
   - 首次提交某小节：直接插入 `learning_record`；
   - 后续心跳：只写 Redis Hash（key `learning:record:{lessonId}`，field=sectionId，1 分钟过期）+ JVM `DelayQueue` 提交 **20 秒延迟任务**；任务到期时比对缓存 `moment` 是否仍是提交值——仍在播放则丢弃旧数据，已停止才把进度落库，同时更新课表 `latest_section_id / latest_learn_time`；
4. **完成小节**：`moment * 2 >= duration`（**看够一半即完成**）→ `learning_record.finished=true`、清缓存 → `handleLearningLessonsChanges`：Feign `CourseClient#getCourseInfoById` 拿总小节数，`learned_sections + 1`；第一节完成推 `LEARNING`，学满推 `FINISHED`；
5. **考试小节**（`SectionType.EXAM`）：答题明细与小节完成记录**分存两个服务、由前端编排缝合，服务间没有调用**——tj-exam 记答题过程（前端 `addExamRecords` 创建记录、`submitExamRecords` 提交答案，走 `/es/exam-records*`）；交卷后由前端**另调** `POST /ls/learning-records`（`sectionType=EXAM`）→ `LearningRecordServiceImpl#handleExamRecord` 直接写一条 `finished=true` 的记录并推进课表进度。若前端漏调后者，考试虽然通过但课表进度不会前进；
6. 查询接口：`GET /ls/learning-records/course/{courseId}` 查指定课程学习记录。

### 学习互动：问答/笔记与点赞同步

学习页的问答与笔记也落在 tj-learning（`InteractionQuestionController`、`NoteController`，前端 `src/api` 对应接口）。点赞数据在 tj-remark（`/rs`）：remark 侧定时汇总点赞数变更，经 `like.record.topic[QA.times.changed]` 批量发送，learning `LikeTimesChangeListener#listenReplyLikedTimesChange`（队列 `qa.liked.times.queue`）消费后批量更新回答/评论表冗余的 `liked_times` 字段——高频点赞写留在 remark，展示侧异步同步。问答/笔记同时是 §6 积分事件（`reply.new`、`note.new`、`note.gathered`）的来源。

## 6. 链路五：积分与排行榜

### 6.1 积分事件 → 入账

监听器 `tj-learning mq/LearningPointsListener`（交换机 `learning.topic`）：

| 行为 | RoutingKey | 队列 | 发送方 | 分值 | 每日上限 |
|---|---|---|---|---|---|
| 写回答 | `reply.new` | qa.points.queue | `InteractionReplyServiceImpl` | +5 | 20（QA） |
| 每日签到 | `sign.in` | sign.points.queue | `SignRecordServiceImpl` | 基础 1 分 + 连签奖励（见 6.2） | 不限 |
| 学完小节 | `section.learned` | learning.points.queue | **当前代码无发送方（见 §8）** | +10 | 50（LEARNING） |
| 写笔记 | `note.new` | note.new.points.queue | `NoteServiceImpl` | +3 | 20（NOTE） |
| 笔记被采集 | `note.gathered` | note.gathered.points.queue | `NoteServiceImpl` | +2 | 20（NOTE） |

入账 `PointsRecordServiceImpl#addPointsRecord`：按类型检查**当日已得积分**，超过 `PointsRecordType` 上限则截断（上限为 0 表示不限），写 `points_record` 表，并 **Redis ZSet `boards:{yyyyMM}` 对 userId `incrementScore`**——积分明细与榜单热数据一次维护。

### 6.2 签到：BitMap + 连续奖励

`SignRecordController` `POST /ls/sign-records` → `SignRecordServiceImpl#addSignRecords`：Redis **BitMap**（按"用户 + 月份"拼 key，`setBit` 当日 offset 去重）→ `bitField` 取位图统计**连续签到天数** → 连续 7/14/28 天奖励 10/20/40 分 → 发 MQ `sign.in`（分值 = 奖励 + 基础 1 分）。`GET /ls/sign-records` 返回当月签到位图。

### 6.3 排行榜（学霸天梯榜）

`PointsBoardController`（前缀 `/ls/boards`）→ `PointsBoardServiceImpl#queryPointsBoardBySeason`：

- **当前赛季**（season 为空/0）：直接查 Redis ZSet——`reverseRangeWithScores` 分页取榜单，`reverseRank` 查我的排名；Feign `UserClient#queryUserByIds` 补用户昵称；
- **历史赛季**：查 MySQL **按赛季分表** `points_board_{season}`（动态表名经 `TableInfoContext`（ThreadLocal）注入 MyBatis），表内 `id` 即名次。

### 6.4 月底结算：XXL-Job 三任务（`handler/PointsBoardPersistentHandler`）

| 任务 | 作用 |
|---|---|
| `createTableJob` | 为上月赛季创建 `points_board_{season}` 分表 |
| `savePointsBoard2DB` | 把上月 ZSet 榜单落库：**分片广播**（`shardIndex` 决定起始页、页码按执行器总数步进），名次写进 `id` |
| `clearPointsBoardFromRedis` | 删除上月 ZSet key |

赛季元数据在 `points_board_season` 表；`GET /ls/boards/seasons/list` 查历史赛季；`GET /ls/points/today` 查我今日各类积分。

## 7. 关键设计点（面试视角）

1. **两级 MQ 解耦**：`pay.success`（支付域→交易域）与 `order.pay`（交易域→学习域）各自独立，报名不依赖支付调用链存活，失败可借 MQ 重试；每层各有幂等手段（pay：锁+乐观锁；trade：状态条件更新；learning：落库前回查）。
2. **心跳合并写**：学习进度从"每 15 秒一次写库"降为"暂停/离开才落库"，靠 Redis 暂存 + 延迟任务比对实现，写库压力与播放时长解耦。
3. **优惠券最优组合**：初筛→细筛→排列组合→线程池并行试算（1 秒超时兜底）→"优惠最大 ∩ 用券最少"双指标择优；明细分摊用"比例分摊 + 末位差额补齐"消误差。
4. **异步领券**：Lua 原子扣减库存/限领次数 + MQ 异步落库，削峰且保证一致性。
5. **榜单读写分离 + 分表**：热数据 ZSet（当月实时排名），冷数据按赛季分表归档（id 即名次），XXL-Job 分片广播批量迁移。
6. **BitMap 签到**：一个月一个 key、一天一个 bit，连续天数用位运算统计，省内存且查询快。
7. **退款多重保障**：发起走独立线程池异步化，结果回传走 MQ 回调，另有 XXL-Job 分片兜底核对滞留的 `AGREE` 申请——三条路径最终收敛到同一个 `handleRefundResult` 状态机，与支付链路的"回调 + 延迟查单 + 定时任务"三层结构互为镜像。

## 8. 当前代码的已知不一致（以工作区现状为准）

1. **mock 支付**：`PayServiceImpl#applyPayOrder` 真实链路被注释，直接同步成功；恢复真实支付时需还原（见 §3.1）。
2. **`section.learned` 无生产者**：`learning.points.queue` 监听器存在，但全仓搜索没有任何服务发送 `section.learned`——即"学完小节 +10 分"目前不会触发；`docs/README.md` 中"learning 发积分 MQ"的描述与现状不符。
3. **课表 `EXPIRED` 是死状态**：`LessonStatus.EXPIRED(3)` 仅有定义，整个 tj-learning 没有任何代码把到期课表置为过期——`addUserLessons` 算出并存储 `expireTime` 后无人消费，没有定时任务，也没有"到期前提醒"的延迟消息；`learning_lesson.expire_time` 目前只是存而不校验。
4. **预下单不校验上架/过期**：确认订单页可能展示过期课程，提交订单时才被拦截。
5. **购物车去重偏弱**：先查后插，无唯一索引兜底，并发下可能重复加购。
6. **前端 `getLearningLog`（`/ls/learning-records/lessons/{lessonId}`）与后端现有接口（`/ls/learning-records/course/{courseId}`）路径不一致**；学习页当前实际使用的是其他初始化接口，该函数未被调用时无实际影响。

## 9. 相关文档

- [项目总体介绍](PROJECT_OVERVIEW.md) / [模块文档索引](README.md)
- 优惠券异步领券：`tj-promotion/src/main/resources/doc/COUPON_ASYNC_RECEIVE.md`
- 优惠券 Lua 优化：`tj-promotion/src/main/resources/doc/COUPON_LUA_OPTIMIZATION.md`
- 兑换码安全重构：`tj-promotion/src/main/resources/doc/exchange-code-security-refactoring.md`
- 认证登录链路图：`docs/diagrams/login-auth-flow.html`
