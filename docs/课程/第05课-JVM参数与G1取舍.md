# 第 5 课：什么时候应该调整 JVM 参数？

开始日期：2026-10-25。状态：学习中，A/B/C 三组首轮对比与分析已完成，多轮复测待补充。

学习主线见 [课程计划](../../JVM调优课程计划.md)。本课复用第 2 课的临时对象分配程序，先保持代码不变，观察堆容量和 G1 暂停目标的影响，再与减少分配的思路对照。

## 1. 问题代码与业务目标

每个任务处理 8 个 32 KiB 缓冲区。问题版每次重新创建数组，处理后不长期保存。对应 [Workloads.java](../../src/main/java/lab/Workloads.java) 中的 `AllocationWorker`，bad 分支核心等价于：

```java
private volatile byte[] published;

public long execute(long sequence) {
    long checksum = 0;
    for (int i = 0; i < 8; i++) {
        byte[] buffer = new byte[32 * 1024];
        Arrays.fill(buffer, (byte) (sequence + i));
        published = buffer;
        checksum += buffer[(int) ((sequence + i) & (buffer.length - 1))];
    }
    return checksum;
}
```

这是已有实验框架中的代码片段，直接通过下方脚本运行，不需要新建 Java 文件。`published` 只指向最新数组，旧数组陆续失去引用；这是短命对象的分配压力，不是缓存持续保留问题。

假设暂时不能修改业务代码，需要判断：能否在内存预算内通过配置改善吞吐或尾延迟？调整后是否只是改变了回收节奏？本课不设虚构的生产 SLA，先收集本机数据，再讨论收益与代价。

## 2. 本课只学习三个参数

| 参数 | 含义 | 本课用法 |
|---|---|---|
| `-Xms` | 初始堆大小，也约束堆缩小的下限 | 与最大堆设置相同，减少运行时扩缩容的影响 |
| `-Xmx` | Java 堆最大容量 | 比较固定 128 MiB 与固定 256 MiB 堆 |
| `-XX:MaxGCPauseMillis` | G1 尝试满足的 GC 暂停时间目标，单位毫秒 | 比较默认目标与 1 ms 的激进实验目标 |

脚本的 `-HeapMiB 128` 实际同时设置 `-Xms128m -Xmx128m`。因此本课变化的是固定堆容量，不声称单独验证了只修改 `-Xmx` 的效果。堆容量也不是进程总内存；元空间、线程栈、直接内存等还会占用空间。

本机 Temurin 21.0.12.1+1、G1 的默认 `MaxGCPauseMillis` 已用 `PrintFlagsFinal` 确认是 **200**。脚本未传 `-MaxGCPauseMillis` 时不添加该 JVM 参数，沿用 JVM 默认值；脚本参数值 0 表示省略，不代表 JVM 的暂停目标为 0 ms。

暂停目标不是硬上限，也不是接口 P99 的目标。G1 根据历史成本预测调整回收工作量，年轻代大小是其重要调节手段；不要同时添加 `-Xmn` 或固定新生代比例，干扰本课比较。见 [G1 调优文档](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector-tuning.html) 与 [G1 原理及目标限制](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)。

## 3. 三组实验设计

所有组保持 Temurin 21、G1、allocation/bad、4 个工作线程、5 秒预热、60 秒测量，均使用脚本自带 GC 日志，不开启 JFR。

| 组别 | 固定堆 | 暂停目标 | 与谁比较、回答什么问题 |
|---|---:|---:|---|
| A：基线 | 128 MiB | 默认 200 ms | 当前分配与回收情况如何？ |
| B：增大堆 | 256 MiB | 默认 200 ms | 与 A 比较：更多内存换来了什么？ |
| C：收紧暂停目标 | 128 MiB | 1 ms | 与 A 比较：更严格的目标改变了什么？ |

**先做 A，反馈证据后再做 B/C。** 1 ms 是用于观察调节边界的教学设置，不作为生产建议。若没有明显变化，也需要解释是否目标未构成约束、存在波动或受到其他成本限制，不能预设每次调参都会有效。

第 2 课的数据保留作背景；本课重新建立相近时间、相同观测条件的基线，不将不同日期的结果直接拼成稳定收益。正式结论各组至少三轮，交替运行，记录背景负载。

## 4. 窗口 A：运行基线 A

```powershell
Set-Location 'D:\Files\Documents\ChatGPT\JVM调优学习'
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'
.\scripts\run.ps1 -JdkHome $Jdk -Scenario allocation -Mode bad -Threads 4 -HeapMiB 128 -WarmupSeconds 5 -Seconds 60 -Collector G1
```

记下 `Artifacts: ...` 与 `PID=...`。这是固定并发闭环任务，不模拟外部到达率、网络或请求队列；任务 P99 不能直接当作真实接口 P99。

## 5. 窗口 B：运行期间观测

替换为本轮的实际 PID 和输出目录，进程退出后不能再附加。

```powershell
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'
$JavaPid = 12345  # 替换为窗口 A 输出的 PID
$Run = '这里替换为本次 Artifacts 完整路径'

# 核实实际生效参数，包括没有显式传入的默认值。
& "$Jdk\bin\jcmd.exe" $JavaPid VM.flags -all |
    Set-Content -LiteralPath "$Run\flags.txt" -Encoding UTF8
Select-String -LiteralPath "$Run\flags.txt" -Pattern 'InitialHeapSize|MaxHeapSize|MaxGCPauseMillis|UseG1GC'

# 查看当前堆已提交容量和使用量；这是一个时点，不是回收后存活量趋势。
& "$Jdk\bin\jcmd.exe" $JavaPid GC.heap_info

# 1 秒间隔采样 8 次，关注 YGC/YGCT 的增量、老年代占比及 FGC。
& "$Jdk\bin\jstat.exe" -gcutil $JavaPid 1000 8 |
    Tee-Object -FilePath "$Run\jstat.txt"
```

`VM.flags -all` 用法见 [jcmd 文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)。`jstat` 计数从 JVM 启动累计，含预热，不能直接等同于 RESULT 的测量期计数。

## 6. 运行结束后整理证据

继续在窗口 B 中使用本次 `$Run`：

```powershell
Import-Csv -LiteralPath "$Run\summary.csv" |
    Select-Object ops_per_s,p95_us,p99_us,worker_alloc_bytes_per_op,worker_alloc_mib_s,gc_count,gc_time_ms,process_cpu_cores |
    Format-List

Get-ChildItem -LiteralPath $Run -Filter 'gc.log*' -File |
    Select-String -Pattern 'Pause (Young|Full).*->' |
    Select-Object -First 8
```

先反馈 RESULT、Artifacts 路径、生效参数与几条 GC 完成行；运行期间没来得及采样时先保留 RESULT 和日志，不需要伪造采样结果。

分析时区分：

- **每任务分配量**：参数是否改变了代码产生的对象数量？
- **每秒分配量**：同时受每任务分配量和吞吐影响，不能只凭它增加就说退化。
- **GC 次数、单次暂停、累计时间**：次数少不保证单次更短；MXBean 的 `gcTime` 不是 GC 消耗的全部 CPU 时间。
- **业务吞吐、P95/P99**：是否真正改善任务表现？GC 暂停分布和任务延迟分布不是同一回事。
- **堆容量与占用**：区分容量、当前 used、GC 后存活量；本实验不把堆容量当作进程 RSS。

GC 日志包含启动与预热，并可能轮转；上面的八条只是样例，不能用来计算整轮暂停分位数。后续若汇总暂停，应明确日志覆盖范围与时间窗口，再与任务统计对照。

## 7. 基线分析后的对比命令（B、C 均已执行首轮）

B 仅增大固定堆容量：

```powershell
.\scripts\run.ps1 -JdkHome $Jdk -Scenario allocation -Mode bad -Threads 4 -HeapMiB 256 -WarmupSeconds 5 -Seconds 60 -Collector G1
```

C 从 A 的配置出发，仅收紧暂停目标：

```powershell
.\scripts\run.ps1 -JdkHome $Jdk -Scenario allocation -Mode bad -Threads 4 -HeapMiB 128 -WarmupSeconds 5 -Seconds 60 -Collector G1 -MaxGCPauseMillis 1
```

每次使用新输出的 PID/目录，并按第 5、6 节采集同类证据。分别 A→B、A→C 比较，不能把 B→C 解释为只修改暂停目标，因为堆也不同。可用 `scripts/compare.ps1 -Before 'A的summary.csv完整路径' -After 'B或C的summary.csv完整路径'`，还需核对各自 `run.json` 和 `flags.txt`，比较脚本本身不会校验所有 JVM 参数。

B、C 组首轮结果见第 9、10 节。参数不会自动消除任务中的数组创建；最终要结合分配量、回收和业务指标判断何时值得调整配置，何时需要修改代码。

## 8. A 组基线实测（2026-10-25）

本轮路径 `artifacts/20261025-170830-791-allocation-bad-0c1852`。实际配置与 A 组设计一致，学习者 VM.flags 确认暂停目标为默认 200 ms。

- 吞吐 36,150.661 任务/秒，P95 155.648 微秒，P99 1.081344 毫秒。
- 每任务分配 262,272 字节；分配速率约 8.83 GiB/秒；测量期 GC 7,469 次，约 124.43 次/秒，累计统计时间 6,600 ms。
- jstat 局部窗口老年代占比稳定在 3.63%，无 Full GC；八条样例显示 `76M->1M(128M)`，支持短命对象分配压力。
- 八条样例不是完整暂停分布；两份完整日志含启动与预热，全部为 Young，最大暂停为 72.203 ms，不能说所有暂停都在 1 ms 左右。

完整证据和统计窗口边界见 [实验记录](../../JVM调优实验记录.md)。随后完成的 B 组结果如下。

## 9. B 组增大堆实测（2026-10-25）

本轮路径 `artifacts/20261025-172019-540-allocation-bad-dc711d`。已核对与 A 仅固定堆容量不同，实际暂停目标仍为默认 200 ms。

| 指标 | A：128 MiB | B：256 MiB |
|---|---:|---:|
| 吞吐（任务/秒） | 36,150.661 | 39,025.006 |
| P99（微秒） | 1,081.344 | 393.216 |
| 每任务分配（字节） | 262,272 | 262,272 |
| GC 次数 | 7,469 | 3,980 |
| GC 累计统计时间（毫秒） | 6,600 | 3,911 |

本轮吞吐提高约 8.0%、P99 降低约 63.6%、GC 次数降低约 46.7%。B 日志尾部出现 `153M->1M(256M)`，更多堆空间容纳短命对象，回收频率降低。每任务分配没有改变，每秒分配量因处理任务更多反而提高。

这不表示所有暂停都更短：含预热的全日志平均暂停 A/B 约 0.947/1.049 ms，最大分别 72.203/84.123 ms。GC 暂停与任务延迟分布需分别看待。各组只有一轮，不把这些数字当作稳定生产收益。

B 组用户提供了两行 jstat，尚无完整八次采样；详细证据见实验记录。随后回到 128 MiB、仅改变暂停目标的 C 组实测如下。

## 10. C 组暂停目标实测（2026-10-25）

本轮路径 `artifacts/20261025-190106-136-allocation-bad-8a1cbc`，已核对与 A 仅暂停目标不同；VM.flags 确认 `MaxGCPauseMillis=1` 生效。

| 指标 | A：128 MiB / 200 ms | C：128 MiB / 1 ms |
|---|---:|---:|
| 吞吐（任务/秒） | 36,150.661 | 38,158.558 |
| P99（微秒） | 1,081.344 | 1,032.192 |
| 每任务分配（字节） | 262,272 | 262,272 |
| GC 次数 | 7,469 | 7,991 |
| GC 累计统计时间（毫秒） | 6,600 | 6,870 |

本轮吞吐上升 5.6%、业务 P99 降低 4.5%，没有数量级改善；GC 次数增加 7.0%。由于任务总量也增加，按每千任务归一化，GC 次数只从 3.442 增至 3.490，约增加 1.4%，不能把总次数变化全部解释为更严格目标所致。

C 的完整日志含预热，共 8,634 次 Young GC，平均暂停约 0.927 ms、最大 42.155 ms，1,753 次超过 1 ms。这是软目标的实际证据。GC 暂停事件 P99 为 1.733 ms，任务 P99 为 1.032192 ms：两者的样本和时间窗不同，不能混用。

多数回收前占用仍在 76～77 MiB，与 A 接近；现有日志不支持整轮年轻代一直显著缩小的结论。理论上更严格目标可能改变年轻代和回收节奏，但具体效果应以实测与预测日志为依据。

## 11. 面试要点与本轮结论

调 JVM 参数前，先确认业务瓶颈和目标，区分高分配、长期保留与线程等待。在内存预算内设置堆容量，结合 GC 日志逐项验证暂停目标，比较业务吞吐、尾延迟、回收和内存代价，不只看 GC 次数。

本轮 B 的任务 P99 和累计 GC 时间表现更好；C 没有带来业务延迟的数量级变化。三组每任务分配相同，配置调整没有解决创建大量临时数组的来源，代码优化仍值得考虑。

各组只有一轮，A/C 运行相隔近两小时，背景负载未完整记录，不能据此选定生产最优参数。至少三轮交替复测仍待补充。完整配置、日志统计和限制见 [实验记录](../../JVM调优实验记录.md)。
