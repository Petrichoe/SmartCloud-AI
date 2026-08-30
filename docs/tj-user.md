# tj-user

> 用户中心：管理平台所有账号（学员/员工/教师）的资料、密码与状态，并负责登录凭据校验（账号密码 / 短信验证码），配合 tj-auth 完成登录。

## 1. 模块概述

- **模块职责**：用户（账号表 `user` + 详情表 `user_detail`）的增删改查；学员注册、密码重置/修改；员工与教师管理；短信验证码发送与校验；为 tj-auth 的登录提供凭据校验接口（`POST /users/detail/{isStaff}`）。
- **服务名/端口**：`user-service`，端口 **8082**。
- **网关路由前缀**：`/us/**`。
- **数据库**：MySQL `tj_user`（`tj.jdbc.database=tj_user`）。
- **外部依赖**：Redis（验证码）、RabbitMQ/SMS（验证码下发，走 tj-message 的 `SmsInfoDTO` 链路或 SMS 交换机，以代码为准）。

## 2. 模块结构

```
com.tianji.user
├── controller   # UserController / StudentController / StaffController / TeacherController
├── service(+impl) # IUserService、IUserDetailService、IStudentService、IStaffService、ITeacherService、ICodeService
├── domain       # po: User、UserDetail；dto: UserDTO、UserFormDTO、StudentFormDTO、LoginFormDTO(在tj-api)；vo: UserBasicVO、UserDetailVO、StaffVO、StudentPageVo、TeacherPageVO
├── mapper       # UserMapper、UserDetailMapper
├── config       # SecurityConfig（BCrypt PasswordEncoder）
├── constants    # UserConstants（如默认密码）、UserErrorInfo
└── enums        # UserStatus
```

## 3. 对外接口

### UserController（`/users`）——账号核心

| 方法 | 路径 | 用途 | 备注 |
|---|---|---|---|
| POST | `/users` | 新增用户（管理端） | 密码 BCrypt 加密，默认密码见 `UserConstants` |
| PUT | `/users/{id}` | 修改用户信息（管理端） | |
| PUT | `/users` | 修改我的信息（含可选改密，需校验旧密码） | |
| PUT | `/users/{id}/password/default` | 重置密码为默认值 | 管理端 |
| PUT | `/users/{id}/status/{status}` | 启用/冻结账号 | |
| GET | `/users/me` | 查询当前登录用户详情 | |
| GET | `/users/{id}` | 查询用户详情 | |
| POST | `/users/detail/{isStaff}` | **登录校验**：按手机号+密码/验证码校验并返回 `LoginUserDTO` | tj-auth 登录时调用；`isStaff` 区分员工/学员 |
| GET | `/users/list` | 按条件查用户列表 | 内部接口 |
| GET | `/users/{id}/type` | 查询用户类型 | 内部接口 |
| GET | `/users/ids` | 按 id 集合批量查询 | 内部接口（Feign 常用） |
| GET | `/users/checkCellphone` | 手机号是否已注册 | |

### StudentController（`/students`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/students/page` | 学员分页（管理端） |
| POST | `/students/register` | 学员注册（手机号 + 验证码 + 密码） |
| PUT | `/students/password` | 学员自助改密（验证码校验） |

### StaffController（`/staffs`）

- `GET /staffs/page`：员工分页。

### TeacherController（`/teachers`）

- `GET /teachers/page`：教师分页。

## 4. 核心业务逻辑

### 4.1 登录凭据校验（`UserServiceImpl.queryUserDetail`）

- tj-auth 登录 → `UserClient.queryUserDetail(loginDTO, isStaff)` → 本服务：
  - 员工/管理端：按手机号查 `user`，`passwordEncoder.matches` 校验 BCrypt 密码（`loginByPw`）。
  - 学员可走**验证码登录**（`loginByVerifyCode`：校验 Redis 中的短信验证码）。
  - 校验通过返回 `LoginUserDTO`（含 userId、角色等），由 tj-auth 据此签发 JWT。
- 账号冻结（`UserStatus`）会拒绝登录。

### 4.2 短信验证码（`CodeServiceImpl`）

- `sendVerifyCode(phone)`：生成验证码 → 存 Redis（key 前缀 `USER_VERIFY_CODE_KEY`，带 TTL，重复发送复用未过期验证码）→ 通过短信渠道下发。
- `verifyCode(phone, code)`：与 Redis 比对，错误抛业务异常（`BizIllegalException`）。
- 使用场景：学员注册、验证码登录、自助改密。

### 4.3 注册与密码管理

- `StudentServiceImpl.saveStudent`：`userService.addUserByPhone`（先 `verifyCode`，密码 BCrypt 加密）+ 学员角色关联。
- `updatePasswordByPhone`：验证码校验后更新密码。
- 管理端重置密码统一为 `UserConstants.DEFAULT_PASSWORD` 再 BCrypt 编码。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `User` | `id`、`username`、`cell_phone`、`password`（BCrypt）、`type`（员工/学员）、`status`（正常/冻结） |
| `UserDetail` | `user_id`、真实姓名、头像、性别、城市、简介等扩展信息（1:1 关联 user） |

## 6. 配置说明

- 端口 8082；`tj.jdbc.database: tj_user`。
- 多环境 yml + Nacos（共享 shared-*.yaml 模式，与 tj-auth 相同）。
- `SecurityConfig` 仅提供 `BCryptPasswordEncoder` Bean，安全校验由 tj-auth-resource-sdk 承担。

## 7. 依赖关系

- 被 tj-auth（登录校验）、其他需要用户信息的业务服务（UserClient）调用。
- 依赖 tj-common（工具/异常）、tj-auth-resource-sdk（登录校验）、Redis。

## 8. 注意事项

1. `/users/detail/{isStaff}` 是内部登录接口，**入参含明文密码**，只允许内网/网关白名单链路调用，不要对外暴露。
2. 验证码 key 与 TTL 在 `CodeServiceImpl`/常量中定义，调整时注意与前端倒计时一致。
3. 用户表是全平台单点账号体系（auth 的角色关联以 accountId 为准），禁止其他服务直连 tj_user 库跨服务改数据。
