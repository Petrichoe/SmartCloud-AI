# 天机学堂（TJXT）模块文档索引

基于 Spring Cloud 的微服务在线教育平台（Java 17 / Spring Boot 3.3.5 / Spring Cloud Alibaba）。每个模块一份详细文档，覆盖模块职责、目录结构、接口清单、核心业务逻辑、数据模型、配置、依赖关系与注意事项。

> 项目总体说明见 [`PROJECT_OVERVIEW.md`](PROJECT_OVERVIEW.md)；开发代理的强制约束见根目录 `AGENTS.md`；本文档以**当前磁盘代码**为准（工作区存在未提交修改）。

## 服务总览

| 模块 | 服务名 | 端口 | 网关前缀 | 存储 | 文档 |
|---|---|---|---|---|---|
| tj-gateway | gateway-service | 10010 | — | — | [tj-gateway.md](tj-gateway.md) |
| tj-auth | auth-service | 8081 | /as | MySQL + Redis | [tj-auth.md](tj-auth.md) |
| tj-user | user-service | 8082 | /us | tj_user | [tj-user.md](tj-user.md) |
| tj-search | search-service | 8083 | /ss | tj_search + ES | [tj-search.md](tj-search.md) |
| tj-media | media-service | 8084 | /ms | tj_media + 云点播/OSS | [tj-media.md](tj-media.md) |
| tj-message | message-service | 8085 | /sms | tj_message | [tj-message.md](tj-message.md) |
| tj-course | course-service | 8086 | /cs | tj_course | [tj-course.md](tj-course.md) |
| tj-pay | pay-service | 8087 | /ps | tj_pay | [tj-pay.md](tj-pay.md) |
| tj-trade | trade-service | 8088 | /ts | tj_trade | [tj-trade.md](tj-trade.md) |
| tj-exam | exam-service | 8089 | /es | tj_exam | [tj-exam.md](tj-exam.md) |
| tj-learning | learning-service | 8090 | /ls | tj_learning + Redis | [tj-learning.md](tj-learning.md) |
| tj-remark | remark-service | 8091 | /rs | tj_remark + Redis | [tj-remark.md](tj-remark.md) |
| tj-promotion | promotion-service | 8092 | /prs | tj_promotion + Redis | [tj-promotion.md](tj-promotion.md) |
| tj-data | data-service | 8093 | /ds | tj_data + Redis | [tj-data.md](tj-data.md) |
| tj-aigc | aigc-service | 8094 | /ais | tj_aigc + MongoDB/Redis/ES | [tj-aigc.md](tj-aigc.md) |

## 基础与公共模块

| 模块 | 形态 | 文档 |
|---|---|---|
| tj-common | 公共库：工具类、统一响应/异常、自动配置（MQ/MyBatis Plus/Redisson/Swagger/XXL-Job） | [tj-common.md](tj-common.md) |
| tj-api | 服务间通信：Feign 客户端 + 跨服务 DTO + 分类本地缓存 | [tj-api.md](tj-api.md) |
| tj-auth（含 resource-sdk / gateway-sdk） | 认证体系：JWT 登录、RBAC 权限、SDK 集成 | [tj-auth.md](tj-auth.md) |

## 业务速览

- **内容侧**：tj-course（课程/分类/目录，草稿双表）→ tj-search（ES 检索/推荐）→ tj-media（视频直传云点播）→ tj-exam（题目库）
- **交易侧**：tj-trade（购物车/下单）→ tj-pay（支付/回调/退款）→ tj-promotion（优惠券 Lua 异步领券/折扣计算/兑换码）
- **学习侧**：tj-learning（课表/学习记录延迟合并写/签到 BitMap/积分赛季榜/问答/笔记）→ tj-remark（点赞）→ tj-message（短信/站内信）
- **数据与 AI**：tj-data（看板只读 Redis）、tj-aigc（多智能体课程顾问，SSE + 混合记忆 + 向量检索 + TTS）

## 关键跨服务链路

1. **登录**：前端 → gateway（`AccountAuthFilter` 验 JWT）→ 业务服务（tj-auth-resource-sdk 拦截器 → `UserContext`）
2. **购买上课**：trade 下单 → pay 支付/回调 → MQ `pay.success`/延迟查单 → trade 发 `order.pay` → learning 入课表 → learning 发积分 MQ → ZSet 排行榜
3. **课程上架**：course 草稿校验上架 → MQ `course.up` → search 同步 ES 索引
4. **领优惠券**：promotion Lua 原子校验 → MQ `coupon.receive` → 异步落库 user_coupon
5. **学习互动**：learning 问答/笔记 → remark 点赞 → 20s 定时 MQ 回同步点赞数
6. **AI 顾问**：aigc RouteAgent 意图路由 → 专业智能体（ES 向量查课 / TradeClient 下单）→ SSE 流式返回

## 敏感区域设计文档

- AIGC 对话存储改造：`tj-aigc/src/main/resources/doc/README_CHAT_REFACTOR.md`
- 优惠券异步领券：`tj-promotion/src/main/resources/doc/COUPON_ASYNC_RECEIVE.md`
- 优惠券 Lua 优化：`tj-promotion/src/main/resources/doc/COUPON_LUA_OPTIMIZATION.md`
- 兑换码安全重构：`tj-promotion/src/main/resources/doc/exchange-code-security-refactoring.md`

## 排障与解决方案

- [解决方案目录](solutions/README.md)
- [AIGC：qwen3.8-flash 报 `url error`](solutions/aigc-dashscope-qwen38-flash-url-error.md)
