# tj-gateway

> 系统统一入口：基于 Spring Cloud Gateway 的 API 网关，负责路由转发、JWT 登录校验、用户信息透传、CORS 与统一异常返回。

## 1. 模块概述

- **模块职责**：所有外部 HTTP 请求的唯一入口。按路径前缀路由到各微服务；通过全局过滤器解析 JWT，将用户 ID/Token 写入请求头传给下游服务；处理全局跨域与网关层异常。
- **服务形态**：独立微服务。
- **服务名/端口**：`gateway-service`，端口 **10010**。
- **外部依赖**：Nacos（服务发现 lb://）、Redis（可选，配合 auth-sdk）、tj-auth-gateway-sdk（`AuthUtil`）。

## 2. 模块结构

```
com.tianji.gateway
├── config      # AuthProperties（放行路径配置）
├── exception.handler  # GatewayExceptionHandler 统一异常返回
├── filter      # AccountAuthFilter（全局鉴权）、RequestIdRelayFilter（链路ID透传）
├── swagger     # 聚合各服务文档：GatewaySwaggerResourceProvider、SwaggerResourceController
└── GatewayApplication
```

依赖 `tj-auth-gateway-sdk`（提供 `com.tianji.authsdk.gateway.util.AuthUtil`）。

## 3. 路由表（application.yml）

全部走 Nacos 负载均衡（`lb://`），`default-filters: StripPrefix=1`（转发前去掉一级前缀）：

| 路由 id | 路径前缀 | 目标服务 |
|---|---|---|
| us | `/us/**` | user-service（用户） |
| as | `/as/**` | auth-service（认证，带 `PreserveHostHeader`） |
| cs | `/cs/**` | course-service（课程） |
| ls | `/ls/**` | learning-service（学习） |
| es | `/es/**` | exam-service（考试） |
| ts | `/ts/**` | trade-service（交易） |
| ps | `/ps/**` | pay-service（支付） |
| prs | `/prs/**` | promotion-service（营销） |
| ss | `/ss/**` | search-service（搜索） |
| ms | `/ms/**` | media-service（媒资） |
| sms | `/sms/**` | message-service（消息） |
| rs | `/rs/**` | remark-service（评价） |
| ds | `/ds/**` | data-service（数据） |
| ais | `/ais/**` | aigc-service（AI） |
| os | `/os/**` | order-service（历史遗留路由，当前项目实际对应 trade-service） |

## 4. 核心业务逻辑

### 4.1 全局鉴权 `AccountAuthFilter`（Order=1000）

处理流程：

1. 拼接 `antPath = "METHOD:path"`（如 `POST:/accounts/login`）。
2. 与 `AuthProperties.excludePath` 匹配（AntPathMatcher），命中直接放行。
3. 从 `Authorization` 请求头取 token，调用 `authUtil.parseToken(token)` 解析 JWT 为 `R<LoginUserDTO>`。
4. 解析成功则改写请求头，向下游传递：
   - `USER_HEADER`（JwtConstants 常量）：用户 ID
   - `TOKEN_HEADER`：原始 token
5. 调用 `authUtil.checkAuth(antPath, r)` 校验权限后放行。

### 4.2 放行路径 `AuthProperties`（前缀 `tj.auth`）

`excludePath` 配置项，初始化后默认追加：

```yaml
tj:
  auth:
    exclude-path:
      - /error/**
      - /jwks
      - /accounts/login        # 普通用户登录
      - /accounts/admin/login  # 管理端登录
      - /accounts/refresh      # 刷新 token
```

> 注意：`afterPropertiesSet()` 里直接对 Set 做 `add`，要求 yml 中配置的必须是非 null 集合，否则 NPE。

### 4.3 其他组件

- **`RequestIdRelayFilter`**：把请求链路 ID 透传到下游（与 tj-common `RequestIdFilter`、tj-api `RequestIdRelayConfiguration` 配套）。
- **`GatewayExceptionHandler`**：网关层异常（路由失败、404 等）统一转成 `R` 格式 JSON 返回。
- **swagger 包**：`SwaggerResourceController` 提供聚合文档资源接口，可在网关侧汇总查看各服务 `/doc.html`。

### 4.4 全局 CORS（application.yml `globalcors`）

- `add-to-simple-url-handler-mapping: true`：保证 OPTIONS 预检不被拦截。
- 允许所有来源（`allowedOriginPatterns: "*"` + `allowCredentials: true`）、GET/POST/PUT/DELETE/OPTIONS、所有请求头，预检有效期 360000s。

## 5. 数据模型

无数据库。核心传输对象：`LoginUserDTO`（网关解析 JWT 后透传的用户信息）。

## 6. 配置说明

- `application.yml`：端口 10010、路由表、CORS；默认 `spring.profiles.active=local`，另有 dev/test 等环境配置。
- `tj.auth.exclude-path`：放行路径集合。

## 7. 依赖关系

- 依赖 **tj-auth-gateway-sdk**（JWT 解析与权限校验工具 `AuthUtil`、`JwtConstants`）。
- 依赖 tj-common（`R`、`LoginUserDTO`）。
- 被**所有**下游业务服务依赖（流量入口）。

## 8. 注意事项

1. **网关只做登录态解析与透传，不做业务鉴权落地**；各服务再用 tj-auth-resource-sdk 做细粒度校验。网关与资源服务需使用同一套 JWT 公钥（`/jwks` 放行供下游取公钥）。
2. 新增微服务时必须：在 yml 增加路由（前缀 + `lb://` + StripPrefix 生效于 default-filters），并确认前缀与下游服务接口不冲突。
3. `AccountAuthFilter` 用 `authHeaders.get(0)` 取第一个 Authorization 头，请求方不要重复携带该头。
4. 本工作区 `AuthProperties`、`AccountAuthFilter` 有未提交修改，以磁盘代码为准；调整鉴权行为时注意与前端约定的 header 一致。
