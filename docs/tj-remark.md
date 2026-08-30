# tj-remark

> 互动评价中心：点赞记录（问答 QA / 笔记 NOTE），Redis Set 记明细、String 计数，定时任务批量把点赞数变更通知 tj-learning。

## 1. 模块概述

- **模块职责**：业务对象（问答、笔记）的点赞/取消点赞；点赞数统计；定时（20s）扫描点赞数变更并发 MQ 通知 learning 同步展示字段。
- **服务名/端口**：`remark-service`，端口 **8091**。
- **网关路由前缀**：`/rs/**`。
- **数据库**：MySQL `tj_remark`（表：liked_record）+ **Redis 承担实时计数**。
- **外部依赖**：Redis、RabbitMQ（`like.record.topic`）、@Scheduled 定时任务。

## 2. 模块结构

```
com.tianji.remark
├── controller        # LikedRecordController
├── service(+impl)    # ILikedRecordService：LikedRecordServiceImpl（DB）、LikedRecordServiceRedisImpl（Redis 计数实现）
├── task              # LikedTimesCheckTask（@Scheduled 20s）
├── domain            # po: LikedRecord；dto: LikeRecordFormDTO
└── constants         # RedisConstants
```

## 3. 对外接口

### LikedRecordController（`/likes`）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/likes` | 点赞/取消点赞（LikeRecordFormDTO：bizId、bizType、liked 布尔） |
| GET | `/likes/list` | 批量查询点赞数（内部：业务 id 集合 → 各自点赞数，供 RemarkClient 调用） |

## 4. 核心业务逻辑

### 4.1 点赞存储（双实现）

- Redis key 设计（`RedisConstants`）：
  - 明细：`likes:set:biz:{bizType}:{bizId}` → Set 存点赞用户 id（防重复点赞）。
  - 计数：`likes:times:type:{bizType}:{bizId}` → 点赞总数。
- `LikedRecordServiceImpl` 同时落 `liked_record` 表（可追溯）；Redis 实现为线上读写路径。

### 4.2 点赞数变更通知（`LikedTimesCheckTask`）★与 learning 的联动

- `@Scheduled(fixedDelay = 20000)`：每 20 秒对 `QA`、`NOTE` 两类业务各扫描最多 30 条有计数变化的对象（`readLikedTimesAndSendMessage`）。
- 变更结果发 MQ：`like.record.topic` + `{bizType}.times.changed`（即 `QA.times.changed` / `NOTE.times.changed`）。
- tj-learning 的 `LikeTimesChangeListener` 消费后更新问答/笔记表的点赞数字段——**展示层点赞数是最终一致**。

## 5. 数据模型

| 表 | 关键字段 |
|---|---|
| `liked_record` | `userId`、`bizId`、`bizType`（QA/NOTE）、`createTime`（唯一键防重复点赞） |

## 6. 配置说明

- 端口 8091；`tj.jdbc.database: tj_remark`；扫描周期/批量大小在 `LikedTimesCheckTask` 常量中（`MAX_BIZ_SIZE=30`）。

## 7. 依赖关系

- 被依赖：tj-learning（RemarkClient 拉点赞数、MQ 同步变更）。
- 外部中间件：Redis、RabbitMQ。

## 8. 注意事项

1. 点赞链路是「Redis 实时 + 定时批量同步 DB」的最终一致模型，前端看到的计数可能滞后 ≤20s。
2. 新增点赞业务类型（如评论）需要：`BIZ_TYPES` 加类型 + learning 侧新增对应 routing key 消费。
3. Redis 计数与 DB 表可能不一致（缓存清空等），以 `liked_record` 表为事实依据可重建计数。
