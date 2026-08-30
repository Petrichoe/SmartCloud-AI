# tj-auth

> 认证授权体系：一套自研 JWT 认证框架，包含认证服务（登录/JWKS/菜单权限管理）与两个 SDK（资源服务侧、网关侧），全服务的登录态与权限都由它支撑。

## 1. 模块概述

- **模块职责**：账号密码登录、JWT（access-token + refresh-token）签发与刷新、登录记录、权限/角色/菜单管理；并分别以 SDK 形式为「网关」和「各业务资源服务」提供鉴权能力。
- **子模块**：
  | 子模块 | 形态 | 职责 |
  |---|---|---|
  | `tj-auth-common` | 公共库 | `JwtConstants`、`AuthErrorInfo`、`PrivilegeRoleDTO` |
  | `tj-auth-service` | 独立微服务 | 登录、token 管理、权限体系管理（端口 8081） |
  | `tj-auth-resource-sdk` | SDK | 被各业务服务依赖：登录拦截、用户上下文、Feign 透传 |
  | `tj-auth-gateway-sdk` | SDK | 被网关依赖：`AuthUtil` 解析 token 并校验权限 |
- **服务名/端口**：`auth-service`，**8081**；网关路由前缀 `/as/**`。
- **数据库**：账号、角色、权限、菜单、登录记录表（库名由 Nacos `shared-mybatis.yaml` 统一配置）。
- **外部依赖**：Redis（JTI 缓存、权限缓存）、Nacos、UserClient（查用户详情）。

## 2. tj-auth-service（认证服务）

### 2.1 模块结构

```
com.tianji.auth
├── controller   # AccountController / JwkController / MenuController / PrivilegeController / RoleController
├── service(+impl) # IAccountService、IPrivilegeService、IRoleService、IMenuService、ILoginRecordService 及账号角色关联
├── domain       # po: AccountRole、LoginRecord、Menu、Privilege、Role、RoleMenu、RolePrivilege
├── mapper       # 各表 Mapper
├── task         # LoadPrivilegeRunner 启动预热权限缓存
└── util         # JwtTool、PrivilegeCache
```

### 2.2 对外接口

**AccountController（`/accounts`）——登录核心**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/accounts/login` | 普通用户账号密码登录（网关放行） |
| POST | `/accounts/admin/login` | 管理端登录（网关放行） |
| POST | `/accounts/logout` | 退出登录（作废 JTI、清 cookie） |
| GET | `/accounts/refresh` | 用 refresh-token 换新 token（网关放行） |

**其他 Controller**

- `JwkController`：`/jwks` 暴露 JWT 公钥（网关与资源服务验签用，网关放行）。
- `MenuController` / `RoleController` / `PrivilegeController`：管理端菜单、角色、权限点的 CRUD 与关联分配（RBAC）。

### 2.3 登录与 JWT 流程（`AccountServiceImpl` + `JwtTool`）

1. `login(loginDTO, isStaff)`：调用 **UserClient.queryUserDetail** 校验账号密码（用户数据在 tj-user，不在本服务），返回 `LoginUserDTO`。
2. `generateToken`：
   - `jwtTool.createToken` 签发 **access-token**（RS256，payload 含 `user` 与 `jti`）。
   - `jwtTool.createRefreshToken` 签发 **refresh-token**，其 JTI 写入 Redis：key `jwt:uid:{userId}`，TTL 与 token 一致——**单设备登录**：新登录覆盖旧 JTI，旧 refresh-token 立即失效。
   - refresh-token 写入 HttpOnly Cookie（普通用户头 `refresh`，管理端 `admin-refresh`；勾选"记住我"时 maxAge=7 天，否则会话 cookie）。
3. `loginRecordService.loginSuccess` 记录登录日志。
4. `refreshToken`：解析 refresh-token → 校验 Redis 中 JTI 一致 → 重新签发一对新 token。
5. `logout`：删除 Redis JTI + 清除 refresh cookie。

**JWT 关键常量（JwtConstants）**

| 常量 | 值 | 说明 |
|---|---|---|
| `JWT_ALGORITHM` | rs256 | 非对称签名 |
| `JWT_TOKEN_TTL` | 30 天 | access-token 有效期 |
| `JWT_REFRESH_TTL` | 30 分钟 | refresh-token 有效期 |
| `JWT_REMEMBER_ME_TTL` | 7 天 | 记住我 cookie TTL |
| `JWT_REDIS_KEY_PREFIX` | `jwt:uid:` | JTI 缓存 key 前缀 |
| `AUTHORIZATION_HEADER` | `authorization` | access-token 请求头 |
| `USER_HEADER` / `TOKEN_HEADER` | `user-info` / `token-info` | 网关向下游透传的用户 ID/token 头 |

> 注意：当前代码中 access-token TTL 被改为 30 天、refresh-token 为 30 分钟（与注释里"5 分钟"的旧值不同），刷新语义实际依赖 access-token 的长有效期。

### 2.4 权限缓存

- `LoadPrivilegeRunner`（ApplicationRunner）：启动时加载「权限-角色」关系到 Redis（`PrivilegeCache.initPrivilegesCache`），失败可重试。
- `PrivilegeCache`：
  - Hash key `auth:privileges`（`AUTH_PRIVILEGE_KEY`）→ 权限路径 → 角色集合。
  - 版本号 key `auth:privileges` 下的 `version` 字段（自增），供网关侧感知权限变更。
  - 分布式锁 key `lock:auth:privileges` 防止并发重建缓存。
- 网关 `AuthUtil.checkAuth` 校验「路径-角色」时读取的正是这份 Redis 缓存。

## 3. tj-auth-resource-sdk（资源服务侧 SDK）

被所有业务服务依赖，核心组件：

| 组件 | 职责 |
|---|---|
| `LoginAuthInterceptor` | MVC 拦截器：校验请求是否已登录（读网关透传的 `user-info` 头），未登录抛 `UnauthorizedException` |
| `UserInfoInterceptor` | 把请求头中的用户 ID 写入 `UserContext`（ThreadLocal），请求结束 `afterCompletion` 清理 |
| `FeignRelayUserInterceptor` | Feign `RequestInterceptor`：服务间调用时把当前用户信息继续透传给下游 |
| `ResourceInterceptorConfiguration` | 自动注册以上拦截器（spring.factories / AutoConfiguration.imports） |
| `ResourceAuthProperties` | 配置前缀 `tj.auth.resource`，关键项 `tj.auth.resource.enable`（是否启用登录校验） |
| `FeignRelayUserAutoConfiguration` | Feign 透传自动配置 |

**业务服务典型接入**：

```yaml
tj:
  auth:
    resource:
      enable: true   # 开启登录校验
      exclude-path:  # 可选：该服务内免登录路径
```

## 4. tj-auth-gateway-sdk（网关侧 SDK）

- **`AuthUtil`**：
  - `parseToken(token)`：RS256 验签解析 access-token → `R<LoginUserDTO>`（`JwtSignerHolder` 持有签名器，公钥来自 `/jwks`）。
  - `checkAuth(antPath, r)`：基于 Redis 权限缓存（`auth:privileges`）校验当前路径所需角色与用户角色是否匹配。
  - `refreshTask()`：监听权限缓存版本变化并刷新本地/Redis 缓存。
- 由 `AuthAutoConfiguration` 自动装配，tj-gateway 依赖后 `AccountAuthFilter` 直接使用。

## 5. 数据模型（tj-auth-service）

| 表/实体 | 说明 |
|---|---|
| `AccountRole` | 账号-角色关联 |
| `Role` / `RoleMenu` / `RolePrivilege` | 角色及角色-菜单、角色-权限关联（RBAC 核心） |
| `Privilege` | 权限点（RESTful 风格：路径 + 方法 + 角色） |
| `Menu` | 管理端菜单树 |
| `LoginRecord` | 登录日志（手机号、时间、是否成功） |

> 注意：**账号表本身在 tj-user 服务**（通过 UserClient 校验），auth 库只存角色/权限/菜单/登录记录。

## 6. 配置说明

- `application.yml`（8081）+ 多环境 `application-local/dev/test.yml`，全部通过 `spring.config.import` 从 Nacos 拉取：
  - `shared-spring.yaml`、`shared-redis.yaml`、`shared-mybatis.yaml`、`shared-logs.yaml`
- JWT 密钥对由 `AuthConfig` 提供（RSA 公私钥，配置于 Nacos 共享配置）。

## 7. 依赖关系

- auth-service → tj-common、tj-api（UserClient）、Redisson/Redis。
- resource-sdk → tj-common（UserContext、异常）；被所有业务服务依赖。
- gateway-sdk → tj-common（`R`、`LoginUserDTO`）；被 tj-gateway 依赖。
- 与 tj-user 的边界：用户凭据校验在 tj-user，token 签发在本模块。

## 8. 注意事项

1. **access-token TTL 当前为 30 天**（JwtConstants 中被调大），如需收紧有效期注意同时调整 refresh 流程与前端 401 处理。
2. 单设备登录依赖 Redis JTI 覆盖逻辑，多端同时在线会被顶下线；改造前先确认产品预期。
3. 权限变更有 Redis 缓存 + 版本号，`LoadPrivilegeRunner` 只在启动时预热；运行期修改权限后要触发 `PrivilegeCache` 对应更新方法，否则网关校验仍用旧缓存。
4. `AccountServiceImpl` 在本工作区有未提交修改，以磁盘代码为准。
5. 资源服务关闭校验只需 `tj.auth.resource.enable=false`（如支付回调等对内接口场景）。
