# tj-search

> 课程搜索服务：基于 Elasticsearch 的课程全文检索（高亮/分词/多条件过滤）、首页推荐位（精品/新课/免费）、用户兴趣偏好。课程索引由 MQ 消费 course-service 的上下架事件维护。

## 1. 模块概述

- **模块职责**：
  - 课程 ES 索引维护：消费 `course.up/down/expire` 等消息同步索引；`/courses/up|down` 也提供手动上架/下架索引操作。
  - 课程搜索：多条件分页（关键词高亮、分类/价格过滤、排序）。
  - 推荐位：销量 TopN（best）、最新 TopN（new）、免费课程（free）。
  - 兴趣偏好（Interests）：用户兴趣分类记录与按兴趣推荐课程。
  - 订单联动：消费 `order.pay/refund` 维护课程销量/报名数（用于推荐位排序）。
- **服务名/端口**：`search-service`，端口 **8083**。
- **网关路由前缀**：`/ss/**`。
- **数据库**：MySQL `tj_search`（存 Interests 偏好）+ **Elasticsearch（course 索引，主存储）**。
- **外部依赖**：Elasticsearch RestHighLevelClient、RabbitMQ、MySQL。

## 2. 模块结构

```
com.tianji.search
├── controller   # CourseController（搜索）、RecommendController（推荐位）、InterestsController（兴趣）
├── service(+impl) # ISearchService（检索）、ICourseService（索引维护）、IInterestsService
├── mq           # CourseEventListener（课程上下架）、OrderEventListener（订单支付/退款）
├── repository   # CourseRepository / CourseRepositoryImpl（ES 封装）
├── domain.po    # Course（ES 文档）、Interests（MySQL）
├── config       # ElasticSearchConfig、InterestsProperties
└── enums        # CourseStatus
```

## 3. 对外接口

### CourseController（`/courses`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/courses/portal` | **课程搜索**：关键词 + 分类 + 价格区间 + 排序 + 分页（结果带高亮） |
| GET | `/courses/name` | 关键词联想：按名称模糊查课程 id 列表（供下单/内部引用） |
| POST | `/courses/up` | 手动上架课程进索引（内部） |
| POST | `/courses/down` | 手动下架移出索引（内部） |

### RecommendController（`/recommend`）

- `GET /recommend/best`：精品课程 TopN（按销量）。
- `GET /recommend/new`：最新课程 TopN。
- `GET /recommend/free`：免费课程列表。

### InterestsController（`/interests`）

- `POST /interests`：保存用户兴趣（分类集合）。
- `GET /interests`：查询当前用户兴趣。
- `GET /interests/{id}/courses`：按兴趣分类推荐课程。

## 4. 核心业务逻辑

### 4.1 索引同步（`CourseEventListener`）

消费 `course.topic`：

| routing key | 动作 |
|---|---|
| `course.up` | 调 CourseClient 拉课程详情 → 写入 ES 索引 |
| `course.down` | 从 ES 删除（或标记下架状态） |
| `course.expire` | 过期处理（同下架） |

### 4.2 销量联动（`OrderEventListener`）

- 消费 `order.pay`：`OrderBasicDTO` 中的课程销量累加进 ES 文档（sold 字段），驱动 `/recommend/best` 排序。
- 消费 `order.refund`：销量回减。

### 4.3 检索实现（`SearchServiceImpl`，基于 RestHighLevelClient）

- `queryCoursesForPortal`：`BoolQuery` 组合（must 分词匹配 + filter 精确过滤）→ `HighlightBuilder` 对课程名称高亮 → 分页聚合为 `PageDTO<CourseVO>`。
- `queryBestTopN/queryNewTopN/queryFreeTopN`：按 sold/发布时间/价格排序取 TopN。
- `queryCourseByCateId`：按二级分类过滤（首页分类页签）。
- `queryCoursesIdByName`：名称分词匹配取 id（供其它服务联动）。
- ES 客户端为 **elasticsearch-rest-high-level-client**（版本由父 pom `${elasticsearch.version}` 决定，7.x；注意与 AIGC 模块的向量检索 8.x 客户端是两套）。

## 5. 数据模型

- **ES 文档 `Course`**：id、name（分词/高亮字段）、categoryId 一~三级、price、sold（销量）、type（免费/付费）、status、发布时间等。
- **MySQL `Interests`**：userId、categoryId（用户兴趣偏好，用于推荐）。

## 6. 配置说明

- 端口 8083；`tj.jdbc.database: tj_search`；ES 地址经 `ElasticSearchConfig`（yml/Nacos）。
- `InterestsProperties`：兴趣推荐相关参数。

## 7. 依赖关系

- **MQ 消费**：`course.up/down/expire`（course.topic）、`order.pay/refund`（order.topic）。
- **Feign**：CourseClient（上架时拉课程详情）。
- 被依赖：前端（搜索/推荐/兴趣），内部经 SearchClient。

## 8. 注意事项

1. **索引数据以 MQ 驱动**：ES 与 DB 最终一致；搜索不到新课程时先查 `course.topic` 消息与死信队列。
2. 本模块 RestHighLevelClient 与 tj-aigc 的 spring-ai ES 向量仓库依赖并存于不同服务，勿混用版本配置。
3. `/courses/up|down`、`/courses/name` 属内部接口，权限与调用方注意收敛。
4. 本工作区 `SearchServiceImpl` 有未提交修改，以磁盘代码为准。
