# tj-learning

> 学习中心：课表与学习计划、学习记录、互动问答（Q&A）、笔记、签到、积分与排行榜，是用户侧学习行为的集中地；消费订单/学习/点赞类 MQ 消息驱动积分与课表。

## 1. 模块概述

- **模块职责**：
  - **课表/学习计划**：购买课程自动入课表、最近学习、周学习计划。
  - **学习记录**：视频观看进度（延迟任务合并写库，防高频刷库）。
  - **互动问答**：课程提问/回复（含管理端审核隐藏、AI 自动回复）。
  - **笔记**：课程笔记与收藏（管理端隐藏管理）。
  - **签到与积分**：Redis BitMap 签到、积分明细、赛季积分榜（ZSet + XXL-Job 持久化）。
- **服务名/端口**：`learning-service`，端口 **8090**。
- **网关路由前缀**：`/ls/**`。
- **数据库**：MySQL `tj_learning`。
- **外部依赖**：Redis（签到 BitMap、积分 ZSet、学习记录缓存）、RabbitMQ、XXL-Job、Feign（TradeClient/CourseClient/RemarkClient/AigcClient/UserClient）。

## 2. 模块结构

```
com.tianji.learning
├── controller   # 11 个 Controller（见下）
├── service(+impl) # 课表/记录/问答/笔记/签到/积分/赛季/AI 回复
├── domain.po    # LearningLesson、LearningRecord、InteractionQuestion、InteractionReply、
│                #   Note、NoteUser、PointsRecord、PointsBoard、PointsBoardSeason
├── mq           # LessonChangeListener、LearningPointsListener、LikeTimesChangeListener
├── handler      # PointsBoardPersistentHandler（XXL-Job ×3）
├── utils        # LearningRecordDelayTaskHandler、DelayTask、TableInfoContext
├── config / constants / enums / mapper
└── LearningApplication
```

## 3. 对外接口

### LearningLessonController（`/lessons`）——课表

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/lessons/page` | 我的课表分页 |
| GET | `/lessons/now` | 最近在学课程（含学习进度） |
| GET | `/lessons/{courseId}` | 查询某课程的学习状态 |
| DELETE | `/lessons/{courseId}` | 删除课程（从课表移除） |
| GET | `/lessons/{courseId}/count` | 课程学习人数统计（内部） |
| GET | `/lessons/{courseId}/valid` | 校验课程是否在课表（内部） |
| POST | `/lessons/plans` | 创建/更新周学习计划 |
| GET | `/lessons/plans` | 查询本周学习计划 |

### LearningRecordController（`/learning-records`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/learning-records/course/{courseId}` | 查询课程学习记录（章节进度） |
| POST | `/learning-records` | 上报学习记录（视频播放心跳）→ 延迟合并写库 |

### 互动问答（Q&A）

- `InteractionQuestionController`（`/questions`）：`POST` 提问、`PUT /{id}` 修改、`GET page`（我的提问/课程提问分页）、`GET {id}` 详情、`DELETE {id}`。
- `InteractionReplyController`（`/replies`）：`POST` 回复、`GET page` 回复分页。
- 管理端：`InteractionQuestionAdminController`（`/admin/questions`：page/详情/隐藏）、`InteractionReplyAdminController`（`/admin/replies`：page/详情/隐藏）。

### 笔记

- `NoteController`（`/notes`）：`POST` 发布、`POST/DELETE /gathers/{id}` 收藏/取消收藏、`PUT /{id}` 编辑、`DELETE /{id}`、`GET page` 我的笔记。
- `NoteAdminController`（`/admin/notes`）：分页、详情、`PUT /{id}/hidden/{hidden}` 显示/隐藏。

### 签到与积分

- `SignRecordController`（`/sign-records`）：`POST` 签到、`GET` 查询本月签到记录。
- `PointsRecordController`（`/points`）：`GET today` 今日积分明细。
- `PointsBoardController`（`/boards`）：`GET` 查询赛季积分榜（当前/指定赛季）、`GET seasons/list` 赛季列表。

## 4. 核心业务逻辑

### 4.1 课表联动（`LessonChangeListener`）

消费 `order.topic`：

- `order.pay`（支付成功）→ `LearningLessonService` 为下单用户创建课表记录（LearningLesson，含有效期）。
- `order.refund`（退款）→ 删除/失效课表记录。

### 4.2 学习记录延迟合并写（`LearningRecordDelayTaskHandler`）★核心优化

- 用户播放视频上报记录时，**先写内存 DelayQueue（延迟 20s）+ Redis 缓存**，不直接落库。
- 守护线程（`init` 中 `CompletableFuture.runAsync`）循环 `queue.take()`，到期后将「最近一次记录」合并写 MySQL，减少高频播放心跳的写压力。
- `@PreDestroy` 钩子：服务停机时把队列中未写任务强制落库，防丢数据。
- 相关类：`DelayTask`（泛型延迟任务）、`TableInfoContext`。

### 4.3 签到（`SignRecordServiceImpl`）

- Redis **BitMap**：key `SIGN_RECORD_KEY_PREFIX + yyyyMM + userId`，当月第几天即 bit 偏移。
- 签到：`setBit`（返回 false 表示已签到）；连续签到天数用 `bitField` 取位图后位运算统计。
- 签到成功后发送 MQ `learning.topic` + `sign.in` 触发积分累加。

### 4.4 积分与赛季榜（`PointsRecordServiceImpl` + `PointsBoardPersistentHandler`）

- **实时榜**：Redis ZSet，key `boards:yyyyMM`（当月赛季），`incrementScore` 累加用户积分；查询排行榜与我的排名从 ZSet 取。
- **积分来源**（`LearningPointsListener`，全部消费 `learning.topic`）：
  | routing key | 事件 | 来源 |
  |---|---|---|
  | `sign.in` | 签到 | 本服务 |
  | `section.learned` | 完成小节学习 | 本服务（播放完成时发送） |
  | `write.note` | 发布笔记 | 本服务 |
  | `note.gathered` | 笔记被收藏 | 本服务 |
  | `reply.new` | 问答被回复 | 本服务问答模块 |
- **赛季持久化**（XXL-Job，`PointsBoardPersistentHandler`）：
  - `createTableJob`：月初为上个月赛季动态建 `points_board` 分表。
  - `savePointsBoard2DB`：把 Redis ZSet 榜单落库。
  - `clearPointsBoardFromRedis`：清理 Redis 中过期赛季数据。
- 赛季信息存 `PointsBoardSeason`。

### 4.5 问答与 AI 自动回复（`AIServiceImpl`）

- 提问后可触发 AI 自动回复：`AIServiceImpl.autoReply` 经 **AigcClient**（Feign → tj-aigc）生成回复内容并落为一条回复。
- 点赞数变更：`LikeTimesChangeListener` 消费 `like.record.topic`（`QA.times.changed`/`NOTE.times.changed`），同步问答/笔记的点赞数（数据来自 tj-remark）。

## 5. 数据模型

| 表/实体 | 说明 |
|---|---|
| `LearningLesson` | 课表：user_id、course_id、状态（学习中/已完成/已过期）、周计划频率、有效期 |
| `LearningRecord` | 小节学习记录：lesson_id、section_id、观看时长/小节时长、完成标记 |
| `InteractionQuestion` / `InteractionReply` | 问答：问题（课程/章节、状态：未查看/已查看）、回复（是否教师回复、parent 回复链） |
| `Note` / `NoteUser` | 笔记内容与用户收藏/笔记归属 |
| `PointsRecord` | 积分明细：user_id、points、type（签到/学习/写笔记/收藏/回复）、season |
| `PointsBoard` / `PointsBoardSeason` | 赛季榜单（按月分表）与赛季定义 |

## 6. 配置说明

- 端口 8090；`tj.jdbc.database: tj_learning`；`tj.auth.resource.enable` 开启登录校验。
- Nacos 共享配置 + 多环境 yml。
- XXL-Job 任务：`createTableJob`、`savePointsBoard2DB`、`clearPointsBoardFromRedis`。

## 7. 依赖关系

- **Feign 依赖**：TradeClient（查询报名课程/订单）、CourseClient（章节/课程信息）、RemarkClient（点赞数）、AigcClient（AI 回复）、UserClient。
- **MQ 消费**：`order.pay`/`order.refund`（订单交换机）、`sign.in`/`section.learned`/`write.note`/`note.gathered`/`reply.new`（学习交换机）、`*.times.changed`（点赞交换机）。
- **MQ 发布**：`sign.in`、`section.learned`、`write.note`、`note.gathered`、`reply.new`（→ `learning.topic`）。
- 被依赖：其他服务经 LearningClient 校验课表/课程学习状态。

## 8. 注意事项

1. **LearningRecordDelayTaskHandler 是单机内存队列**：多实例部署时各节点只合并自己收到的请求；重启依赖 `@PreDestroy` 落库，强杀进程可能丢 20 秒内的进度。
2. 积分实时榜强依赖 Redis ZSet，Redis 故障会丢当月实时积分（可由 `savePointsBoard2DB` 任务对账恢复）。
3. `points_board` 分表由 XXL-Job 动态创建，新环境必须注册并执行 `createTableJob`，否则跨月查询报错。
4. `PointsRecordServiceImpl`、`LearningRecordDelayTaskHandler` 有未提交修改，以磁盘代码为准。
5. 问答/笔记的点赞数来自 tj-remark 异步同步，存在短暂不一致属正常。
