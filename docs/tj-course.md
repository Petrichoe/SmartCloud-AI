# tj-course

> 课程中心：课程（含草稿/上架流程）、三级分类、课程目录（章节/小节）、题目主题、教师等课程域核心数据的管理与查询，是全平台内容侧的基础服务。

## 1. 模块概述

- **模块职责**：课程 CRUD 与分步编辑（草稿态→上架态双表设计）、三级课程分类管理、课程目录与题目关联、教师管理；课程上架/下架/删除/过期时通过 MQ 通知搜索等服务同步索引；对外提供大量内部查询接口（课程信息、目录、分类统计）。
- **服务名/端口**：`course-service`，端口 **8086**。
- **网关路由前缀**：`/cs/**`。
- **数据库**：MySQL `tj_course`。
- **外部依赖**：Redis（统计缓存）、RabbitMQ（`course.topic` 交换机）、XXL-Job（课程结课）。

## 2. 模块结构

```
com.tianji.course
├── controller   # CourseController(管理端编辑) / CourseInfoController(查询) / CategoryController / CatalogueController
├── service(+impl) # ICourseService、ICourseDraftService、ICategoryService、ICatalogueService 等
├── domain.po    # Course/CourseDraft、CourseCatalogue(+Draft)、CourseContent(+Draft)、
│                #   CourseTeacher(+Draft)、CourseSubject、CourseCataSubject(+Draft)、
│                #   Category、Subject、SubjectCategory、SubjectUseNum
├── handler      # CourseJobHandler（XXL-Job: courseFinished）
├── config       # ThreadPoolConfig
├── properties   # CourseProperties
├── constants    # RedisContants 等
└── utils        # SubjectUtils、CategoryDataWrapper、CourseSaveBaseGroup（分组校验）
```

特点：**草稿双表设计**——每个核心实体都有对应的 `*Draft` 表，编辑操作先写 Draft，上架时校验并落正式表。

## 3. 对外接口

### CourseController（`/courses`）——管理端课程编辑

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/courses/baseInfo/{id}` | 分步编辑-查询课程基础信息 |
| POST | `/courses/baseInfo/save` | 保存第 1 步（基础信息） |
| GET | `/courses/catas/{id}` | 查询目录/主题步骤数据 |
| POST | `/courses/catas/save/{id}/{step}` | 保存目录/主题步骤 |
| POST | `/courses/media/save/{id}` | 保存视频步骤 |
| POST/GET | `/courses/subjects/save/{id}`、`/courses/subjects/get/{id}` | 题目关联保存/查询 |
| GET/POST | `/courses/teachers/{id}`、`/courses/teachers/save` | 课程教师查询/保存 |
| POST | `/courses/upShelf` | **上架**（校验通过后落正式表） |
| GET | `/courses/checkBeforeUpShelf/{id}` | 上架前校验 |
| POST | `/courses/downShelf` | 下架（发 MQ `course.down`） |
| DELETE | `/courses/delete/{id}` | 删除课程（发 MQ `course.delete`） |
| GET | `/courses/simpleInfo/list` | 课程简单信息列表（内部） |
| GET | `/courses/catas/index/list/{id}` | 目录索引（内部） |
| GET | `/courses/page` | 课程分页（管理端） |
| GET | `/courses/checkName` | 课程名重复校验 |
| GET | `/courses/{id}/catalogs` | 课程目录查询 |

### CourseInfoController（`/course`）——查询（内部/前台）

`/course/infoByTeacherIds`（按教师查课程）、`/course/section/{id}`（小节详情）、`/course/media/useInfo`（媒资引用信息）、`/course/{id}/searchInfo`（搜索用课程信息）、`/course/{id}`（课程完整详情）、`/course/getCateNameMap`、`/course/name` 等。

### CategoryController（`/categorys`）——三级分类

`GET list`（树）、`GET {id}`、`POST add`、`DELETE {id}`、`PUT disableOrEnable`、`PUT update`、`GET all`、`GET getAllOfOneLevel`。

### CatalogueController（`/catalogues`）

`GET batchQuery`（批量查章节小节）、`GET querySectionInfoById/{id}`。

## 4. 核心业务逻辑

### 4.1 课程编辑与上架（`CourseDraftServiceImpl`）

- 分步保存：`save`（基础信息）→ `updateStep`（推进步骤）→ 各步骤保存草稿表（Course/Catalogue/Content/Teacher/Subject 各自的 Draft）。
- `upShelf(id)`：`checkBeforeUpShelf` 校验必填步骤完整性 → 将 Draft 数据复制到正式表 → **异步发送 MQ**：`course.topic` + `course.up`（tj-search 消费同步索引）。
- `downShelf`：同步发送 `course.down`；`delete`：发送 `course.delete`（`CourseServiceImpl`）。
- `CourseJobHandler.courseFinished`（XXL-Job 任务 `courseFinished`）：周期扫描到期课程做结课处理；过期场景会发送 `course.expire` 消息（异步）。

### 4.2 分类缓存

- 统计类查询使用 Spring Cache（`@Cacheable`）写 Redis：`COURSE:COURSE_NUM_CATEGORY`（分类课程数统计）、`COURSE:CATEGORY_ID_WITH_COURSE`（有课程的分类 id 列表）。
- 三级分类的本地缓存复用 tj-api 的 `CategoryCache`（Caffeine，`@EnableCategoryCache` 开启），分类名拼接等高频展示不重复远程调用。
- `RedisContants` 还定义了分类三级数量 key `CATEGORY:THIRD_NUMBER` 等。

### 4.3 题目/主题（Subject）

- `Subject`/`SubjectCategory` 维护题目主题与三级分类的关联；`SubjectUtils` 做主题相关校验；`SubjectUseNum` 记录主题被引用次数。

## 5. 数据模型

| 实体 | 说明 |
|---|---|
| `Course` / `CourseDraft` | 课程主表/草稿：名称、一二三级分类、价格、封面、有效期、步骤状态等 |
| `CourseCatalogue`(+Draft) | 章节（第几章）/小节（第几节）树，含小节考试/练习标识 |
| `CourseContent`(+Draft) | 章节图文内容 |
| `CourseTeacher`(+Draft) | 课程-教师关联 |
| `CourseSubject` / `CourseCataSubject`(+Draft) | 课程-题目主题、目录-题目主题关联 |
| `Category` | 三级分类（树，parent_id） |
| `Subject` / `SubjectCategory` | 题目主题及其与分类关联 |

## 6. 配置说明

- 端口 8086；`tj.jdbc.database: tj_course`；允许循环引用（`allow-circular-references: true`）。
- Nacos 共享配置 + 多环境 yml；`CourseProperties` 承载课程模块自定义配置。

## 7. 依赖关系

- 依赖：tj-common、tj-api（UserClient 查教师/用户信息、ExamClient 查题目）、media-service（媒资引用校验，经 MediaQuoteDTO）。
- 被依赖：tj-search（课程索引同步数据源）、tj-learning、tj-trade、tj-remark、tj-aigc（课程查询）等——**课程域的单一数据源**。
- MQ：发布 `course.up/down/expire/delete` 到 `course.topic`；消费方主要是 tj-search。

## 8. 注意事项

1. **双表草稿机制**：任何课程字段变更若只改正式表会出现"编辑丢失"；编辑必须走 Draft 接口，上架才落正式表。
2. 上架是异步发 MQ，搜索侧索引更新有短暂延迟；排查"搜索不到新课程"先查 `course.topic` 的 `course.up` 消息。
3. 分类树操作（禁用/删除）要评估下游（learning/trade/course 列表页）对分类缓存的依赖，必要时清理 `COURSE:CATEGORY_*` 缓存。
4. `CourseController` 大量接口为内部 Feign 提供（simpleInfo/list、catas/index 等），改动需检查 tj-api 中对应 client 定义。
