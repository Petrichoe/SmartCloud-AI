# tj-api

> 服务间通信层：集中定义所有 Feign 客户端、跨服务 DTO 与分类缓存，供各业务服务依赖实现远程调用（集成 Sentinel 降级）。

## 1. 模块概述

- **模块职责**：以 Spring Cloud OpenFeign 接口的形式定义各微服务对外暴露的内部 API；集中存放跨服务传输的 DTO；提供基于 Caffeine 的本地分类/角色缓存。
- **服务形态**：公共依赖库（不独立部署，无端口、无数据库）。
- **通信对象**：`user-service`、`course-service`、`learning-service`、`trade-service`、`promotion-service`、`remark-service`、`exam-service`、`search-service`、`aigc-service`、`auth-service`。

## 2. 模块结构

```
com.tianji.api
├── annotations   # @EnableCategoryCache 启用注解
├── cache         # CategoryCache / RoleCache 本地缓存
├── client        # 各服务 Feign 客户端（按业务域分子包）
│   └── fallback  # Sentinel 降级工厂（FallbackFactory）
├── config        # Feign/缓存配置
├── constants     # CourseStatus、SmsConstants
└── dto           # 跨服务 DTO（按服务分包）
```

### 关键配置类（`config`）

- **`RequestIdRelayConfiguration`**：`@EnableFeignClients(basePackages = "com.tianji.api.client")`，业务服务依赖 tj-api 即自动扫描全部客户端；同时负责透传请求链路 ID（与 tj-common 的 `RequestIdFilter` 配套）。
- **`FallbackConfig`**：降级 Bean 装配。
- **`CategoryCacheConfig` / `RoleCacheConfig`**：Caffeine 本地缓存配置，由 `@EnableCategoryCache` 注解按需开启。

## 3. Feign 客户端清单

| 客户端 | 目标服务 | fallbackFactory | 主要方法（对应服务内部接口） |
|---|---|---|---|
| `UserClient` | user-service | 有 | `GET /users/ids` 批量查用户、`POST /users/detail/{isStaff}` 批量详情、`GET /users/{id}/type`、`GET /users/list`、`GET /users/{id}` |
| `CourseClient` | course-service | 无 | 课程基础/完整信息、购买信息查询 |
| `CatalogueClient` | course-service（path=`catalogues`） | 无 | 课程目录（章节/小节）查询 |
| `CategoryClient` | course-service（path=`categorys`） | 无 | 分类查询 |
| `SubjectClient` | course-service（path=`subjects`） | 无 | 题目主题/考点查询 |
| `LearningClient` | learning-service | 有 | 课表/学习记录相关（如校验课程是否在课表） |
| `TradeClient` | trade-service | 有 | `GET /order-details/enrollNum` 报名人数、`GET /order-details/enrollCourse` 已购课程、`GET /order-details/course/{id}`、`GET /order-details/purchaseInfo`、`GET /orders/prePlaceOrder` 预下单 |
| `CartClient` | trade-service（path=`carts`） | 有 | 购物车操作 |
| `PromotionClient` | promotion-service | 有 | 优惠券折扣计算（`CouponDiscountDTO`/`OrderCouponDTO`） |
| `RemarkClient` | remark-service | 有 | 评价/点赞数查询 |
| `ExamClient` | exam-service | 无 | 题目（QuestionDTO/QuestionBizDTO）查询 |
| `SearchClient` | search-service | 无 | 搜索相关 |
| `AigcClient` | aigc-service | 有 | AIGC 相关内部接口 |
| `AuthClient` | auth-service | 无 | 认证内部接口（contextId=`auth1111`） |

> 说明：`AuthClient`、`ExamClient`、`SearchClient`、`CourseClient`、`CatalogueClient`、`CategoryClient`、`SubjectClient` 未配置 fallbackFactory，调用失败会直接抛异常；带 fallback 的客户端在提供方不可用时走 `FallbackFactory` 返回兜底结果。

## 4. 缓存组件

- **`CategoryCache`**（`@EnableCategoryCache` 开启）：课程三级分类的 Caffeine 本地缓存，提供 `getCategoryMap()`、`getCategoryNames(ids)`、`getNameByLv3Id()` 等；用于课程列表等高频展示场景，减少对 course-service 的远程调用。
- **`RoleCache`**：角色信息本地缓存（`RoleDTO`）。

## 5. DTO 约定

- **user**：`UserDTO`、`LoginFormDTO`
- **course**：`CourseDTO`、`CourseSimpleInfoDTO`、`CourseFullInfoDTO`、`CourseBaseInfoDTO`、`CoursePurchaseInfoDTO`、`CourseSearchDTO`、`CatalogueDTO`、`CataSimpleInfoDTO`、`CategoryDTO/BasicDTO`、`SubjectDTO`、`SectionInfoDTO`、`MediaQuoteDTO`、`SubNumAndCourseNumDTO`
- **trade**：`CartsAddDTO`、`OrderBasicDTO`、`OrderConfirmVO`
- **promotion**：`CouponDiscountDTO`、`OrderCouponDTO`、`OrderCourseDTO`
- **learning**：`LearningLessonDTO`、`LearningRecordDTO`、`LearningRecordFormDTO`
- **exam**：`QuestionDTO`、`QuestionBizDTO`
- **remark**：`LikedTimesDTO`
- **sms**：`SmsInfoDTO`
- **auth**：`RoleDTO`
- 通用：`IdAndNumDTO`

## 6. 依赖关系

- 依赖 tj-common（`R` 统一响应解码、异常体系）。
- **被依赖**：所有需要跨服务调用的业务服务。
- 外部依赖：spring-cloud-starter-openfeign、loadbalancer、sentinel、caffeine。

## 7. 注意事项

1. Feign 接口路径必须与提供方 Controller 路径严格一致，修改任一方的接口签名/路径都要同步两边（接口即契约）。
2. `contextId` 用于同一服务多个 Feign 客户端的区分（如 trade-service 有 `cart`/`trade` 两个），新增客户端若目标服务重复必须指定 `contextId`，否则启动报 Bean 冲突。
3. 响应解析依赖 tj-common 的 `R` 统一包装格式，不要单独调整提供方的响应包装行为。
4. `CategoryCache` 是本地缓存，分类数据变更后存在短暂不一致窗口，强一致场景请直查 `CategoryClient`。
