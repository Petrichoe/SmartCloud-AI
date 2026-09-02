# 海量积分实时排行榜整体设计概述

## 1. 简历描述

> 设计海量积分实时排行榜：基于 Redis ZSet 实现百万级用户实时排名，突破数据库排序性能瓶颈。并结合赛季动态分表与 XXL-JOB 分片广播完成异步批量持久化，缓解单表数据膨胀与数据库排序压力。

这条描述对应 `tj-learning` 中的积分与赛季排行榜模块。整体采用“Redis 实时榜 + MySQL 历史快照”的双层架构：当前赛季的积分和排名由 Redis ZSet 维护，历史赛季在赛季结束后由 XXL-JOB 异步落库。

## 2. 整体架构

```text
用户行为：学习、签到、考试、写笔记、问答
                    |
                    v
          RabbitMQ learning.topic
                    |
          按 RoutingKey 路由到积分队列
                    |
                    v
          LearningPointsListener
                    |
                    v
          PointsRecordServiceImpl
             |                 |
             v                 v
      MySQL points_record   Redis ZSet
      积分明细/规则校验       boards:yyyyMM
                              当前赛季实时榜

每月赛季结束后：

XXL-JOB -> 创建 points_board_{seasonId}
        -> 分页、分片读取上月 ZSet
        -> 批量写入历史赛季表
        -> 删除已持久化的上月 Redis Key
```

核心代码位置：

| 能力 | 代码位置 |
|---|---|
| MQ 交换机和 RoutingKey | `tj-common/src/main/java/com/tianji/common/constants/MqConstants.java` |
| MQ 发送封装 | `tj-common/src/main/java/com/tianji/common/autoconfigure/mq/RabbitMqHelper.java` |
| 积分消息消费者 | `tj-learning/src/main/java/com/tianji/learning/mq/LearningPointsListener.java` |
| 积分明细和 ZSet 更新 | `tj-learning/src/main/java/com/tianji/learning/service/impl/PointsRecordServiceImpl.java` |
| 当前/历史榜查询 | `tj-learning/src/main/java/com/tianji/learning/service/impl/PointsBoardServiceImpl.java` |
| 赛季榜持久化任务 | `tj-learning/src/main/java/com/tianji/learning/handler/PointsBoardPersistentHandler.java` |
| 动态表名拦截器 | `tj-learning/src/main/java/com/tianji/learning/config/MybatisConfiguration.java` |

## 3. 实时积分链路

### 3.1 MQ 事件模型

项目使用 `learning.topic` 作为学习领域的 Topic Exchange，行为类型放在 RoutingKey 中，用户信息放在消息体中。

| 行为 | RoutingKey | 消息体 | 消费队列 | 规则 |
|---|---|---|---|---:|
| 签到 | `sign.in` | `SignInMessage(userId, points)` | `sign.points.queue` | 基础 1 分，连续签到有额外奖励 |
| 完成学习小节 | `section.learned` | `userId` | `learning.points.queue` | 10 分 |
| 发布笔记 | `note.new` | `userId` | `note.new.points.queue` | 3 分 |
| 收藏笔记 | `note.gathered` | `userId` | `note.gathered.points.queue` | 2 分 |
| 写回答 | `reply.new` | `userId` | `qa.points.queue` | 5 分 |

消息生产者只需要指定：

```text
exchange = learning.topic
routingKey = sign.in / section.learned / ...
message = userId 或业务消息对象
```

RabbitMQ 根据 RoutingKey 将消息路由到对应的持久化队列。公共 MQ 配置使用 Jackson JSON 转换器，并为消息添加消息 ID 和请求链路 ID，便于追踪和失败处理。

### 3.2 消费与积分入榜

消费者接收到消息后，根据行为类型调用统一方法：

```java
recordService.addPointsRecord(userId, points, type);
```

`PointsRecordServiceImpl` 的处理顺序是：

1. 对有每日上限的积分类型查询当天已得积分，并计算本次实际可发放积分。
2. 写入 `points_record`，保存用户、积分类型、积分值和创建时间。
3. 计算当前月份 Key，例如 `boards:202608`。
4. 使用 Redis `ZINCRBY` 累加用户积分。

Redis 中的数据结构为：

```text
Key：boards:yyyyMM
Member：userId
Score：用户本月累计积分
```

例如：

```text
ZINCRBY boards:202608 10 1001
```

Redis 会在累加分数后自动调整用户在有序集合中的位置，不需要应用层重新排序。

### 3.3 实时榜查询

当前赛季查询直接访问 Redis：

- 榜单列表使用 `ZREVRANGE WITHSCORES`，按分数从高到低分页读取。
- 查询个人积分使用 `ZSCORE`。
- 查询个人排名使用 `ZREVRANK`，返回值加 1 后作为业务排名。
- 榜单中只保存用户 ID，用户姓名通过 `UserClient` 批量查询，避免 N+1 次远程调用。

对于 `N` 个用户和 `K` 条返回记录，主要操作复杂度大致为：

```text
ZINCRBY：       O(logN)
ZREVRANK：      O(logN)
ZREVRANGE：     O(logN + K)
```

因此实时请求不需要执行 MySQL 的全量 `ORDER BY points DESC`，数据库只承载积分明细写入和规则查询。

## 4. 赛季异步持久化链路

### 4.1 调度时机

`PointsBoardPersistentHandler` 中的 `@XxlJob` 只注册任务名称，Cron 周期由 XXL-JOB 管理端配置，项目 Java 代码本身没有写死执行间隔。

部署上可以配置为每月 1 号凌晨执行，例如：

```text
03:00 createTableJob
03:10 savePointsBoard2DB
03:30 clearPointsBoardFromRedis
```

三个任务必须保证先后顺序。创建表和清理 Key 只需要单节点执行，榜单全量持久化任务适合使用分片广播。

### 4.2 创建赛季历史表

`createTableJob` 获取上月时间，并通过 `PointsBoardSeasonService` 查询上月赛季 ID。假设赛季 ID 为 12，则创建：

```text
points_board_12
```

历史表当前结构为：

```text
id       排名，持久化时写入 rank
user_id  用户 ID
points   最终积分
```

赛季 ID 通过表名体现，表中没有单独的 `season_id` 字段。

### 4.3 分页、分片读取 Redis

持久化任务读取上月 Key：

```text
boards:202608
```

当前实现每页读取 10 条，然后批量写入数据库。如果 XXL-JOB 配置了 3 个分片执行器：

```text
执行器 0：1、4、7、10 ... 页
执行器 1：2、5、8、11 ... 页
执行器 2：3、6、9、12 ... 页
```

实现方式是：

```java
int pageNo = shardIndex + 1;
pageNo += shardTotal;
```

每个执行器访问同一个 Redis ZSet，但处理不同分页，因此不会重复写入同一批榜单数据。

### 4.4 保存最终排名

Redis 返回用户 ID 和积分后，代码根据分页位置计算排名：

```java
b.setId(b.getRank().longValue());
b.setRank(null);
```

最终保存为：

```text
id = rank
user_id = Redis member
points = Redis score
```

历史查询时再使用 `id` 还原排名。

### 4.5 清理旧榜单和开启新赛季

持久化完成后，`clearPointsBoardFromRedis` 使用 `UNLINK` 删除上月 Key，释放 Redis 内存。

新赛季没有显式的“开启”方法。新的积分行为会根据新的月份生成新 Key，例如：

```text
boards:202609
```

第一次执行 `ZINCRBY` 时，Redis 自动创建新的 ZSet。

## 5. 动态分表的实现方式

实体类统一映射逻辑表：

```java
@TableName("points_board")
```

历史查询或持久化前，将真实表名放入 `ThreadLocal`：

```java
TableInfoContext.setInfo("points_board_12");
```

MyBatis-Plus 的 `DynamicTableNameInnerInterceptor` 检测到 SQL 中存在 `points_board` 后，从 `TableInfoContext` 读取真实表名并改写 SQL：

```sql
-- MyBatis 逻辑 SQL
INSERT INTO points_board (...)

-- 拦截器改写后的实际 SQL
INSERT INTO points_board_12 (...)
```

创建表本身不是通过拦截器完成，而是由 Mapper 直接执行：

```sql
CREATE TABLE `points_board_12` (...)
```

因此，项目采用的是应用层动态表路由，而不是引入数据库中间件做分库分表。

## 6. 为什么使用 XXL-JOB 分片广播

如果百万用户全部持久化，当前每页 10 条，单机可能需要处理约 10 万页。普通单机定时任务也可以完成，但所有 Redis 分页读取和 MySQL 写入都集中在一台机器上，任务执行时间和失败影响范围较大。

XXL-JOB 分片广播的价值是：

- 多个 `learning-service` 实例并行处理不同分页；
- 缩短月初全量快照的执行时间；
- 降低单个执行器长时间占用 CPU、网络和数据库连接的风险；
- 利用 XXL-JOB 的任务日志、失败重试、手动触发和执行器管理能力。

需要注意，分片广播主要分摊应用层读取和数据库写入压力，并不会自动把一个 Redis ZSet 拆成多个 Key。所有分片仍然访问同一个月度榜单 Key。

## 7. 容错与一致性

### 7.1 Redis 丢失

当前实时榜依赖 Redis ZSet。如果月初持久化前 Redis 数据丢失：

- 当前赛季的实时积分和排名不可直接查询；
- `savePointsBoard2DB` 也无法从 Redis 生成完整历史快照；
- MySQL `points_record` 中的积分明细如果仍在，可以理论上按赛季时间范围聚合重建 ZSet；
- 当前仓库中尚未实现完整的“从积分明细重建排行榜”任务。

生产环境应结合 Redis 副本、故障转移、AOF/RDB 备份，以及 MySQL 到 Redis 的重建任务形成恢复闭环。

### 7.2 MQ 重复消费

积分明细写入 MySQL 和 ZSet 更新不是一个分布式事务，MQ 重试或重复投递可能造成重复加分。更完整的实现需要为积分事件增加唯一业务 ID，并在消费端做幂等控制；也可以使用 Outbox 等最终一致性方案。

### 7.3 赛季边界

当前代码通过月份 Key 实现自然切换，没有显式的赛季冻结标记。调度任务也必须保证“建表 → 持久化 → 清理”的顺序；`TableInfoContext.remove()` 应放在 `finally` 中，避免任务异常时 ThreadLocal 残留。

## 8. 当前代码与简历表述的边界

这套设计具备百万级排行榜的算法和架构基础，但仓库中没有压测报告，不能仅凭代码证明已经稳定承载百万并发用户。

另外，当前代码中存在以下需要确认的实现差异：

- `section.learned` 的消费者已经存在，但仓库内暂未找到对应生产端；考试完成流程目前保存学习记录，但没有看到发送积分 MQ 的代码。
- 实体和注释提到前 100 名，但持久化任务当前会遍历整个 ZSet，接口也没有强制限制页大小为 100。
- 问答积分生产端目前传入了常量 `5`，而消费者把消息当作 `userId`，需要确认是否应传当前用户 ID。

更严谨的简历表述可以是：

> 基于 Redis ZSet 构建当前赛季积分实时排行榜，通过 RabbitMQ 解耦学习行为与积分处理，使用 `ZINCRBY` 和 `ZREVRANK` 实现实时加分及排名查询；月初使用 XXL-JOB 分片广播将上赛季榜单分页持久化到动态赛季表，并在完成后清理旧 Redis Key，降低数据库实时排序和单表数据膨胀压力。

