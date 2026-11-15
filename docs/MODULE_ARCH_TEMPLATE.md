# 天机学堂单服务架构模板（以 tj-learning 为例）

> 本文以 `tj-learning`（learning-service，端口 8090）为蓝本，描述本仓库**单个业务微服务**的标准结构、分层规则、技术栈与代码约定。
> 用途：让 AI 照这份文档生成一个新系统/新服务时，产出的代码结构与本仓库一致，做到"打开任何一个包都知道东西在哪"。
> 文中所有示例均摘自本仓库真实代码，可直接作为生成模板。

---

## 1. 技术栈清单

| 类别 | 技术 | 版本/说明 |
|---|---|---|
| 语言 | Java | **17**（强制） |
| 基础框架 | Spring Boot | 3.3.5 |
| 微服务 | Spring Cloud | 2023.0.3 |
| 微服务组件 | Spring Cloud Alibaba | 2023.0.3.2（Nacos 注册中心 + 配置中心） |
| ORM | MyBatis-Plus | 3.5.9（`mybatis-plus-spring-boot3-starter`），配合 MySQL |
| 缓存 | Redis（StringRedisTemplate）+ Redisson（分布式锁 `@Lock`） | 本地缓存用 Caffeine |
| 消息队列 | RabbitMQ（spring-boot-starter-amqp） | 同步调用用 OpenFeign + LoadBalancer |
| 定时任务 | XXL-Job | `handler/` 包下 `@XxlJob` 方法 |
| 接口文档 | Knife4j（OpenAPI3 Jakarta） | 注解：`@Tag`/`@Operation`/`@Schema`/`@Parameter` |
| 鉴权 | 自研 `tj-auth-resource-sdk` | 网关解析 JWT → 服务内 ThreadLocal |
| 工具库 | Lombok、Hutool（少量）、自研 tj-common 工具集 | 优先用 tj-common 的工具 |

**代码生成铁律**：不用 MapStruct、不用 MyBatis Generator、不用 JPA；对象拷贝用 `BeanUtils`，CRUD 用 MyBatis-Plus。

---

## 2. 仓库总体布局（微服务级）

```
tjxt/                          # Maven 聚合父工程（统一管版本）
├── tj-common/                 # 公共模块：工具类、统一响应R、异常、MQ/MVC/Mybatis/Redisson自动装配
├── tj-api/                    # Feign客户端 + 跨服务DTO（服务间调用唯一入口）
├── tj-auth/                   # 认证服务 + 资源服务SDK（业务服务都依赖这个SDK）
├── tj-gateway/                # 网关：解析JWT，把userId塞进请求头
├── tj-learning/               # 业务服务（学习中心）← 本文档的蓝本
├── tj-user/ tj-course/ tj-trade/ tj-promotion/ tj-search/ tj-exam/ tj-aigc/ ...
└── startup.sh                 # Docker部署脚本
```

- 每个业务服务都是独立 Spring Boot 应用，命名 `{domain}-service`（如 `learning-service`），包名 `com.tianji.{domain}`。
- 服务间通信：**同步走 Feign（tj-api 中定义客户端），异步走 RabbitMQ**，外部请求统一经过网关。

---

## 3. 单服务目录树（tj-learning 实际结构）

```
tj-learning/
├── pom.xml                                # 继承父工程 tjxt；依赖见 §1
└── src/
    ├── main/
    │   ├── java/com/tianji/
    │   │   ├── LearningApplication.java   # ★ 启动类：放在 com.tianji（与 learning 平级）
    │   │   └── learning/
    │   │       ├── controller/            # Web层：只做参数接收与转发，零业务逻辑
    │   │       ├── service/               # 业务接口：I{Name}Service
    │   │       │   └── impl/              # 业务实现：{Name}ServiceImpl
    │   │       ├── mapper/                # DAO接口：extends BaseMapper<PO>
    │   │       ├── domain/                # 数据模型四件套
    │   │       │   ├── po/                # 表映射对象（@TableName），只对应一张表
    │   │       │   ├── dto/               # 请求体（前端→后端，@RequestBody 接收）
    │   │       │   ├── vo/                # 响应体（后端→前端）
    │   │       │   └── query/             # 查询参数（GET请求，extends PageQuery）
    │   │       ├── enums/                 # 业务枚举：implements BaseEnum
    │   │       ├── constants/             # 本服务常量（Redis key 前缀等，interface + 常量字段）
    │   │       ├── config/                # 本服务专属配置类（MybatisConfiguration 等）
    │   │       ├── handler/               # XXL-Job 定时任务（@XxlJob）
    │   │       ├── mq/                    # RabbitMQ 监听器 + mq/message/ 消息体 + mq/ 配置类
    │   │       └── utils/                 # "组件型"工具（@Component，有状态/需注入Bean）
    │   └── resources/
    │       ├── application.yml            # 端口、服务名、tj.* 自定义配置
    │       ├── application-local.yml      # local/dev/test 三套环境，Nacos地址 + shared-*.yaml 导入
    │       ├── application-dev.yml
    │       ├── application-test.yml
    │       ├── mapper/                    # MyBatis XML（与 mapper 接口同名，复杂SQL才写）
    │       └── sql/                       # 本服务用到的 DDL/变更脚本
    └── test/java/                         # 单元测试，包结构与 main 对齐
```

**启动类模板**（注意：启动类不在 `{domain}` 子包下，这样默认组件扫描覆盖 `com.tianji.**`）：

```java
@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("com.tianji.learning.mapper")   // 统一扫描mapper，mapper接口不用写@Mapper
public class LearningApplication {
    public static void main(String[] args) { SpringApplication.run(LearningApplication.class, args); }
}
```

---

## 4. 一次请求的完整链路

```
前端(tj-portal, Vue3)
   │ HTTP + Bearer Token
   ▼
tj-gateway(AccountAuthFilter)：校验JWT → 删除客户端伪造的头 → 写入 user-info: {userId}
   ▼
learning-service
   │ auth-sdk 的 UserInfoInterceptor：读请求头 user-info → UserContext.setUser(userId)（ThreadLocal，请求结束清理）
   ▼
Controller（转 VO/PageDTO，无逻辑）
   ▼
I{X}Service 接口 → {X}ServiceImpl（业务逻辑、事务、跨服务调用）
   ▼
Mapper（MyBatis-Plus）→ MySQL
   │
   ├─ 需要别的服务数据 → tj-api 的 Feign Client（FeignRelayUserInterceptor 自动透传 user-info 头）
   ├─ 要异步通知/削峰   → RabbitMqHelper.send(exchange, routingKey, message) → @RabbitListener 消费
   └─ 缓存/签到位/排行榜 → StringRedisTemplate（key 前缀定义在 constants/RedisConstants）

响应：Controller 返回的 VO 会被 WrapperResponseBodyAdvice 自动包成
      R{ code:200, msg:"OK", data:VO, requestId }（经网关的请求才包装；@NoWrapper 可跳过）
```

---

## 5. 分层规则与代码模板

### 5.1 Controller 层

- 命名 `{Name}Controller`；用户端与管理端拆成两个类（如 `InteractionQuestionController` / `InteractionQuestionAdminController`）。
- `@RestController + @RequestMapping("资源名") + @Tag(name=...) + @RequiredArgsConstructor`。
- **只做转发**：每个方法一行 `return service.xxx()`，参数校验注解（`@Valid`）、Swagger 注解是唯一允许的额外内容。
- 注入用 `private final` 字段 + Lombok 构造注入（全仓库统一，禁止 @Autowired 字段注入）。
- 返回值直接写 `VO` / `PageDTO<VO>` / `void`，**不要自己包 R**（统一响应由 tj-common 自动装配完成）。

```java
@RestController
@RequestMapping("/lessons")
@Tag(name = "我的课表相关接口")
@RequiredArgsConstructor
public class LearningLessonController {

    private final ILearningLessonService lessonService;

    @Operation(summary = "分页查询我的课表")
    @GetMapping("/page")
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {   // GET查询参数直接绑query对象
        return lessonService.queryMyLessons(query);
    }

    @Operation(summary = "创建学习计划")
    @PostMapping("/plans")
    public void createLearningPlans(@Valid @RequestBody LearningPlanDTO planDTO) {
        lessonService.createLearningPlan(planDTO.getCourseId(), planDTO.getFreq());
    }
}
```

### 5.2 Service 接口层

- 命名 `I{Name}Service`（极个别历史类如 `AIService` 未带 I，新代码一律带）。
- 一个业务领域一个接口，方法签名出入参只用 DTO/VO/Query/PO，不暴露 MyBatis-Plus 类型。

```java
public interface ISignRecordService {
    SignResultVO addSignRecords();
    Byte[] querySignRecords();
}
```

### 5.3 ServiceImpl 实现层（业务逻辑全在这里）

- 命名 `{Name}ServiceImpl`，注解 `@Service + @RequiredArgsConstructor`。
- **对应一张表的业务**必须继承 `ServiceImpl<Mapper, PO>`，直接使用 `lambdaQuery()`、`save()`、`getById()`、`updateById()`、`removeById()`、`saveBatch()`、`page()` 等父类方法，不再注入自己的 mapper。
- 多表/跨服务逻辑：注入其它 service 接口、其它 mapper、tj-api 的 Feign Client。
- 方法体结构约定：**步骤注释编号**（`// 1.xxx` `// 2.xxx`）+ 从 `UserContext.getUser()` 取当前用户。
- 对象转换：`BeanUtils.copyBean(src, Target.class)`（单个）、`BeanUtils.copyList(list, Target.class)`（批量）。
- 分页：`lambdaQuery()...page(query.toMpPageDefaultSortByCreateTimeDesc())` → `PageDTO.of(page, voList)`，空结果用 `PageDTO.empty(page)`。
- 参数不合法抛 `BadRequestException`；业务规则不满足抛 `BizIllegalException`；无权限抛 `ForbiddenException`（统一由 `CommonExceptionAdvice` 转成 R）。事务用 `@Transactional(rollbackFor = Exception.class)`。

```java
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl
        extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion>
        implements IInteractionQuestionService {

    private final InteractionReplyMapper replyMapper;   // 别的表 → 注入mapper
    private final UserClient userClient;                // 别的服务 → 注入Feign客户端

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        // 1.参数校验
        if (query.getCourseId() == null && query.getSectionId() == null) {
            throw new BadRequestException("课程id和小节id不能都为空");
        }
        // 2.分页查询（lambdaQuery 条件构造，第一个boolean参数=条件是否生效）
        Page<InteractionQuestion> page = lambdaQuery()
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, UserContext.getUser())
                .eq(query.getCourseId() != null, InteractionQuestion::getCourseId, query.getCourseId())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.批量查关联数据（Feign），转 Map 避免循环里远程调用
        Map<Long, UserDTO> userMap = ...;
        // 4.PO转VO逐个填充
        List<QuestionVO> voList = records.stream()
                .map(r -> BeanUtils.copyBean(r, QuestionVO.class)).collect(Collectors.toList());
        // 5.封装分页结果
        return PageDTO.of(page, voList);
    }
}
```

### 5.4 Mapper 层（DAO）

- 接口命名 `{Name}Mapper extends BaseMapper<PO>`，**不写 @Mapper 注解**（启动类已 `@MapperScan`）。
- 单表 CRUD 完全靠 BaseMapper，接口体留空；聚合/统计 SQL 用注解 `@Select` + `${ew.customSqlSegment}` 接收 QueryWrapper：

```java
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    @Select("SELECT SUM(points) FROM points_record ${ew.customSqlSegment}")
    Integer queryUserPointsByTypeAndDate(@Param(Constants.WRAPPER) QueryWrapper<PointsRecord> wrapper);
}
```

- 复杂 SQL / DDL 写 XML，放 `resources/mapper/{Name}Mapper.xml`，namespace 对应接口全名：

```xml
<mapper namespace="com.tianji.learning.mapper.LearningRecordMapper">
    <select id="countLearnedSections" resultType="com.tianji.api.dto.IdAndNumDTO">
        SELECT lesson_id AS id, COUNT(1) AS num
        FROM learning_record
        WHERE user_id = #{userId} AND finished = 1
        GROUP BY lesson_id;
    </select>
</mapper>
```

---

## 6. domain 四件套 + 枚举

### 6.1 PO（`domain/po/`，一表一类）

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("points_record")                 // 表名
public class PointsRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 积分记录表id */
    @TableId(value = "id", type = IdType.AUTO)   // 每个字段都有JavaDoc注释
    private Long id;

    /** 用户id */
    private Long userId;

    /** 积分方式：1-课程学习，2-每日签到...（数据库存int，代码用枚举） */
    private PointsRecordType type;

    private LocalDateTime createTime;
}
```

### 6.2 Query（`domain/query/`，GET 查询参数）

继承公共 `PageQuery`（自带 `pageNo/pageSize/sortBy/isAsc` 和 `toMpPage*()` 系列方法）：

```java
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "互动问题分页查询条件")
public class QuestionPageQuery extends PageQuery {
    @Schema(description = "课程id")
    private Long courseId;
    @Schema(description = "是否只查询我的问题", example = "1")
    private Boolean onlyMine = false;
}
```

### 6.3 VO（`domain/vo/`，响应体）与 DTO（`domain/dto/`，请求体）

- 都是 `@Data + @Schema(每个字段)`；DTO 上用 `jakarta.validation` 注解（`@NotNull` 等），Controller 方法参数加 `@Valid`。
- 命名：表单类 `XxFormDTO`；管理端列表 `XxAdminVO`；结果类 `XxResultVO`。

### 6.4 枚举（`enums/`）

数据库存 int、JSON 输出 int、前端传 int 反序列化回枚举，统一实现 `BaseEnum`：

```java
@Getter
public enum LessonStatus implements BaseEnum {
    NOT_BEGIN(0, "未学习"), LEARNING(1, "学习中"), FINISHED(2, "已学完"), EXPIRED(3, "已过期"),
    ;
    @JsonValue      // Jackson序列化输出value
    @EnumValue      // MyBatis-Plus存取数据库用value
    int value;
    String desc;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)   // 前端传数字反序列化为枚举
    public static LessonStatus of(Integer value) { ... }
}
```

---

## 7. 工具、常量、配置、任务、消息的存放规则（重点）

| 要存的东西 | 放哪 | 规则 |
|---|---|---|
| **通用静态工具类**（纯函数，无状态） | `tj-common` 的 `com.tianji.common.utils` | 全仓库共享，服务内**不重复造**。已有：`UserContext`、`BeanUtils`、`CollUtils`、`DateUtils`、`JsonUtils`、`StringUtils`、`NumberUtils`、`BooleanUtils`、`TreeDataUtils`、`RandomUtils`、`SqlWrapperUtils` 等 |
| **组件型工具**（需要注入 Bean / 有状态 / 有生命周期） | 本服务 `utils/` 包，`@Component` | 例：`LearningRecordDelayTaskHandler`（DelayQueue 延迟任务处理器，`@PostConstruct` 启动线程、`@PreDestroy` 停止）、`TableInfoContext`（ThreadLocal 存动态表名）；纯数据类如 `DelayTask` 也可放这里 |
| Redis key 前缀 | 本服务 `constants/RedisConstants`（interface + String 常量） | 格式 `业务:对象:限定`，如 `sign:uid:`、`boards:`；注释写清完整 key 示例 |
| 其它业务常量 | `constants/LearningConstants` | 同上风格 |
| 全局共用常量（MQ 交换机/路由键等） | `tj-common` 的 `MqConstants`/`Constant`/`RegexConstants` | 所有服务引用同一份 |
| 配置类 | 本服务 `config/`（`@Configuration`） | 只放本服务特有的 Bean（如 `MybatisConfiguration` 的动态表名拦截器）；通用自动装配（Mybatis、MQ、MVC、Redisson、Swagger、XXL-Job）全部由 tj-common 的 `autoconfigure` 包提供，服务零配置 |
| 定时任务 | 本服务 `handler/`，`@Component + @XxlJob("任务名")` | 例：`PointsBoardPersistentHandler` |
| MQ 消费者 | 本服务 `mq/`，`@Component + @RabbitListener` | 队列/交换机绑定内联写在注解里 |
| MQ 消息体 | `mq/message/` | 消息类含 `of(...)` 静态工厂与 `buildEventId(...)` 等方法 |
| MQ 发送配置/容器工厂 | `mq/` 下的 `XxRabbitMqConfig` | 如 `SignInRabbitMqConfig` |
| 跨服务 DTO | `tj-api` 的 `com.tianji.api.dto.{domain}` | 服务自己的 DTO/VO 绝不跨服务共享 |
| DDL/SQL 脚本 | `resources/sql/` | 如 `points_mq_idempotency.sql` |

**tj-common 自动装配清单**（业务服务 pom 引入 tj-common 相关模块后自动生效，不需要写代码）：

- `autoconfigure/mvc`：统一响应包装 `WrapperResponseBodyAdvice`、全局异常 `CommonExceptionAdvice`、Jackson 配置、参数校验
- `autoconfigure/mybatis`：MyBatis-Plus 分页插件、`createTime/updateTime` 自动填充（`MyBatisAutoFillInterceptor`）
- `autoconfigure/mq`：`RabbitMqHelper`（发送消息 + 重试/确认封装）
- `autoconfigure/redisson`：`@Lock` 注解 + `LockAspect`（可指定 `LockStrategy`）
- `autoconfigure/swagger`：Knife4j 分组与包装展示（`application.yml` 的 `tj.swagger.*` 控制）
- `autoconfigure/xxljob`：执行器配置（`XxlJobConfig`）

---

## 8. 跨服务调用（tj-api 规范）

```
tj-api/src/main/java/com/tianji/api/
├── client/{domain}/XxClient.java          # @FeignClient(value = "目标服务名", fallbackFactory = ...)
│   └── fallback/XxClientFallback.java     # 降级实现，记录日志并返回null/空集合
├── dto/{domain}/XxDTO.java                # 跨服务传输对象（服务内部DTO/VO绝不外泄）
├── cache/                                 # CategoryCache、RoleCache：带Caffeine缓存的远程数据封装
└── config/                                # Feign相关配置（FallbackConfig、RequestIdRelayConfiguration）
```

```java
@FeignClient(value = "user-service", fallbackFactory = UserClientFallback.class)
public interface UserClient {
    @GetMapping("/users/list")
    List<UserDTO> queryUserByIds(@RequestParam("ids") Iterable<Long> ids);
}
```

- 调用方在 ServiceImpl 里直接注入 Client；**必须在拿到结果后判空**（降级时返回 null）。
- 批量数据查询先收集 id → 一次远程调用 → 转 Map 填充，禁止循环里调 Feign。

---

## 9. 异步消息（RabbitMQ）规范

**发送方**（如签到业务发积分消息）：

```java
mqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE, MqConstants.Key.SIGN_IN,
        SignInMessage.of(userId, rewardPoints, now));   // 交换机/路由键常量在tj-common的MqConstants
```

**消费方**（`mq/` 包）：队列在注解里声明绑定，消费者只做"校验 + 调 service"：

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class LearningPointsListener {

    private final IPointsRecordService recordService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "sign.points.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignInMessage(SignInMessage message) {
        // 1.校验消息完整性，不合法抛异常让MQ重试/进死信
        // 2.调service落库
        // 3.幂等：捕获DuplicateKeyException → 查消费记录确认已处理才吞掉，否则重抛
    }
}
```

**幂等消费**（项目已验证的实现）：消费记录表（`mq_consume_record`，`consumer_name + business_key` 唯一键）与业务数据在**同一个 MySQL 事务**里写入，配合 Redis Lua（SISMEMBER + ZINCRBY + SADD 原子执行）防重；消息体携带 `eventId = md5(userId + 业务键)` 防伪造。新增异步积分/记账类消费照此模式。

---

## 10. Redis 使用规范

- 统一用 `StringRedisTemplate`；key 前缀在 `constants/RedisConstants` 定义，value 用 `JsonUtils.toJsonStr` 存 JSON 字符串。
- 典型用法（本项目实例）：
  - **Bitmap 签到**：`setBit(key, dayOfMonth-1, true)` + `bitField` 取位图回推连续签到天数（`SignRecordServiceImpl`）；
  - **ZSet 排行榜**：`opsForZSet().incrementScore("boards:yyyyMM", userId, points)`（`PointsRecordServiceImpl`）；
  - **Lua 脚本幂等**：`DefaultRedisScript` + `redisTemplate.execute(script, keys, args)`；
  - **Hash 缓存播放进度**：`opsForHash()` + `expire` 短过期（`LearningRecordDelayTaskHandler`）。
- 分布式锁用 tj-common 的 `@Lock` 注解（Redisson 实现），不手写锁模板。

---

## 11. 定时任务与动态表名（handler/ + config/ + utils/ 联动）

以月度榜单归档为例（可整套复制到新系统）：

1. **任务定义** `handler/PointsBoardPersistentHandler`：`@XxlJob("savePointsBoard2DB")`，分片参数 `XxlJobHelper.getShardIndex()/getShardTotal()` 实现多执行器分页扫描；
2. **动态表名**：任务里 `TableInfoContext.setInfo("points_board_202401")`（ThreadLocal）→ `config/MybatisConfiguration` 注册 `DynamicTableNameInnerInterceptor`，遇到逻辑表 `points_board` 就替换成 ThreadLocal 里的真实表名；
3. **建表**：`resources/mapper/PointsBoardMapper.xml` 里写 `CREATE TABLE ${tableName}` 的 DDL，由 `@XxlJob("createTableJob")` 触发。

---

## 12. 配置文件结构

`application.yml`（不随环境变的）：

```yaml
server:
  port: 8090
spring:
  profiles:
    active: local
  application:
    name: learning-service           # 也是Nacos服务名、Feign里的服务名
tj:
  swagger: { enable: true, enableResponseWrap: true, package-path: com.tianji.learning.controller, title: ... }
  jdbc:
    database: tj_learning            # 数据库名，由tj-common拼装数据源
```

`application-{profile}.yml`（local/dev/test 三套，随环境变的）：

```yaml
spring:
  cloud:
    nacos:
      server-addr: 192.168.150.101:8848
  config:
    import:                          # 共享配置全部托管在Nacos
      - nacos:${spring.application.name}.yaml
      - nacos:shared-spring.yaml
      - nacos:shared-redis.yaml
      - nacos:shared-mybatis.yaml
      - nacos:shared-mq.yaml
      - nacos:shared-xxljob.yaml
```

生成新服务时：复制这两层结构，改 `port` / `application.name` / `tj.jdbc.database` / `tj.swagger.package-path` 四处即可。

---

## 13. 命名与风格速查表

| 对象 | 规则 | 示例 |
|---|---|---|
| 启动类 | `{Domain}Application`，包 `com.tianji` | `LearningApplication` |
| Controller | `{Name}Controller` | `LearningLessonController` |
| Service 接口 | `I{Name}Service` | `ILearningLessonService` |
| Service 实现 | `{Name}ServiceImpl` | `LearningLessonServiceImpl` |
| Mapper | `{Name}Mapper`（接口）/ XML 同名 | `PointsRecordMapper` |
| PO | 表名驼峰，`@TableName` 对应表 | `PointsRecord` ↔ `points_record` |
| 查询对象 | `{Name}Query extends PageQuery` | `QuestionPageQuery` |
| 请求体 | `{Name}DTO` / `{Name}FormDTO` | `LearningPlanDTO` |
| 响应体 | `{Name}VO` | `LearningLessonVO` |
| MQ 监听器 | `{Xx}Listener` | `LearningPointsListener` |
| 定时任务类 | `{Xx}Handler` | `PointsBoardPersistentHandler` |
| 常量接口 | `{Xx}Constants` | `RedisConstants` |
| 依赖注入 | `private final` + `@RequiredArgsConstructor` | 全仓库统一 |
| 注释 | 类上 JavaDoc + 方法体 `// 1.` 步骤注释 | 见 §5.3 |
| API 风格 | RESTful：GET 查询 / POST 新增 / PUT、DELETE 按 id；管理端路径加 `/admin` 前缀（独立 Controller） | `GET /lessons/page` |

---

## 14. 附：给 AI 的"新系统生成"指令模板

> 用法：把下面这段连同第 1~13 章一起发给 AI，替换尖括号内容即可。

```
请按照《天机学堂单服务架构模板》（MODULE_ARCH_TEMPLATE.md）为我生成一个 <系统名称> 微服务，要求：

1. 技术栈：Java 17 + Spring Boot 3.3.5 + Spring Cloud Alibaba（Nacos）+ MyBatis-Plus + MySQL
   + Redis/Redisson + RabbitMQ + XXL-Job + Knife4j + Lombok，与模板完全一致。
2. 工程结构：严格按模板第3章目录树创建 <artifactId>，包名 com.tianji.<domain>；
   启动类 <Domain>Application 放 com.tianji 包，@MapperScan("com.tianji.<domain>.mapper")。
3. 分层：Controller（只转发、@RequiredArgsConstructor、返回VO/PageDTO、Swagger注解齐全）
   → I{X}Service 接口 → {X}ServiceImpl（继承 ServiceImpl<Mapper,PO>，lambdaQuery 构建
   查询，BeanUtils 转换，PageDTO.of 封装分页，步骤编号注释）→ Mapper（BaseMapper，
   统计SQL用@Select+${ew.customSqlSegment}，复杂SQL写XML）。
4. domain 四件套：po（@Data+@Accessors(chain=true)+@TableName+字段注释）、
   query（extends PageQuery）、dto（@Schema+校验注解）、vo（@Schema）；
   枚举 implements BaseEnum（@JsonValue/@EnumValue/of()）。
5. 当前用户一律 UserContext.getUser() 获取；错误用 BadRequestException/BizIllegalException/
   ForbiddenException 表达，不自己定义异常类；不要手工包装 R 响应。
6. 工具类：通用静态工具直接引用 com.tianji.common.utils 已有工具，不要重复实现；
   需要注入Bean的工具放 <domain>/utils 并注册为 @Component。
7. Redis key 前缀统一定义在 constants/RedisConstants（interface 常量）。
8. 需要异步/跨服务的地方：Feign 客户端按模板第8章声明（含 fallbackFactory），
   MQ 按模板第9章（@RabbitListener + QueueBinding 内联声明，消息体放 mq/message）。
9. 配置：application.yml + application-local.yml 两层结构，按模板第12章占位。
10. 生成顺序：建表SQL(resources/sql/) → PO → Mapper(+XML) → Service接口 → ServiceImpl
    → DTO/VO/Query → Controller → 枚举/常量。
业务需求：<在这里描述表结构、接口清单、业务规则>
```

---

*本文基于仓库实际代码整理（分支 dev），示例文件路径均可直接检索验证。*
