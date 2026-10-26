# JVM 调优实操教程：从一个现象走到证据与验证

> 从 2026-10-22 起，学习顺序以 [JVM 调优课程计划](../JVM调优课程计划.md) 为准。本文保留为已有代码和工具的参考资料，其中“实验一至四”是场景编号，不是新课程编号；旧实验结果已清理，新课程需重新运行采集数据。

你要掌握的工作方法是：给性能目标一个数字，复现问题，采集证据，提出解释，实施一项改变，再比较结果。可能需要改 JVM 参数，也可能需要改对象分配、缓存或锁的代码。

本教程面向 JDK 21 HotSpot。项目中的实验是有意放大的教学模型，不是通用生产参数模板。

## 1. 先理解几个量

Java 堆用于存储对象。某个对象从线程栈、静态字段等 GC Roots 沿引用链仍可到达，就有可能继续保留。对象是否“业务上还有用”和 GC 是否能回收，是两回事。

年轻对象大量产生又很快失去引用，会形成分配压力；缓存持续保留本应淘汰的对象，会使存活集合增长。前者重点看每个任务分配的字节数，后者重点看完整回收后仍存活的对象和引用链。

`-Xms` 是初始/最小堆，`-Xmx` 是最大堆。本实验将它们设为相同值，减少堆扩容这一变量。进程占用还包含线程栈、元空间、代码缓存、直接内存和 JVM 自身等，因此最大堆不是进程总内存上限。

GC 有暂停阶段，也可能有并发阶段。GC 次数和时间可以提示压力，但不能单独解释业务延迟。锁等待、计算、I/O 和调度都会影响任务时间。

吞吐量是单位时间完成量。P99 表示约 99% 的任务耗时不超过该值，不表示“最慢一次”。一个系统可以吞吐不错，却仍有较差的尾延迟。

## 2. 准备工作和第一次基线

按照 README 设置 `$LabJdkArgs`。以下命令均在项目根目录的同一个 PowerShell 中执行：

```powershell
.\scripts\build.ps1 @LabJdkArgs
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad
```

先保留默认 G1、128 MiB 堆、四线程和 30 秒测量。不要先堆一串调优参数。查看终端的 `RESULT` 和输出目录，记录吞吐、P99、每任务分配字节和 GC 情况。

建议学习目标：在相同并发、堆和运行时间下减少分配，同时提高完成量，并检查 P99 是否变差。这是本练习的目标，不是生产业务的 SLO。

想记录 JFR，就在同一命令加 `-Recording`。比较性能时，两边都开启或都关闭；需要定位热点时，可以另做诊断运行。

## 3. 实验一：大量临时对象导致频繁 GC

打开 `src/main/java/lab/Workloads.java`，找到 `AllocationWorker.execute`：

```java
byte[] buffer = fixed ? reusable : new byte[SIZE];
Arrays.fill(buffer, (byte) (sequence + i));
published = buffer;
```

每次任务执行八轮，每轮缓冲区为 32 KiB，所以 bad 模式每任务的数组有效载荷约 256 KiB，再加数组头等开销。`published` 让分配的数组逃逸，减少 JIT 把实验分配消除的可能。fixed 版本为每个工作线程保留自己的缓冲区；两个版本仍执行相同填充并返回相同结果。

**先找证据。** 在 bad 运行输出中观察 `workerAlloc` 和 `gcCount`。运行后检查日志：

```powershell
# 替换为终端给出的实际目录。
$Run = 'D:\完整路径\本次运行目录'
Select-String -LiteralPath "$Run/gc.log" -Pattern 'Pause Young','Pause Full','Humongous','Evacuation Failure','To-space exhausted'
Import-Csv -LiteralPath "$Run/summary.csv" | Format-List
```

GC 日志中的 `A->B(C)` 通常表示回收前后堆占用与容量。反复明显下降说明有许多可回收对象，不能仅凭锯齿形就认定泄漏。日志可能轮转，必要时也查看 `gc.log.0` 等文件。

**做参数实验。** 保持 bad 代码，只把堆从 128 MiB 改为 256 MiB：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode bad -HeapMiB 256
```

假设：更大的堆可能降低 GC 频率，但每任务分配量仍近似相同，暂停与内存代价需要实测。把这次结果与默认 bad 比较，不能把它和同时改成 fixed 的结果直接当作单因素实验。

**做代码实验。** 恢复默认堆，只改模式：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario allocation -Mode fixed
```

优先比较 `worker_alloc_bytes_per_op`。fixed 完成更多任务，因此“每秒”指标会同时受完成量变化影响；每任务指标更能说明分配是否减少。预期 GC 压力下降，但收益大小与硬件、JIT 和后台负载有关。

复用缓冲区的业务前提：一次调用结束后，没有消费者继续依赖旧内容，而且缓冲区不在工作线程间共享。异步请求或返回数组给调用方时，直接照搬此方案可能破坏正确性。

## 4. 实验二：堆持续增长，先检查是谁保留了对象

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario leak -Mode bad -Seconds 60
```

`RetainedCache` 持有 `ArrayDeque<byte[]>`。bad 模式持续将数组加入缓存。为了让实验可控，默认保留有效载荷最多 64 MiB；到达上限后不再增长，`retention_capped=true`。这是教学保护上限，不是生产泄漏的修复方案。

fixed 模式容量为 8 MiB，满后淘汰旧条目。两个版本每次返回的结果相同，但缓存保留策略不同。这适用于允许淘汰历史数据的缓存；如果业务要求永久保留，则应设计外部存储和生命周期。

运行期间打开第二个 PowerShell，使用**同一 JDK**，填入程序显示的 PID。PowerShell 的 `$PID` 是内置只读变量，下面使用 `$JavaPid`：

```powershell
$Jdk = 'C:\Java\jdk-21'  # 改成实际路径；兼容练习时为 J:\JDK\jdk23。
$JavaPid = 12345          # 改成这次运行显示的 PID。
& "$Jdk/bin/jcmd.exe" $JavaPid VM.version
& "$Jdk/bin/jcmd.exe" $JavaPid VM.command_line
& "$Jdk/bin/jcmd.exe" $JavaPid GC.heap_info
```

在运行前后各取一次对象直方图，比较数组数量和总大小：

```powershell
# 诊断运行单独做；这些操作会干扰性能测量。
& "$Jdk/bin/jcmd.exe" $JavaPid GC.class_histogram
```

`[B` 表示 `byte[]`。数组多只能告诉你对象类型，不能告诉你引用来源。想看到引用链，先将 `$Run` 设置为实际实验目录，再生成 heap dump：

```powershell
# 用没有空格的相对输出名，并切换到目标目录，减少 jcmd 路径转义问题。
Push-Location -LiteralPath $Run
try { & "$Jdk/bin/jcmd.exe" $JavaPid GC.heap_dump heap.hprof }
finally { Pop-Location }
```

在 Eclipse Memory Analyzer（MAT）等堆分析工具中打开 `heap.hprof`，查找 `Workloads$RetainedCache`，沿 `cache` 查看数组；使用 Dominator Tree 和 Path to GC Roots 确认引用来源。这里的典型关系是：运行中的实验对象 → RetainedCache → ArrayDeque → byte[]。对象路径的实际展示以工具结果为准。

`GC.class_histogram` 和 `GC.heap_dump` 都是高影响诊断命令；默认 heap dump 还会请求一次完整 GC。只在本实验或合适的诊断窗口执行，不把这段被打断的结果拿来做公平基准。[JDK 21 jcmd 文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)

然后运行 fixed：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario leak -Mode fixed -Seconds 60
```

观察 `metrics.csv` 的 `retained_payload_mib`：bad 随任务数增长直到保护上限，fixed 到约 8 MiB 后维持上限。Java 堆仍可能波动，因为被淘汰数组要等 GC 才回收。堆使用持续上升是线索，结合存活量和引用链才能判断是否泄漏。

本实验不默认制造 OOM。如果以后排查真实 OOM，可提前配置 `-XX:+HeapDumpOnOutOfMemoryError` 与 `-XX:HeapDumpPath=<目录>`，并核算磁盘空间；这只留下证据，不会解决 OOM。

## 5. 实验三：CPU 热点来自重复计算

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario cpu -Mode bad -Recording
```

将 `$Run` 设置为本次实际输出目录，用同一 JDK 自带的工具读取：

```powershell
$Jdk = $LabJdkArgs.JdkHome
& "$Jdk/bin/jfr.exe" summary "$Run/recording.jfr"
& "$Jdk/bin/jfr.exe" print --events jdk.ExecutionSample --stack-depth 20 "$Run/recording.jfr" |
    Select-Object -First 120
```

在执行采样栈中寻找 `Workloads$Cpu.expensiveScore`。采样出现频率高意味着它可能占用了较多 CPU；一次栈样本不能直接算出该方法耗时。可以用 JDK Mission Control 的方法采样视图进一步看热点。

`jfr summary` 给出事件种类和数量，`jfr print` 可导出具体事件和栈。执行采样、对象分配采样和监视器进入事件适合分别寻找计算、分配和锁等待线索。[JDK 21 jfr 文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html)

代码中只有 256 个输入，计算结果不变。fixed 在准备阶段计算一次，然后查表：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario cpu -Mode fixed -Recording
```

比较吞吐和任务时间。fixed 的进程总 CPU 仍可能较高，因为闭环工作线程会不停接着做下一项任务；完成量变大，并不意味着总 CPU 一定下降。可用 `process_cpu_cores / ops_per_s` 估计每任务 CPU 秒数。

预计算耗时发生在测量之前。本实验研究稳态性能；若业务关注冷启动、输入无限多或结果会变化，就必须把初始化、缓存容量和失效成本纳入设计。

## 6. 实验四：吞吐低，但 CPU 不一定高

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario lock -Mode bad -Recording
```

在运行期间第二个终端抓几次线程栈：

```powershell
& "$Jdk/bin/jcmd.exe" $JavaPid Thread.print -l
# 间隔几秒后手动再执行两次，观察是否反复出现同一个锁。
```

预期有 `lab-worker-*` 线程处于 `BLOCKED`，等待 `LockContention` 中同一个监视器；持锁线程正在 `LockSupport.parkNanos` 模拟慢 I/O。CPU 低也能有性能问题，因为时间花在等待上。

运行后查看监视器事件：

```powershell
& "$Jdk/bin/jfr.exe" print --events jdk.JavaMonitorEnter --stack-depth 20 "$Run/recording.jfr" |
    Select-Object -First 160
```

JFR 会按配置和阈值记录事件，短暂等待不一定出现。没有事件时先检查线程栈与记录设置，不能直接断言没有竞争。需要捕捉更短等待时，可在一次独立诊断运行中创建配置：

```powershell
& "$Jdk/bin/jfr.exe" configure --input "$Jdk/lib/jfr/profile.jfc" --output artifacts/locks.jfc 'jdk.JavaMonitorEnter#threshold=1ms'
```

直接用该配置重新运行一组诊断：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario lock -Mode bad -Recording -JfrSettings artifacts/locks.jfc
```

也可在另一个运行中的实验上动态开始 20 秒录制：`jcmd <实际PID> JFR.start name=diagnostic settings=profile duration=20s filename=diagnostic.jfr`。这里文件名相对于**目标 Java 进程的工作目录**，脚本会把该目录设为本次输出目录。动态录制默认依赖 JVM 的可写临时目录；如果受限，优先使用脚本的 `-Recording` 和 `-JfrRepositoryRoot` 指定实际有权限的 ASCII 工作目录。

fixed 把慢操作移出锁，仅在更新共享状态时持锁：

```powershell
.\scripts\run.ps1 @LabJdkArgs -Scenario lock -Mode fixed -Recording
```

比较吞吐和 P99，并验证线程栈中的竞争减少。Windows 定时器和调度会让 4 ms 的模拟等待实际更长，因此不要期待固定四倍加速。真正改业务锁时，还要确认移出的工作不依赖受锁保护的状态。

## 7. 什么时候调整 JVM 参数

| 参数/选项 | 在实验中的用途 | 要验证的代价 |
|---|---|---|
| `-Xms128m -Xmx128m` | 控制堆容量 | 进程仍有堆外开销 |
| `-XX:+UseG1GC` | G1 基线 | 以本机日志和任务指标判断 |
| `-XX:MaxGCPauseMillis=50` | G1 暂停目标实验 | 不是硬性上限；吞吐可能变化 |
| `-XX:+UseZGC -XX:+ZGenerational` | JDK 21 分代 ZGC 对比 | 并发工作与内存预算 |
| `-XX:+UseParallelGC` | 可选吞吐型收集器对比 | 关注暂停与尾延迟 |
| `-Xlog:gc*,safepoint:...` | 收集 GC 与安全点证据 | 日志本身也有成本 |

G1 调整先从默认配置、堆预算和暂停目标开始。不要随手固定 `-Xmn` 或照抄 `NewRatio`；限制年轻代可能妨碍 G1 的自适应暂停控制。[Oracle G1 调优指南](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector-tuning.html)

JDK 21 的分代 ZGC 需要同时设置 `UseZGC` 与 `ZGenerational`；不要混用其他 JDK 版本的默认值，也不要同时指定多个收集器。[JEP 439：Generational ZGC](https://openjdk.org/jeps/439)

对比收集器时保持相同代码、堆、并发和测量时间，最好使用至少 256 MiB 堆给并发回收一些余量。此教学负载对象寿命单一，不能用于给收集器做普遍排名。

## 8. 如何保证比较可信

先做一组诊断，形成假设；再做独立的对比运行，每次只改一个因素。每个配置至少运行三轮，轮换先后顺序，查看中位数和波动。避开系统更新、其他压测和明显后台 CPU 活动。

预热是为了减少解释执行、JIT 编译和初始化对稳态测量的影响，但固定 5 秒不保证所有场景都稳定。观察结果漂移，必要时同样增加两边的预热。

本程序让固定数量的工作线程完成一个任务后再执行下一个，是闭环负载：变慢时新的任务也会变少。真实服务若以固定外部速率接收请求，会产生排队甚至过载；需要额外的开放负载压测和端到端指标来检验。

直方图每个二进制量级使用 32 个桶，输出所在桶上界，近似误差通常在约 3.125% 内（极短任务另受定时器与采样成本影响）。fixed CPU 查询很快，测到的时间会大量包含计时和记录开销；更细致的微基准应使用 JMH。

GC 的 `gc_time_ms` 是 MXBean 返回的收集时间统计差值，并不等于严格的全部 STW 暂停总和，也不能拿它直接当 GC CPU 时间。要评估单次暂停和暂停分位数，应读取日志或 JFR 的相应事件。

工作线程分配量不包含主线程、监测线程或整个 JVM 的所有分配；JFR `ObjectAllocationSample` 也只是采样。指标不支持时 CSV 写 `-1`，不是零开销。

`retained_payload_mib` 只计算缓存数组内容，不包含数组头、容器和其他对象。测量结束的 `heap_used_mib` 还包含尚未回收的垃圾，不能替代 retained size。预热阶段对象和缓存会保留到测量期；因此 leak 默认禁用预热。

## 9. 独立完成一次排查

练习时先不要看 fixed 分支。任选一个 bad 场景，写下目标和现象，采集两种互相补充的证据，指出源码中的具体原因，再预测改动后的指标方向。运行 fixed 验证预测，解释没有按预期变化的指标。

推荐顺序：第一天跑分配实验并读 GC 日志；第二天观察缓存引用和 heap dump；第三天抓 CPU 采样与线程栈；第四天完成至少三轮单因素对比。完成后，你已经练过一次可复查的 JVM 调优流程。
