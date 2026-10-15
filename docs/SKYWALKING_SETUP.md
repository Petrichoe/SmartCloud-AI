# SkyWalking 接入与使用说明

本文档记录 TJXT 项目当前使用的 SkyWalking 配置、IDEA 本地启动方式、虚拟机 Docker 部署方式和常见排障方法。

最后验证时间：2026-09-19

## 1. 工作原理

SkyWalking Java Agent 不是一个独立运行的业务服务。它会通过 -javaagent 加载到每个 Java 服务进程中，采集请求、调用链、实例和 JVM 信息，再通过 gRPC 上报到虚拟机中的 OAP Server。

当前链路如下：

    IDEA 中的 Spring Boot 服务
      └─ SkyWalking Java Agent 9.7.0
           └─ gRPC 192.168.150.101:11800
                └─ SkyWalking OAP 10.4.0
                     └─ Elasticsearch 7.12.1

SkyWalking UI 通过 OAP 的 HTTP 端口读取数据。

## 2. 当前环境

| 组件 | 版本/地址 | 用途 |
|---|---|---|
| OAP Server | apache/skywalking-oap-server:10.4.0-java17 | 接收和分析 Agent 数据 |
| OAP gRPC | 192.168.150.101:11800 | Java Agent 上报地址 |
| OAP HTTP | 192.168.150.101:12800 | UI 查询和健康检查 |
| UI | apache/skywalking-ui:10.4.0 | http://192.168.150.101:18090 |
| Java Agent | 9.7.0 | 加载到 Java 服务进程 |
| 存储 | 虚拟机中已有的 ES 7.12.1 | 保存 SkyWalking 数据 |

OAP 使用 Elasticsearch 容器 es:9200，存储命名空间为 tjxt。

## 3. 虚拟机中的 OAP 和 UI

OAP 和 UI 已在虚拟机中通过 Docker 启动，并设置了自动重启策略：

    skywalking-oap
    skywalking-ui

虚拟机正常开机后，如果 Docker 服务正常启动，这两个容器会自动启动。Java Agent 不会作为独立容器启动，只有业务 Java 服务启动时才会被加载。

检查状态：

    docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' | grep skywalking
    curl -fsS http://127.0.0.1:12800/healthcheck

健康检查返回 200 OK 表示 OAP HTTP 服务正常。

首次部署时使用的核心配置如下。已有容器正常运行时不要重复创建：

    docker run -d --name skywalking-oap --restart unless-stopped --network tjxt \
      --memory=768m --memory-swap=1g \
      -p 11800:11800 -p 12800:12800 \
      -e TZ=Asia/Shanghai \
      -e JAVA_OPTS='-Xms256m -Xmx512m' \
      -e SW_STORAGE=elasticsearch \
      -e SW_STORAGE_ES_CLUSTER_NODES=es:9200 \
      -e SW_NAMESPACE=tjxt \
      -e SW_STORAGE_ES_INDEX_SHARDS_NUMBER=1 \
      -e SW_STORAGE_ES_INDEX_REPLICAS_NUMBER=0 \
      apache/skywalking-oap-server:10.4.0-java17

    docker run -d --name skywalking-ui --restart unless-stopped --network tjxt \
      --memory=320m --memory-swap=512m \
      -p 18090:8080 \
      -e TZ=Asia/Shanghai \
      -e JAVA_OPTS='-Xms64m -Xmx192m' \
      -e SW_OAP_ADDRESS=http://skywalking-oap:12800 \
      apache/skywalking-ui:10.4.0

## 4. Java Agent 文件

本地 Agent 目录：

    D:\computer technology\code\Project-me\tianji\tjxt\tools\skywalking-agent

虚拟机 Agent 目录：

    /usr/local/src/skywalking-agent

核心文件是：

    skywalking-agent.jar

tools 目录已加入 .gitignore，Agent 二进制文件不应提交到 Git。

### 4.1 清理 ._*.jar 元数据文件

部分 Agent 压缩包包含 macOS 生成的 ._*.jar 元数据文件。它们不是合法 JAR，但 Agent 会尝试加载，导致：

    zip END header not found

Windows PowerShell 清理命令：

    Get-ChildItem -LiteralPath .\tools\skywalking-agent -Recurse -Force -File |
      Where-Object { $_.Name.StartsWith('._') } |
      Remove-Item -Force

Linux/虚拟机清理命令：

    find /usr/local/src/skywalking-agent -type f -name '._*' -delete

清理后检查：

    Get-ChildItem -LiteralPath .\tools\skywalking-agent -Recurse -Force -File |
      Where-Object { $_.Name.StartsWith('._') }

没有输出表示清理完成。

## 5. IDEA 直接启动服务

IDEA 直接启动的是 Windows 本地 Java 进程，不会经过虚拟机的 Docker 启动脚本。因此需要在 IDEA 的每个 Spring Boot 启动配置中加入 Agent。

打开：

    Run → Edit Configurations → 选择服务

在“虚拟机选项”中加入一行。推荐直接在 -javaagent 后面写入服务名和 OAP 地址，避免 IDEA 环境变量没有传入 Java 进程：

    -javaagent:"D:\computer technology\code\Project-me\tianji\tjxt\tools\skywalking-agent\skywalking-agent.jar=agent.service_name=course-service,collector.backend_service=192.168.150.101:11800,logging.output=CONSOLE"

注意：

- 只保留一条 -javaagent。
- 必须放在“虚拟机选项”，不要放到程序参数或类路径。
- Agent 路径包含空格时，保留外层双引号。
- 每个服务的 agent.service_name 应唯一。
- course-service 来自 tj-course/src/main/resources/application.yml 中的 spring.application.name。
- 修改后必须停止旧进程，再重新启动；Agent 只在 JVM 启动时加载。

也可以使用环境变量方式：

    SW_AGENT_NAME=course-service
    SW_AGENT_COLLECTOR_BACKEND_SERVICES=192.168.150.101:11800
    SW_LOGGING_OUTPUT=CONSOLE

但如果日志仍然出现 /127.0.0.1:11800，说明环境变量没有进入当前 IDEA 启动配置，建议改用上面的 -javaagent 直接配置方式。

## 6. 本地批量启动

项目根目录的 start-all-services.bat 已支持 Agent：

    start-all-services.bat /check
    start-all-services.bat local

默认读取：

    tools\skywalking-agent\skywalking-agent.jar

也可以通过环境变量覆盖：

    set SKYWALKING_AGENT_HOME=D:\path\to\skywalking-agent
    set SKYWALKING_COLLECTOR=192.168.150.101:11800
    start-all-services.bat local

该脚本会根据模块目录设置服务名。IDEA 启动时仍需要单独配置 Run/Debug Configuration。

## 7. 虚拟机 Docker 启动业务服务

虚拟机中的 /usr/local/src/script/startup.sh 已接入 SkyWalking。脚本检测到：

    /usr/local/src/skywalking-agent/skywalking-agent.jar

后，会自动为新创建的业务容器加入：

    -v /usr/local/src/skywalking-agent:/opt/skywalking-agent:ro
    -e JAVA_TOOL_OPTIONS=-javaagent:/opt/skywalking-agent/skywalking-agent.jar
    -e SW_AGENT_NAME=<服务名>
    -e SW_AGENT_COLLECTOR_BACKEND_SERVICES=192.168.150.101:11800

启动示例：

    cd /usr/local/src/script
    ./startup.sh -c <容器名> -n <jar名称> -d <项目路径> -p <端口>

注意：

- Agent 只会在创建或重建业务容器时挂载。
- 已经存在的旧容器不会自动补挂载，需要用该脚本重新创建。
- OAP/UI 容器不需要挂载 Java Agent。
- 原虚拟机启动脚本备份在 /usr/local/src/script/startup.sh.bak-skywalking-20260919。

## 8. 验证流程

### 8.1 检查主机到 OAP 的端口

在 Windows PowerShell 执行：

    Test-NetConnection 192.168.150.101 -Port 11800

TcpTestSucceeded: True 才能让 IDEA 中的 Agent 上报数据。

### 8.2 检查 Agent 启动

启动服务后，控制台不应出现：

    zip END header not found
    Connection refused: /127.0.0.1:11800

如果出现第二条，说明 Agent 仍在使用默认 Collector 地址。

### 8.3 在 UI 中查看

1. 启动带 Agent 的业务服务。
2. 至少访问一次业务接口。
3. 打开 http://192.168.150.101:18090。
4. 进入 General Service / Services，选择对应服务。
5. 查看 Service、Instance、Topology 和 Traces。

Dashboard List 用于管理仪表盘，不是查找新上报服务的主要入口。

服务菜单只有在 OAP 检测到服务上报后才会出现。首次上报后刷新页面，并把时间范围设置为最近 15 分钟。

## 9. 常见问题

### 9.1 出现 ._apm-xxx.jar jar file can't be resolved

原因是 Agent 目录中存在 macOS 元数据文件。按第 4.1 节清理本地和虚拟机 Agent 目录，然后完全重启 Java 服务。

### 9.2 日志连接 127.0.0.1:11800

原因是 Agent 使用了默认地址。IDEA 本地进程中的 127.0.0.1 指 Windows 主机，不是虚拟机。

将 collector.backend_service 直接写入 -javaagent 参数，或确保 SW_AGENT_COLLECTOR_BACKEND_SERVICES 已添加到当前 Run Configuration 的环境变量。

### 9.3 Agent 加载了但 UI 没有服务

按以下顺序检查：

1. 服务是否真正重启。
2. -javaagent 路径是否正确。
3. Collector 是否为 192.168.150.101:11800。
4. Windows 到 11800 端口是否连通。
5. 是否访问过业务接口。
6. UI 时间范围是否覆盖服务启动时间。

## 10. 资源和安全注意事项

- 当前虚拟机内存较紧张，OAP 和 UI 已设置内存上限，不建议在虚拟机中一次启动全部业务服务。
- Agent 二进制只作为本地运行依赖，不提交 Git。
- 不要把 API Key、密码、Token 写入本文档、项目配置或启动命令。
- 虚拟机原有部署脚本中存在历史敏感参数，本文档不记录其具体内容，建议迁移到环境变量或密钥管理系统。

## 11. 官方参考

- SkyWalking Java Agent 使用：https://skywalking.apache.org/docs/skywalking-java/latest/en/setup/service-agent/java-agent/readme/
- SkyWalking Java Agent 配置项：https://skywalking.apache.org/docs/skywalking-java/latest/en/setup/service-agent/java-agent/configurations/
- SkyWalking Agent 兼容性：https://skywalking.apache.org/docs/main/v10.4.0/en/setup/service-agent/agent-compatibility/
- SkyWalking UI 说明：https://skywalking.apache.org/docs/main/latest/en/ui/readme/
