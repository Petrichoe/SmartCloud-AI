# Codex：本地命令执行报 `setup refresh had errors`

## 记录信息

- 记录日期：2026-10-08（Asia/Shanghai）。
- 影响范围：Windows 上的 Codex 本地命令执行器和 Node 工具内核。
- 故障发生时的桌面程序版本：`26.1002.7124.0`。
- 故障发生时的本地执行器版本：`codex-cli 0.162.0-alpha.2`。
- 最终状态：用户授权采用 `unelevated` 临时回退，完全重启 Codex 后，普通命令执行和 Node 工具内核均恢复。
- 本次排障没有修改 TJXT 项目代码；本文记录的是开发工具故障处理。

版本仅用于记录本次环境，尚未证明该版本在所有机器上均有相同问题，也没有确定故障由哪次升级引入。

## 现象与失败链路

Codex 执行本地命令时，在 shell 启动之前失败，外层错误为：

```text
CreateProcess: Rejected
helper_unknown_error: setup refresh had errors
```

默认命令、裸 `echo`、指定 PowerShell 和项目目录，以及 Node 工具内核启动，均出现同一错误。命令没有实际开始执行，也没有进程退出码；Codex 项目列表仍能正确识别项目路径。

`Rejected` 在这里是创建执行进程失败的外层表现，需要继续查看 `setup refresh` 日志才能判断具体原因。

实际失败链路：

```text
请求执行命令或启动 Node 工具内核
  → Windows elevated 沙箱执行 setup refresh
  → 校验或更新内部运行时文件的访问权限（ACL）
  → 打开正在使用的文件时，Windows 返回 os error 32
  → setup refresh 以错误结束
  → shell 或 Node 工具内核无法启动
```

经授权在沙箱外执行的 PowerShell 诊断命令可以正常运行，将排查范围缩小到了 Windows 沙箱初始化阶段。

## 直接原因与日志证据

本次日志位于：

```text
C:\Users\31241\.codex\.sandbox\sandbox.2026-10-08.log
```

关键错误如下。为便于阅读，省略时间戳并换行展示：

```text
runtime read/execute validation failed:
validate runtime read/execute access on
C:\Users\31241\AppData\Local\OpenAI\Codex\runtimes\cua_node\3dd31cfff853001c\bin\node_repl.exe:
open ACL target for root-only update:
另一个程序正在使用此文件，进程无法访问。 (os error 32)
```

**已确认的直接原因是：沙箱刷新文件访问权限时，打开被 Codex 自身运行时占用的文件失败。** Windows 错误 32 对应文件共享冲突。

本次首先发现 5 个内部 REPL 进程占用 `node_repl.exe`。核对 PID 和可执行文件路径后释放这些进程，权限刷新越过原来的失败点，又在以下文件上出现同类错误：

```text
C:\Users\31241\AppData\Local\OpenAI\Codex\runtimes\cua_node\3dd31cfff853001c\bin\node_modules\@oai\sky\bin\windows\swift\x64\VCRUNTIME140_1.dll
```

该 DLL 被 `codex-computer-use-swift.exe` 加载。继续释放相关内部进程后，Codex 主程序又自动重建了它们。因此结束子进程不能稳定修复故障。

日志能够确认上述文件打开冲突；具体是哪段 Codex 源码或哪种文件共享方式造成的，尚未定位。本文不把它归结为某个已确认的官方缺陷。

### 查看诊断日志

本次使用 PowerShell 显式按 UTF-8 读取日志：

```powershell
Get-Content -LiteralPath 'C:\Users\31241\.codex\.sandbox\sandbox.2026-10-08.log' -Encoding UTF8 |
    Select-String -Pattern 'runtime read/execute validation failed', 'setup refresh completed with errors', 'os error 32'
```

其他会话应先选取 `.sandbox` 目录中最近更新的 `sandbox*.log`。本次 `sandbox.log` 保存的是旧日志，当天错误实际位于带日期的文件中。日志日期、运行时目录哈希及 PID 会变化，应以实时检查为准。

## 排查和处理过程

| 步骤 | 操作 | 结果与判断 |
|---|---|---|
| 1 | 用普通命令和 Node 工具复现 | 两者均在沙箱初始化阶段失败 |
| 2 | 经授权在沙箱外执行只读 PowerShell 诊断 | 命令正常运行，能够读取沙箱日志 |
| 3 | 检查当天日志、进程路径及文件权限 | 确认内部运行时文件占用和 Windows 错误 32 |
| 4 | 核对身份后释放相关内部进程 | 失败点转移到 DLL，且进程会被自动重建，无法稳定解决 |
| 5 | 在独立测试进程中采用 `unelevated` 回退 | 命令运行成功，工作区内读写成功，工作区外写入被拒绝 |
| 6 | 告知模式差异，取得用户明确同意 | 获得切换模式并备份配置的授权 |
| 7 | 完整备份配置，只修改 `windows.sandbox` | 校验备份 SHA-256 和完整配置差异，确认仅一个设置变化 |
| 8 | 完全退出并重新打开 Codex | 当前聊天的普通执行器和 Node 工具内核恢复 |

本次还用独立 Codex 沙箱复现了 `elevated` 模式下的错误，测试 `windows_sandbox_service` 功能开关后，同类错误仍存在；未将该开关作为持久修复。

## 已应用的临时回退

本机配置文件：

```text
C:\Users\31241\.codex\config.toml
```

将已有 `[windows]` 表中的：

```toml
[windows]
sandbox = "elevated"
```

改为：

```toml
[windows]
sandbox = "unelevated"
```

保留原来的 `sandbox_mode = "workspace-write"` 和审批相关设置，不重复追加 `[windows]` 表。

修改时显式使用严格 UTF-8 编码，原配置完整备份，采用临时文件原子替换，并验证备份 SHA-256 与修改前文件一致。完整配置对比确认唯一变化是 `windows.sandbox`。

本次原配置备份：

```text
C:\Users\31241\.codex\config.toml.before-sandbox-fix-20261008-153201.bak
```

配置文件和备份可能包含其他工具的敏感设置，本文只记录相关配置项及备份位置，不复制完整内容。

### 模式差异与授权

`unelevated` 是官方支持的 Windows 沙箱回退模式，仍提供文件权限边界；它不使用 `elevated` 模式的独立沙箱用户，网络隔离也较弱。本次在明确告知差异并取得用户同意后才修改持久配置。[官方模式与故障回退说明](https://learn.chatgpt.com/docs/windows/windows-sandbox)

首次持久配置修改被自动审批拒绝，理由是降低全局沙箱隔离保护超出当时授权。用户随后明确同意“允许切换并备份配置”，授权后的修改成功。

本次验证证明回退模式可恢复执行能力，同时指定工作区外写入仍被拒绝；它不能证明两种模式的所有安全边界完全相同，也不代表工作区所有子目录都具有相同的 Windows 文件权限。

### 配置生效

修改配置后，新启动的独立 Codex 沙箱已经能够执行命令，但当前聊天的普通执行器仍报原来的错误，说明当前会话尚未使用新设置。

完全退出并重新打开 Codex 后，再在同一聊天中验证普通执行器，确认新设置生效。

## 验证方法与实际结果

### 普通命令执行

让 Codex 通过普通命令执行工具运行以下命令，不使用沙箱外授权执行作为最终验收：

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Write-Output 'CODEX_DEFAULT_EXEC_OK'
Get-Location
```

本次重启后返回 `CODEX_DEFAULT_EXEC_OK`，退出码为 0，工作目录为：

```text
D:\computer technology\code\Project-me\tianji\tjxt
```

### Node 工具内核

通过 Node 工具执行：

```javascript
nodeRepl.write({ status: 'CODEX_NODE_REPL_OK', cwd: nodeRepl.cwd });
```

本次成功返回 `CODEX_NODE_REPL_OK` 和正确的项目工作目录。Node 内核启动后，普通命令仍能继续执行。

### 文件权限与 UTF-8

本次使用普通执行器完成以下检查：

| 检查 | 实际结果 |
|---|---|
| 在项目目录写入随机名称 UTF-8 临时文件并读回中文 | 成功，内容一致 |
| 向用户目录根部的工作区外随机路径写入文件 | 返回拒绝访问 |
| 清理项目内临时测试文件 | 成功，按精确路径删除 |
| 检查权限配置 | `sandbox_mode = "workspace-write"` 保持不变，`windows.sandbox = "unelevated"` |

最终验证完成于 2026-10-08 15:42:31（Asia/Shanghai）。验证期间没有修改项目代码。

## 遗留问题与恢复方式

当前恢复的是命令执行能力。`elevated` 模式下的运行时文件占用冲突本身仍未修复；本文记录的方案是临时回退。

准备恢复默认模式时：

1. 在 `config.toml` 中，只将 `[windows]` 下的 `sandbox` 改回 `"elevated"`，保留期间新增或修改的其他配置。
2. 按 UTF-8 保存，完全退出并重新打开 Codex。
3. 再次验证普通命令、Node 工具内核和工作区文件权限。
4. 如果仍出现相同错误，查看最新沙箱日志，结合实际失败点继续排查；需要继续采用回退时，应确认其隔离差异和授权。

恢复时优先只调整这一项。整份旧配置备份用于对照，不应直接覆盖此后新增的其他设置。

## 参考资料

- [OpenAI 官方 Windows sandbox 文档](https://learn.chatgpt.com/docs/windows/windows-sandbox)
- 本次故障日志：`C:\Users\31241\.codex\.sandbox\sandbox.2026-10-08.log`
- 本次配置备份：`C:\Users\31241\.codex\config.toml.before-sandbox-fix-20261008-153201.bak`
