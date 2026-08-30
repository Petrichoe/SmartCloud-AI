# tj-media

> 媒资中心：管理课程视频（云点播）与普通文件（对象存储）的上传、播放签名与元数据，当前以腾讯云为主、阿里云为备。

## 1. 模块概述

- **模块职责**：
  - **媒资（视频）**：走腾讯云点播 VOD——客户端先取上传签名，直传云点播；播放/预览前取播放签名；服务端轮询拉取上传/转码事件，落 `media` 表并回填封面。
  - **文件**：走对象存储（腾讯 COS / 阿里 OSS 双实现）——服务端代理上传/下载/删除，`file` 表登记。
- **服务名/端口**：`media-service`，端口 **8084**。
- **网关路由前缀**：`/ms/**`。
- **数据库**：MySQL `tj_media`。
- **外部依赖**：腾讯云 VOD/COS SDK、阿里云 OSS SDK、Nacos。

## 2. 模块结构

```
com.tianji.media
├── controller   # MediaController（媒资+签名）、FileController（文件）
├── service(+impl) # IMediaService、IFileService
├── storage      # 抽象：IFileStorage、IMediaStorage、MediaUploadResult
│   ├── ali      # AliFileStorage（阿里 OSS 文件实现）
│   └── tencent  # TencentFileStorage、TencentMediaStorage（腾讯 COS/VOD 实现）
├── task         # PullEventTask：轮询云点播事件回调
├── config       # AliConfig/AliProperties、TencentConfig/TencentProperties、PlatformProperties
├── domain       # po: Media、File；dto/vo: MediaDTO、MediaVO、VideoPlayVO、FileDTO…
└── enums        # FilePlatform、FileStatus、Platform
```

## 3. 对外接口

### MediaController（`/medias`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/medias` | 媒资分页查询 |
| POST | `/medias` | 手动登记媒资（含 filePlatform/fileId/categoryId/mediaName 等信息） |
| GET | `/medias/signature/upload` | **上传签名**：前端直传云点播的签名（filePlatform、fileId、tplCode 等参数） |
| GET | `/medias/signature/play` | **播放签名**：点播播放凭证（网关放行路径之一，用于播放器鉴权） |
| GET | `/medias/signature/preview` | 试看/预览签名 |

### FileController（`/files`）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/files` | 文件上传（multipart，服务端转存对象存储，返回 FileDTO 含 url） |
| GET | `/files/{id}` | 文件信息/下载 |

## 4. 核心业务逻辑

### 4.1 存储抽象（`storage` 包）

- `IFileStorage`：`uploadFile / downloadFile / deleteFile / deleteFiles`——文件能力接口。
- `IMediaStorage`：媒资（视频）能力接口。
- 实现：`TencentFileStorage` + `TencentMediaStorage`（腾讯 COS/VOD）、`AliFileStorage`（阿里 OSS）。
- 当前启用平台由配置决定：`tj.platform.file: TENCENT`（可切 `ALI`），`PlatformProperties`/`Platform` 枚举承载。

### 4.2 视频上传/播放链路

1. 前端调 `GET /medias/signature/upload` 获取签名（按 filePlatform/fileId/tplCode 生成，含转码模板）。
2. 前端直传云点播，不上传业务服务器。
3. `PullEventTask.pullEvent()` 轮询云点播事件（`@Scheduled` 默认被注释，10s 周期）：
   - `FileUploadEvent` → 更新 `media` 表状态（`FileStatus`）。
   - `ProcedureStateChangeEvent` → 从转码结果取封面快照 `coverUrl` 回填。
4. 播放时 `GET /medias/signature/play` 生成播放签名返回 `VideoPlayVO`。

### 4.3 与课程的关联

- tj-course 编辑课程视频步骤时引用媒资（`MediaQuoteDTO`），`/course/media/useInfo` 由 course-service 反查媒资引用信息。

## 5. 数据模型

| 表/实体 | 关键字段 |
|---|---|
| `Media` | `fileId`（云点播 fileId）、`mediaName`、`coverUrl`、`filePlatform`（TENCENT/ALI）、`status`（上传/转码状态）、`categoryId` |
| `File` | `key`、`fileName`、`url`、`platform`、`status` |

## 6. 配置说明

- 端口 8084；`tj.jdbc.database: tj_media`。
- `tj.platform.file: TENCENT`（启用平台）；腾讯/阿里密钥与参数经 `TencentProperties`/`AliProperties`（Nacos 配置）。
- 网关放行 `/medias/signature/play`（见 application.yml 中 exclude-path 配置）。

## 7. 依赖关系

- 被依赖：tj-course（视频媒资引用）、其他需要文件/视频的服务。
- 外部中间件：腾讯云 VOD/COS、阿里云 OSS、MySQL、Nacos。

## 8. 注意事项

1. **云点播事件靠轮询拉取**（`PullEventTask`），`@Scheduled` 目前注释掉了——媒资状态不自动更新时先确认该任务是否启用；生产建议改用云厂商回调。
2. 切换 `tj.platform.file` 只影响**新上传**文件/媒资，历史数据仍按其 `filePlatform` 处理。
3. 云厂商密钥在 Nacos 中配置，不要硬编码或提交到仓库。
4. 播放签名接口是放行路径，注意它只返回签名凭证而非视频本身，真正的防盗链由云点播签名机制保障。
