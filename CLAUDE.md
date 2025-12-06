# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

天机学堂(TJXT)是一个基于 Spring Cloud 的微服务架构在线教育平台，使用 Java 17 和 Spring Boot 3.3.5 开发。

## 技术栈

- **核心框架**: Spring Boot 3.3.5, Spring Cloud 2023.0.3, Spring Cloud Alibaba 2023.0.3.2
- **数据库**: MySQL 8.0.23, MyBatis Plus 3.5.9
- **缓存**: Redisson 3.13.6
- **搜索**: Elasticsearch 7.12.1
- **消息队列**: Spring AMQP (RabbitMQ)
- **任务调度**: XXL-Job 2.3.1
- **文档**: Knife4j 4.5.0 (OpenAPI 3)
- **工具库**: Hutool 5.8.36
- **云服务**: 阿里云 OSS、支付宝 SDK、腾讯云 SDK

## 构建和运行命令

### 本地开发

```bash
# 编译整个项目
mvn clean package -DskipTests

# 编译单个服务（例如 course 服务）
cd tj-course
mvn clean package -DskipTests

# 运行单个服务（使用 local 环境配置）
cd tj-course
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=YourTestClass

# 运行单个测试方法
mvn test -Dtest=YourTestClass#testMethod
```

### Docker 部署

项目包含 startup.sh 脚本用于 Docker 部署：

```bash
# 使用示例（通常在 CI/CD 环境）
./startup.sh -c container_name -n project_name -d project_path -p port -o "java_opts" -a debug_port
```

参数说明：
- `-c`: 容器名称
- `-n`: 项目名称（jar 文件名）
- `-d`: 项目路径（相对于 /usr/local/src/tianji/tjxt）
- `-p`: 应用端口
- `-o`: JVM 参数（可选）
- `-a`: 调试端口（0 表示普通模式）

## 项目架构

### 模块结构

项目采用多模块 Maven 结构，分为以下主要模块：

#### 1. 基础模块

- **tj-common**: 公共模块，包含：
  - `annotations`: 自定义注解
  - `autoconfigure`: 自动配置类
  - `constants`: 常量定义
  - `domain`: 通用领域对象（如分页、响应包装类）
  - `enums`: 枚举类
  - `exceptions`: 异常处理
  - `filters`: 过滤器
  - `utils`: 工具类
  - `validate`: 校验相关

- **tj-api**: API 接口模块，包含：
  - `client`: 各服务的 Feign 客户端（跨服务调用）
  - `dto`: 数据传输对象
  - `constants`: API 常量
  - `cache`: 缓存相关
  使用 Spring Cloud OpenFeign 实现服务间调用，集成 Sentinel 做熔断降级

- **tj-gateway**: 网关服务（端口 10010）
  - 使用 Spring Cloud Gateway
  - 路由配置：各服务通过路径前缀区分（/us/**, /cs/**, /ls/** 等）
  - 集成全局 CORS 配置
  - StripPrefix=1 过滤器去除路径前缀

#### 2. 认证授权模块（tj-auth）

多子模块结构：
- **tj-auth-common**: 认证公共组件
- **tj-auth-service**: 认证服务（独立部署）
- **tj-auth-resource-sdk**: 资源服务器 SDK（被其他服务依赖）
- **tj-auth-gateway-sdk**: 网关认证 SDK（被网关依赖）

#### 3. 业务服务模块

每个服务都是独立的微服务，通过网关路由访问：

- **tj-user** (us): 用户服务
- **tj-course** (cs): 课程服务（端口 8086）
- **tj-learning** (ls): 学习服务
- **tj-exam** (es): 考试服务
- **tj-trade** (ts): 交易服务
- **tj-pay** (ps): 支付服务
- **tj-promotion** (prs): 营销服务
- **tj-search** (ss): 搜索服务
- **tj-media** (ms): 媒体服务
- **tj-data** (ds): 数据服务
- **tj-remark** (rs): 评论服务
- **tj-message** (sms): 消息服务

部分服务采用 DDD 分层结构（如 tj-message、tj-pay）：
- `*-domain`: 领域层（实体、值对象）
- `*-api`: API 接口定义
- `*-service`: 服务实现层

### 服务间通信

1. **同步调用**: 使用 Feign Client（定义在 tj-api 模块）
   - 例如：`UserClient`, `CourseClient`, `TradeClient` 等
   - Feign 客户端按业务域组织在 `tj-api/client` 目录下

2. **异步消息**: 使用 RabbitMQ（Spring AMQP）

3. **网关路由**: 所有外部请求通过 tj-gateway 统一入口

### 配置管理

- 每个服务有多环境配置：
  - `application.yml`: 主配置
  - `application-local.yml`: 本地开发
  - `application-dev.yml`: 开发环境
  - `application-test.yml`: 测试环境
- 使用 Spring Cloud Bootstrap 加载配置
- 通过 `spring.profiles.active` 切换环境

### 数据库设计

- 每个服务有独立数据库（通过 `tj.jdbc.database` 配置）
- 例如：course-service 使用 `tj_course` 数据库
- 使用 MyBatis Plus 作为 ORM 框架

## 开发约定

### 服务端口分配

- tj-gateway: 10010
- tj-course: 8086
- 其他服务端口参考各自 application.yml

### API 文档

- 使用 Knife4j（Swagger 3）
- 配置在各服务的 `application.yml` 中：
  ```yaml
  tj:
    swagger:
      enable: true
      package-path: com.tianji.xxx.controller
      title: 服务标题
  ```
- 访问地址：`http://localhost:{port}/doc.html`

### 认证与鉴权

- 使用自定义认证框架（tj-auth）
- 通过 SDK 方式集成：
  - 网关集成 `tj-auth-gateway-sdk`
  - 资源服务集成 `tj-auth-resource-sdk`
- 配置项：`tj.auth.resource.enable`

### 代码分层

典型 Controller 层路径：`src/main/java/com/tianji/{service}/controller`

### 常见依赖

- 所有服务都依赖 `tj-common` 获取公共工具
- 需要跨服务调用时依赖 `tj-api` 中的 Feign Client
- Lombok 用于减少样板代码
- 使用 Jakarta EE 9+ 规范（jakarta.servlet）

## 注意事项

1. **Java 版本**: 必须使用 Java 17
2. **编码**: 统一使用 UTF-8
3. **循环依赖**: course-service 允许循环引用（`allow-circular-references: true`）
4. **Git 分支**: 主分支为 `stu`，创建 PR 时应以 `stu` 为目标分支
5. **容器化**: 使用阿里云镜像仓库（registry.cn-beijing.aliyuncs.com/itcast/openjdk:17-jdk-eclipse-temurin）
