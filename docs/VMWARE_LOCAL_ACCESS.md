# 本地 VMware 虚拟机静态配置与操作手册

本文档用于指导 AI 在当前项目中操作本地 VMware 虚拟机。涉及虚拟机 SSH、网络、Nginx、Docker、前端部署或网关排查时，先阅读本文档，再根据实时状态执行检查。

本文档只记录相对稳定的连接配置、目录约定、网络拓扑和操作规则，不记录容器运行状态、进程状态、最近提交、临时 IP 租约或其他动态快照。动态信息必须在执行任务前实时检查。

## 1. 连接配置

| 项目 | 默认配置 |
| --- | --- |
| 登录后预期主机名 | `heima` |
| 虚拟机 IP | `192.168.150.101` |
| SSH 端口 | `22` |
| SSH 用户 | `root` |
| VMware 网络 | `VMnet3`，NAT 模式 |
| VMnet3 网段 | `192.168.150.0/24` |
| Windows VMnet3 网卡 | 通常为 `192.168.150.1` |
| 本机专用 SSH 私钥 | `C:\Users\31241\.ssh\tjxt_vm_ed25519` |

不要在本文档或项目文件中记录密码、私钥内容、令牌或其他凭据。MobaXterm 保存的凭据不会自动共享给 Codex。

## 2. SSH 操作流程

1. 用户明确要求操作虚拟机后，优先使用本机专用私钥，不使用 Telnet。
2. 先从主机测试 `192.168.150.101:22` 是否可达；必要时使用主机网络权限，避免把沙箱网络限制误判为虚拟机故障。
3. 使用 SSH 密钥并指定 `IdentitiesOnly=yes`，避免误试其他密钥。
4. 首次连接或目标可能变化时，先执行只读确认：

   ```bash
   hostname
   whoami
   pwd
   ```

5. 只有在密钥不可用且用户明确授权时，才使用临时提供的密码完成一次认证；密码不得写入命令、日志、本文档或回复。
6. 用户未明确要求时，只执行查看和诊断命令。删除、覆盖、重置、停止服务、修改防火墙/网络、重启服务等操作必须先确认。

## 3. VMware 网络关系

VMnet3 是连接 Windows 主机和虚拟机的 NAT 网络：

```text
Windows 主机 VMnet3 网卡 192.168.150.1
              │
       VMnet3 虚拟交换网络
              │
虚拟机 ens33 192.168.150.101
```

`192.168.150.0/24` 中，`.0` 是网络地址，`.1` 通常是主机虚拟网卡，`.101` 是虚拟机地址，`.255` 是广播地址。

本机 `hosts` 文件约定将多个开发域名指向虚拟机：

```text
192.168.150.101 www.tianji.com
192.168.150.101 api.tianji.com
192.168.150.101 manage.tianji.com
192.168.150.101 git.tianji.com
192.168.150.101 jenkins.tianji.com
192.168.150.101 mq.tianji.com
192.168.150.101 nacos.tianji.com
192.168.150.101 xxljob.tianji.com
192.168.150.101 es.tianji.com
```

`hosts` 只负责域名解析，不会启动服务，也不会自动提供 HTTPS。

## 4. Nginx 结构

Nginx 不是系统 PATH 中的安装，实际位置为：

```text
程序：/usr/local/src/nginx/sbin/nginx
配置：/usr/local/src/nginx/conf/nginx.conf
版本：1.22.0
```

配置基线只监听 HTTP：

```nginx
listen 80;
```

没有 `listen 443 ssl`、证书或私钥配置，因此按此配置应使用：

```text
http://www.tianji.com/
```

不能直接使用 `https://www.tianji.com/`。如果浏览器自动升级到 HTTPS，需要检查 HTTPS-First 或 HSTS 行为。

主要虚拟主机关系：

| 域名 | Nginx 行为 |
| --- | --- |
| `www.tianji.com` | 静态目录 `/usr/local/src/tj-portal` |
| `manage.tianji.com` | 静态目录 `/usr/local/src/tj-admin` |
| `api.tianji.com` | 代理到 Windows 主机 `192.168.150.1:10010` |
| `git.tianji.com` | 代理到虚拟机 `localhost:10880` |
| `jenkins.tianji.com` | 代理到虚拟机 `localhost:18080` |
| `mq.tianji.com` | 代理到虚拟机 `localhost:15672` |
| `nacos.tianji.com` | 重写后代理到虚拟机 `localhost:8848` |
| `xxljob.tianji.com` | 重写后代理到虚拟机 `localhost:8880` |
| `es.tianji.com` | 代理到虚拟机 `localhost:5601` |

特别注意：`api.tianji.com` 使用 `192.168.150.1:10010`，因为网关运行在 Windows 主机上；不要擅自改成虚拟机内的 `localhost:10010`。

查看生效配置：

```bash
/usr/local/src/nginx/sbin/nginx -t
/usr/local/src/nginx/sbin/nginx -T
```

## 5. 前端部署布局

`www.tianji.com` 的 Nginx 静态根目录约定为：

```text
/usr/local/src/tj-portal
```

该目录用于存放 `index.html`、`assets/*.js`、`assets/*.css` 和图片等构建产物。部署目录通常不包含 `package.json`、`src`、Vite 或 Webpack 配置，不应把 hash 命名的 JS/CSS 当作源码编辑。

管理端前端目录为：

```text
/usr/local/src/tj-admin
```

修改前端时，通常应在源码工程中修改、重新构建，再将构建产物部署到对应目录。不要直接把 hash 命名的 JS/CSS 当作源码编辑。

门户前端源码位于 Windows 主机：

```text
D:\computer technology\code\Project-me\tianji\tj-portal-src
```

该工程使用 Vue 3 和 Vite，开发配置约定如下：

| 项目 | 配置 |
| --- | --- |
| 开发端口 | `18082` |
| 监听地址 | `0.0.0.0` |
| development API 地址 | `http://api.tianji.com` |
| 本地访问地址 | `http://localhost:18082/` |

启动前端开发服务器：

```powershell
cd 'D:\computer technology\code\Project-me\tianji\tj-portal-src'
npm ci
npm run dev
```

`npm ci` 仅在依赖尚未安装或需要按锁文件恢复时执行；日常修改源码后 Vite 会自动热更新。开发服务器通过 `api.tianji.com` 使用虚拟机 Nginx 和 Windows 网关的现有联调链路。

## 6. 网关连接配置

网关的部署约定是运行在 Windows 主机，监听 `10010`；Nginx 位于虚拟机时，应通过 Windows VMnet3 地址访问：

```text
192.168.150.1:10010
```

检查网关连通性：

```bash
curl -I --max-time 3 http://192.168.150.1:10010/
```

收到 HTTP `4xx/5xx` 仍可能表示 TCP 已连通，只能说明应用层返回了错误；连接拒绝或超时才表示端口/网络不可达。

## 7. Docker 检查约定

Docker 容器、镜像、网络、数据卷、版本和运行状态都属于动态信息，不写入本文档。每次任务开始前根据需要实时检查：

```bash
docker version
docker ps -a
docker images
docker network ls
docker volume ls
docker compose version
docker-compose version
```

## 8. 常用只读检查

```bash
# 系统和网络
hostname
ip -4 addr
ip route
ss -lntp

# Nginx
systemctl is-active nginx
/usr/local/src/nginx/sbin/nginx -t
/usr/local/src/nginx/sbin/nginx -T

# Docker
docker ps -a
docker images
docker network ls
docker volume ls
docker stats --no-stream
```

查看日志时限制时间范围和输出量，避免泄露凭据或产生过大输出；不要默认执行 `docker exec`、`docker inspect` 环境变量查看或读取配置中的密码。

## 9. 操作边界

- 只操作用户明确放入范围的这台本地虚拟机。
- 任何运行状态、端口、IP、容器、镜像和服务信息都应以实时检查为准，不要依赖本文档中的历史结果。
- 不读取、输出或提交私钥内容，不从 MobaXterm 提取保存的密码。
- 不把密码、令牌、数据库密钥或其他机密写入项目文档、源码、日志或命令参数。
- 删除、覆盖、重置、停止服务、修改网络/防火墙、重启服务和安装软件前，必须获得明确确认。
