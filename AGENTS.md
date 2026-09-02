# AGENTS.md

> This file provides guidance to AI coding agents (ZCode, Codex, etc.) when working with code in this repository.

## 项目速览

天机学堂（TJXT）是基于 Spring Cloud 的微服务在线教育平台，使用 Java 17 和 Spring Boot 3.3.5。

- **基础模块**：`tj-common`、`tj-api`、`tj-gateway`
- **认证授权**：`tj-auth`（认证服务、资源服务 SDK、网关 SDK）
- **业务服务**：用户、课程、学习、考试、交易、支付、营销、搜索、媒体、数据、评论、消息、AIGC
- **服务通信**：Feign 用于同步调用，RabbitMQ 用于异步消息，外部请求统一经过网关
- **详细说明**：见 [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md)
- **模块文档**：见 [`docs/README.md`](docs/README.md)

## 构建和运行

**本地开发**

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

## 前端项目（tj-portal-src）

前端用户端代码不在本仓库目录内，位于：

`D:\computer technology\code\Project-me\tianji\tj-portal-src`

涉及用户端页面、组件、路由、Pinia 状态、API 请求、Vite 配置、前端构建或联调时，先到上述目录阅读相关代码。前端项目使用 Vue 3 + Vite，启动命令以该目录下的 `package.json` 为准。

**本地启动（Windows PowerShell）**

```powershell
cd "D:\computer technology\code\Project-me\tianji\tj-portal-src"

# 首次启动或依赖变更后安装依赖
npm install

# 本地开发，默认监听 0.0.0.0:18082，并自动打开浏览器
npm run dev
```

也可以在本仓库根目录双击或执行 `start-frontend.bat` 一键启动前端。该脚本会打开可见的命令行窗口，自动检查并安装前端依赖，然后执行 `npm run dev`；按 `Ctrl+C` 或关闭该窗口即可停止前端。

启动后访问 `http://localhost:18082`。当前 `vite.config.js` 中的代理目标为：

- `/api` -> `http://api.tianji.com`
- `/img-tx` -> `http://www.tianji.com`
- `/mock/3359` -> `http://172.17.0.137:8321/mock/3359`

其他常用脚本：

```powershell
# mock 模式（package.json 的实际定义）
npm run start

# 测试、产品模式启动
npm run dev:test
npm run pro

# 构建和预览
npm run build
npm run build:test
npm run preview
```

注意：前端 README 中把 `dev` 和 `start` 的 mock/测试说明写反了；以 `package.json` 为准，其中 `npm run dev` 是 `development` 模式，`npm run start` 是 `mock` 模式。`vite.config.js` 当前没有启用 `loadEnv`，代理地址是固定配置，`--mode` 不会自动切换后端地址。README 记录的初始开发环境为 Node.js `v17.8.0`、npm `8.5.5` 或 pnpm `6.32.8`。

**Docker 部署**

项目包含 `startup.sh` 脚本用于 Docker 部署：

```bash
./startup.sh -c container_name -n project_name -d project_path -p port -o "java_opts" -a debug_port
```

参数：`-c` 容器名称，`-n` 项目名称（jar 文件名），`-d` 项目路径（相对于 `/usr/local/src/tianji/tjxt`），`-p` 应用端口，`-o` JVM 参数（可选），`-a` 调试端口（0 表示普通模式）。

## 开发约定

- 必须使用 Java 17。
- 本仓库所有代码、配置和脚本文件均为 UTF-8 编码；在 Windows/PowerShell 下读取中文文件必须显式使用 UTF-8（如 `Get-Content -Encoding UTF8`），不能按 GBK/系统默认编码判断文件内容是否乱码。
- 发现中文显示为乱码时，先区分“终端显示编码问题”和“文件字节已损坏”：必要时用 UTF-8 读取或字节检查确认；未经确认，不做转码修复。
- 主分支为 `stu`，创建 PR 时以 `stu` 为目标分支。
- `course-service` 和 `aigc-service` 允许循环引用（`allow-circular-references: true`）。
- 容器镜像使用阿里云镜像仓库：`registry.cn-beijing.aliyuncs.com/itcast/openjdk:17-jdk-eclipse-temurin`。
- 服务端口和详细技术约定见 [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md)。

## 本地 VMware 虚拟机操作约定

涉及当前本地 VMware 虚拟机的 SSH、网络、Nginx、Docker、前端部署或网关排查时，先阅读 [`docs/VMWARE_LOCAL_ACCESS.md`](docs/VMWARE_LOCAL_ACCESS.md)，再执行实时检查和操作。该文档是本地虚拟机环境的详细索引和操作手册。

- 默认使用 SSH，不使用 Telnet；专用私钥为 `C:\Users\31241\.ssh\tjxt_vm_ed25519`。
- 不得读取、输出或提交私钥内容，不得把密码、令牌等凭据写入项目文件、日志或命令参数。
- 删除、覆盖、重置、停服、修改网络/防火墙、重启服务等不可逆操作，必须先获得明确确认。

## 详细文档索引

修改敏感区域前，先阅读对应设计文档：

- [项目总体介绍](docs/PROJECT_OVERVIEW.md)
- [模块文档索引](docs/README.md)
- AIGC 对话存储改造：`tj-aigc/src/main/resources/doc/README_CHAT_REFACTOR.md`
- 优惠券异步领券：`tj-promotion/src/main/resources/doc/COUPON_ASYNC_RECEIVE.md`
- 优惠券 Lua 优化：`tj-promotion/src/main/resources/doc/COUPON_LUA_OPTIMIZATION.md`
- 兑换码安全重构：`tj-promotion/src/main/resources/doc/exchange-code-security-refactoring.md`
