# tj-data

> 数据看板服务：管理端大屏/报表的今日概况、Top10 榜单、图表数据查询。数据由外部（XXL-Job 统计任务等）预先算好写入 Redis，本服务只做**读取与组装 ECharts 结构**。

## 1. 模块概述

- **模块职责**：读取 Redis 中预计算的统计结果，组装为前端 ECharts 需要的 VO（轴、系列、数据集），提供三类看板接口：今日数据、Top10、图表看板。
- **服务名/端口**：`data-service`，端口 **8093**。
- **网关路由前缀**：`/ds/**`。
- **数据库**：MySQL `tj_data`（`tj.jdbc.database=tj_data`；PO 含 CourseInfo/TodayDataInfo，作为备份/落库模型）。
- **外部依赖**：Redis（统计数据主来源）。

## 2. 模块结构

```
com.tianji.data
├── controller   # TodayDataController / Top10Controller / BoardController
├── service(+impl) # TodayDataService、Top10Service、BoardService
├── model        # po: CourseInfo、TodayDataInfo；dto: TodayDataDTO、Top10DataSetDTO、BoardDataSetDTO
│                # vo: TodayDataVO、Top10DataVO、EchartsVO、AxisVO、SerierVO
├── constants    # RedisConstants（各看板 key）、DataTypeEnum
└── utils        # DataUtils（版本号/时间工具）
```

## 3. 对外接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/data/today` | 今日概况：访问量、注册量、订单量、成交额等（TodayDataVO） |
| GET | `/data/top10` | Top10 榜单（如课程热度/销量，Top10DataVO） |
| GET | `/data/board` | 图表看板：多维统计曲线（EchartsVO：AxisVO + SerierVO） |

三个接口均为管理端查询，无写接口。

## 4. 核心业务逻辑

- **读缓存模式**：`TodayDataServiceImpl.get()` 等从 Redis 取 key（`RedisConstants.KEY_TODAY` + `DataUtils.getVersion(1)` 版本号），JSON 反序列化为 DTO 再转 VO；缓存不存在返回空/默认结构。
- **数据生产方**：统计结果由外部任务写入 Redis（项目中未见对应生产者代码——如 XXL-Job 统计任务在其它工程/待开发），本服务是纯消费方。
- PO（`CourseInfo`/`TodayDataInfo`）用于统计结果落库留档的模型定义。

## 5. 数据模型

- Redis key（`RedisConstants`）：今日数据、Top10、看板各一组，key 带版本号便于整体切换。
- MySQL：`CourseInfo`、`TodayDataInfo`（模型定义，当前主链路不依赖 DB 查询）。

## 6. 配置说明

- 端口 8093；`tj.jdbc.database: tj_data`；Nacos 共享配置 + 多环境。

## 7. 依赖关系

- 被依赖：管理端前端（经网关 /ds/**）。
- 外部中间件：Redis（只读）。

## 8. 注意事项

1. 本模块**不生产统计数据**，只读 Redis；看板空数据时先排查 Redis key 与数据生产任务是否运行。
2. key 带版本号（`getVersion`），上游写数据时需与 `RedisConstants` 的 key 约定保持一致。
