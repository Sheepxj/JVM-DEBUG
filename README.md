# JVM 调优学习实验室（JDK 21）

从 2026-10-22 起，按 [JVM 调优课程计划](JVM调优课程计划.md) 的 **3 个阶段、6 门课程**逐步学习。[第 6 课：独立调优实战](docs/课程/第06课-独立调优实战.md)的三阶段首轮闭环已完成，既有课程的进度和待补实验见课程计划。下方是现有实验工具的使用参考，实际学习顺序以课程计划为准。

先测量，再定位，再改变一个因素，最后在相同条件下验证。这套项目带你走完这个循环。

纯 Java，无第三方依赖，不需要 Maven。四种单项实验均有 `bad` 与 `fixed` 版本：临时对象分配、缓存持续增长、重复计算、锁竞争；另有第 6 课的报表综合场景 `report`，按证据逐项排查与优化。

仓库保存代码、脚本、课程讲义和实测结果分析。`artifacts/`、`build/`、堆快照及 IDE 文件不提交；文档中指向这些原始产物的链接用于本地查看，克隆仓库后需自行运行实验生成文件。

## 现有实验工具使用参考

在项目目录打开 PowerShell：

```powershell
Set-Location 'D:\Files\Documents\ChatGPT\JVM调优学习'
```

旧实验曾使用 `J:\JDK\jdk23`。新课程开始前会重新确认实际 JDK 路径和版本；以下仅为旧路径的配置示例，使用前确认它仍然有效：

```powershell
$LabJdkArgs = @{ JdkHome = 'J:\JDK\jdk23'; AllowOtherJdk = $true }
```

这会显式启用兼容性运行：源代码仍编译为 Java 21 字节码，但 GC/JIT 的实际行为属于 JDK 23。要做正式的 JDK 21 实验，安装完整 JDK 21 后，改成自己的实际目录：

```powershell
# 示例路径；替换为你实际安装的 JDK 21 目录。
$LabJdkArgs = @{ JdkHome = 'C:\Java\jdk-21' }
```

先检查代码，再依次运行问题版与改进版：

```powershell
.\scripts\verify.ps1 @LabJdkArgs
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode fixed
```

每次运行默认 5 秒预热、30 秒测量、4 个工作线程、128 MiB 堆；运行结束自动退出。`leak` 默认不预热。运行时会显示 PID，供第二个 PowerShell 窗口连接诊断。

观察最后的 `RESULT`：

- `throughput`：每秒完成的任务数；其他条件一致时，通常越高越好。
- `p95`、`p99`：任务耗时分位数的估计上界；通常越低越好。
- `workerAlloc`：工作线程每秒分配的内存，**不是内存泄漏量**。
- `gcCount`、`gcTime`：测量期 MXBean 的累计差值，用来辅助判断。
- `cpuCores`：进程 CPU 时间 / 墙钟时间，`2.0` 约等于平均用满两个核。
- `retained`：缓存仍保留的数组有效载荷，和整个 Java 堆占用不同。

每次结果保存在独立的 `artifacts/<运行标识>/` 下，包含 `summary.csv`、`metrics.csv`、`gc.log`、`console.log` 和记录启动参数的 `run.json`。指定 `-Recording` 时还生成 `recording.jfr`。GC 日志最多三个轮转归档加当前文件，每个约 10 MiB；JFR 使用 64 MiB 的近似保留预算。

JFR 工作数据默认使用系统临时目录中的独立 ASCII 子目录，最终记录仍保存在项目中。脚本会检查录制非空且可解析。本机 JDK 23 在中文 JFR 工作目录下曾实测产生空文件，因此需要自定义时使用例如 `-JfrRepositoryRoot 'C:\Temp\jfr'`，替换成自己有权限的纯英文路径。临时目录不可写时也用此参数指定其他有权限的目录。

复制终端显示的两个 `summary.csv` 完整路径进行比较：

```powershell
# 将路径替换为刚才两次运行实际输出的文件。
.\scripts\compare.ps1 -Before 'D:\完整路径\bad\summary.csv' -After 'D:\完整路径\fixed\summary.csv'
```

如果 PowerShell 提示禁止运行脚本，可仅在当前终端启用：`Set-ExecutionPolicy -Scope Process Bypass`。这不会修改机器级策略；也可使用下方直接 Java 命令。

脚本兼容 Windows PowerShell 5.1 与 PowerShell 7，脚本源码使用 ASCII 文本避免旧版按 GBK 读入 UTF-8 中文注释的问题。完成提示只有在确认结果文件存在且汇总有效后才输出。如果仅有 `Artifacts` 和 `Finished` 两行，却没有 `PID` / `RESULT`，请使用更新后的脚本重新运行；旧版本可能根本没有启动 Java，也就不会有 CSV。

## 按新课程学习

1. 按 [课程计划](JVM调优课程计划.md) 从第 1 课开始，每课完成“问题代码 → 观测 → 原因与优化 → 对比验证”。
2. 在助手引导下阅读和运行本课代码，再结合实际输出分析；观测前不必先阅读 `fixed` 分支。
3. 使用 [实验记录模板](docs/实验记录模板.md) 留下假设、证据和结果，将本课记录追加到 [JVM调优实验记录.md](JVM调优实验记录.md)。
4. 完成一课后更新课程计划中的进度，再进入下一课。
5. [实操教程](docs/JVM调优实操教程.md) 作为工具与示例参考；[工具验证记录](docs/验证记录.md) 单独记录工具自检，不作为课程完成依据。

## 常用运行方式

```powershell
# 缓存增长；适合在运行期间观察堆和对象。
.\scripts\run.ps1 @LabJdkArgs -Scenario leak -Mode bad -Seconds 60
.\scripts\run.ps1 @LabJdkArgs -Scenario leak -Mode fixed -Seconds 60

# CPU 热点；JFR 采样。
.\scripts\run.ps1 @LabJdkArgs -Scenario cpu -Mode bad -Recording
.\scripts\run.ps1 @LabJdkArgs -Scenario cpu -Mode fixed -Recording

# 锁竞争；在第二个终端抓线程栈。
.\scripts\run.ps1 @LabJdkArgs -Scenario lock -Mode bad -Recording
.\scripts\run.ps1 @LabJdkArgs -Scenario lock -Mode fixed -Recording

# 参数实验：保持 bad 代码，仅增大堆；和默认 bad 结果对比。
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad -HeapMiB 256

# G1 暂停目标实验：保持堆和代码一致。
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad -MaxGCPauseMillis 50

# 进阶：JDK 21 上的分代 ZGC，保留足够堆空间。
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad -HeapMiB 256 -Collector GenerationalZGC
```

实验故意放大差异，不能据此推断某个收集器在真实业务中必然更快。固定并发闭环负载没有模拟外部到达率或请求队列，P99 不是 HTTP 响应时间。比较正式结果时至少运行三轮，保持机器负载、JDK、并发、预热、时长和观测配置一致。

## 不用脚本也能运行

下面示例在项目根目录执行，`$Jdk` 替换为实际 JDK 21 路径：

```powershell
$Jdk = 'C:\Java\jdk-21'
New-Item -ItemType Directory -Path build/classes -Force | Out-Null
$sources = @(Get-ChildItem src/main/java -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$Jdk/bin/javac.exe" --release 21 -encoding UTF-8 -classpath build/classes -d build/classes @sources
& "$Jdk/bin/java.exe" -Xms128m -Xmx128m -XX:+UseG1GC -cp build/classes lab.JvmTuningLab --scenario allocation --mode bad --warmup 5 --seconds 30 --output-dir artifacts/manual-bad
& "$Jdk/bin/java.exe" -Xms128m -Xmx128m -XX:+UseG1GC -cp build/classes lab.JvmTuningLab --scenario allocation --mode fixed --warmup 5 --seconds 30 --output-dir artifacts/manual-fixed
```

直接 Java 命令会使用指定的输出目录；再次使用同一目录会覆盖 CSV。因此需要保留结果时请换一个目录。脚本会自动生成唯一目录。
