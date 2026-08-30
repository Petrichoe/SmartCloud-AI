# tj-exam

> 题库中心：课程题目（单选/多选/判断等）的管理与查询，题目与业务对象（课程/章节）的关联，供课程编辑与学习侧练习/考试使用。

## 1. 模块概述

- **模块职责**：题目的增删改查与分页；题目与业务（课程、章节等）关联（question_biz）；题目详情（选项、答案解析）维护；按教师/业务维度的统计查询。
- **服务名/端口**：`exam-service`，端口 **8089**。
- **网关路由前缀**：`/es/**`。
- **数据库**：MySQL `tj_exam`（表：question、question_detail、question_biz）。
- **外部依赖**：tj-course（题目主题 Subject 关联在课程侧维护）、tj-api 的 ExamClient 提供给课程/学习模块调用。

## 2. 模块结构

```
com.tianji.exam
├── controller   # QuestionController（题目管理）、QuestionBizController（题目-业务关联）
├── service(+impl) # IQuestionService、IQuestionDetailService、IQuestionBizService
├── domain       # po: Question、QuestionDetail、QuestionBiz
│                # dto: QuestionFormDTO；vo: QuestionPageVO、QuestionDetailVO；query: QuestionPageQuery
├── constants    # QuestionType（题型）、ExamErrorInfo
└── mapper
```

## 3. 对外接口

### QuestionController（`/questions`）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/questions` | 新增题目（含选项/答案） |
| PUT | `/questions/{id}` | 修改题目 |
| DELETE | `/questions/{id}` | 删除题目 |
| GET | `/questions/page` | 题目分页（按名称/类型/难度/方向筛选） |
| GET | `/questions/{id}` | 题目详情 |
| GET | `/questions/list` | 题目列表（内部，按 ids） |
| GET | `/questions/scores` | 题目分值（内部统计） |
| GET | `/questions/numOfTeacher` | 按教师统计题量 |
| GET | `/questions/listOfBiz` | 按业务 id 查题目列表（内部） |
| GET | `/questions/checkName` | 题干重复校验 |

### QuestionBizController（`/question-biz`）——内部接口

`POST list`（按业务集合查题目）、`GET /biz/{id}`、`GET /biz/list`、`GET /scores`：为 course/learning 提供题目-章节关联查询。

## 4. 核心业务逻辑

- **题型**：`QuestionType`（单选/多选/不定项/判断等，以枚举为准）。
- **题目主表 + 详情表**：`question` 存题干/题型/难度/分值，`question_detail` 存选项与答案解析（1:1）。
- **业务关联**：`question_biz` 把题目挂到业务对象（如课程小节），course 侧的 `Subject`（主题）与 `CourseCataSubject` 组织「课程-章节-题目」练习结构，练习作答入口在学习侧。
- 题目被 tj-course 的课程编辑（题目关联步骤）经 ExamClient 引用。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `Question` | `name`（题干）、`type`（题型）、`difficulty`、`score`、`direction`（方向）、`subQuestionId`（主题） |
| `QuestionDetail` | `questionId`、`options`（选项 JSON）、`answer`、`analysis`（解析） |
| `QuestionBiz` | `questionId`、`bizId`（业务对象 id）、`questionType` |

## 6. 配置说明

- 端口 8089；`tj.jdbc.database: tj_exam`；Nacos 共享配置 + 多环境。

## 7. 依赖关系

- 被依赖：tj-course（ExamClient 拉题目）、tj-learning（练习/考试场景）。
- 依赖：tj-common、tj-auth-resource-sdk。

## 8. 注意事项

1. 题目删除前需确认无课程/练习引用（`question_biz`），否则学习侧出现脏引用。
2. `/questions/listOfBiz`、`/question-biz/*` 为内部接口，注意与 tj-api 的 ExamClient 签名保持一致。
