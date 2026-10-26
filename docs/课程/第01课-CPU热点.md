# 第 1 课：CPU 很忙，程序为什么还是慢？

开始日期：2026-10-22。状态：学习中，首轮观测、分析与优化验证已完成，待补充复测。

学习主线见 [课程计划](../../JVM调优课程计划.md)。第 1～8 节为问题版运行与观测；获得实际输出后，继续第 9～10 节的原因分析和对比验证。实测详情见 [实验记录](../../JVM调优实验记录.md)。

## 1. 本课要回答的问题

程序忙着工作，但究竟忙在哪个方法？我们能否用运行证据指出它，而不是只凭阅读源码猜测？

本课先掌握三个层次的证据：进程 CPU、线程栈、JFR 方法采样。暂不修改 JVM 参数，也不运行优化版本。

## 2. 业务场景和问题代码

模拟一个评分任务：输入为 0～255 的整数，输出一个 `long` 类型的评分。同一输入在本实验期间对应相同评分。多个工作线程持续获取输入并执行任务。

使用现有 [Workloads.java](../../src/main/java/lab/Workloads.java) 中 `Cpu` 场景的 `bad` 分支。其每次任务的处理逻辑等价于：

```java
long execute(long sequence) {
    int key = (int) (sequence & 255);
    return expensiveScore(key);
}
```

实际调用的计算方法为：

```java
static long expensiveScore(int key) {
    long value = key + 0x9E3779B97F4A7C15L;
    for (int i = 0; i < 20_000; i++) {
        value = Long.rotateLeft(value ^ (i * 0x9E3779B97F4A7C15L), 13);
        value = value * 0xBF58476D1CE4E5B9L + key;
    }
    return value;
}
```

`sequence & 255` 将非负序号映射到 0～255；循环中的数值混合运算用于构造计算负载，不需要记住十六进制常量。实验框架会消费返回值，降低整段计算被当作无用代码消除的可能。

框架负责启动线程、预热、计时和保存结果。本课只需先理解“输入 → 调用评分方法 → 返回结果”。先不阅读 `fixed` 分支。

## 3. 已确认的环境

2026-10-22 检查到以下完整 JDK，包含 `java`、`javac`、`jcmd` 和 `jfr`：

```text
J:\JDK\jdk_21\temurin-21.0.12.1
OpenJDK Temurin 21.0.12.1+1，64 位 HotSpot
```

本课直接使用 JDK 21，不需要旧实验的 `-AllowOtherJdk`。

## 4. 窗口 A：启动问题版

打开 PowerShell，执行：

```powershell
Set-Location 'D:\Files\Documents\ChatGPT\JVM调优学习'
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'

.\scripts\run.ps1 -JdkHome $Jdk -Scenario cpu -Mode bad -Threads 4 -Seconds 120 -Recording
```

脚本自动编译，使用 G1、128 MiB 堆、4 个工作线程，预热 5 秒后测量 120 秒，并录制 JFR。程序结束后自动退出。

如果终端明确提示禁止执行脚本，可仅为当前窗口执行 `Set-ExecutionPolicy -Scope Process Bypass`，再重试。

记录开头显示的 `Artifacts: ...` 完整目录和 `PID=...`。本课所有输出都在这个运行目录；不要把短测目录当作自己的课程数据。

运行期间观察每秒的 `ops/s`：它是该时间段每秒完成的任务数。最后的 `RESULT` 是测量阶段汇总。

## 5. 观察进程 CPU

打开任务管理器的“详细信息”，按本次 PID 找到对应 `java.exe`，观察一段时间的 CPU，而不是只看一个瞬间。

记录 Java 进程 CPU 的大致范围，以及 CPU 性能页面显示的逻辑处理器数量。

本实验只启动 4 个工作线程，不一定把整台电脑用满。任务管理器通常按整机 CPU 容量显示百分比：例如 16 个逻辑处理器上的 4 个忙线程，进程 CPU 可能在约 25% 附近波动。这只是便于理解的近似，不是验收阈值。

程序末尾的 `cpuCores` 则是“进程累计 CPU 时间 / 墙钟时间”，例如约为 4 表示平均消耗约 4 个逻辑核的 CPU 时间，和整机百分比不是同一单位。

此时只能确定“进程在忙”，还不能确定“哪个方法值得优化”。

## 6. 窗口 B：在程序运行时抓线程栈

再打开一个 PowerShell，设置 JDK，并将示例进程号替换为窗口 A 的实际 PID：

```powershell
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'
$JavaPid = 12345  # 替换成窗口 A 显示的 PID

& "$Jdk\bin\jcmd.exe" $JavaPid Thread.print -l
```

使用 `$JavaPid`，因为 PowerShell 的 `$PID` 是内置变量。

间隔约 2～3 秒，手动再执行最后一行两次。程序必须仍在运行；如果已经退出，重新启动问题版并更新 PID。

`jcmd` 将诊断请求发送给指定 Java 进程，`Thread.print` 打印线程栈。[JDK 21 jcmd 官方文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)

重点找 `lab-worker-1` 到 `lab-worker-4`，回答：

1. 工作线程大多是什么状态？
2. 栈中反复出现了哪个业务方法？
3. 多次采集是否有相似表现？

可能看到如下片段，但以你的实际输出为准：

```text
"lab-worker-1" ...
   java.lang.Thread.State: RUNNABLE
        at lab.Workloads$Cpu.expensiveScore(Workloads.java:...)
        ...
```

`lab.Workloads$Cpu` 表示 `Workloads` 的内部类 `Cpu`。调用栈从上往下表示当前执行位置及其调用者，行号可能随源码调整变化。

线程栈是瞬时快照；`RUNNABLE` 包括运行和等待 CPU 调度等情况，单独看到它不能证明该线程占了多少 CPU。结合进程 CPU 和重复栈样本，才能逐步缩小范围。

## 7. 程序结束后：用 JFR 看一段时间的热点

在窗口 B 设置 `$Run`，把下面的占位文字替换成窗口 A 的 `Artifacts:` 完整路径：

```powershell
$Run = '这里替换为本次 Artifacts 的完整路径'

& "$Jdk\bin\jfr.exe" view --width 120 hot-methods "$Run\recording.jfr"
```

`jfr view` 可以把录制文件中的事件聚合成视图。[JDK 21 jfr 官方文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html)

关注 `Method`、`Samples` 和 `Percent`。出现频繁的方法是本次热点的候选；表中的采样占比不等于该方法的精确 CPU 占用率，也不等于它消耗了多少毫秒。

需要查看具体采样栈时，将事件导出到文件：

```powershell
& "$Jdk\bin\jfr.exe" print --events jdk.ExecutionSample --stack-depth 20 "$Run\recording.jfr" |
    Set-Content -LiteralPath "$Run\execution-samples.txt" -Encoding UTF8
```

本次 JFR 包含启动、预热及测量阶段；`RESULT` 只汇总测量阶段。分析时不要把两者当作完全相同的时间窗口。

## 8. 本轮反馈与下一步

请提供：

1. 任务管理器中 Java 进程 CPU 的大致范围，以及逻辑处理器数。
2. 任一次线程栈中一个 `lab-worker-*` 的完整片段，尤其是状态和前几行调用栈。
3. `hot-methods` 表的前几行，以及程序结束时的 `RESULT` 行。

这些信息用于建立本课的正式观测记录。接下来结合实际结果，分析为什么热点出现在这里，再实施优化和对比验证。

即使已定位到热点，也不能仅凭“某方法占比高”认定实现有错；仍需结合业务要求判断哪些工作是必要的。

本实验是固定并发的闭环负载：线程完成一项任务就继续下一项。吞吐是内部任务数，P99 是内部任务耗时的估计上界，不包含网络和外部请求排队。优化结果不能直接当作真实 HTTP 服务的性能承诺。

## 9. 根据本次实测分析原因

学习者的问题版吞吐为 110,045.112 任务/秒、P99 为 73.728 微秒、平均 CPU 核数为 3.867356，测量期未观测到 GC；任务管理器观察约 34%。本机 Windows 与 .NET 报告 12 个可见逻辑处理器，`3.867356 / 12 × 100% ≈ 32.2%`，与任务管理器观察相符，但两个指标不是相同时间窗的精确对照。

线程快照指向 `expensiveScore`；线程累计 CPU 时间约 107.953 秒、存活约 112.34 秒，两者之比约 96.1%，支持这个工作线程长期忙于计算。线程池 Worker 出现在持有同步器列表中，本身不能证明锁竞争。

同次运行的 JFR 热点首位为 `expensiveScore`：31,052 个样本，约占 99.03%。因此有了“进程忙 → 工作线程在算 → 长时间采样集中于评分方法”的证据。

结合业务前提，输入只有 256 种，评分规则在实验期间不变，问题版却对重复输入反复执行相同计算。优化方案是在初始化阶段算好 256 个结果，任务执行时查只读数组。现有 `fixed` 分支已经实现：

```java
// 初始化：只计算一遍全部输入。
for (int key = 0; key < precomputed.length; key++) {
    precomputed[key] = expensiveScore(key);
}

// 每次任务：取出对应结果。
return precomputed[key];
```

数组共有 256 个 `long`，有效载荷为 2,048 字节，另有数组对象头与对齐开销。工作线程启动前完成初始化，后续只读。本方案把重复计算转为有限的预计算和存储。

这依赖输入有限、结果稳定的业务前提；真实业务中输入无限或评分规则变化时，需要重新设计容量与失效策略。初始化成本发生在本实验测量阶段之前，讨论冷启动时必须另行计入。

## 10. 运行优化版，验证预测

执行以下命令，除模式改为 `fixed` 外，其余主动配置与本次问题版保持一致：

```powershell
Set-Location 'D:\Files\Documents\ChatGPT\JVM调优学习'
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'
.\scripts\run.ps1 -JdkHome $Jdk -Scenario cpu -Mode fixed -Threads 4 -Seconds 120 -Recording
```

反馈优化版 `RESULT` 与 `Artifacts:` 路径。优先比较吞吐和 P95/P99，同时计算每任务 CPU 成本：

```text
每任务进程 CPU 微秒 ≈ cpuCores / throughput × 1,000,000
问题版本轮约为 35.14 微秒/任务
```

这是进程 CPU 成本的平均分摊，含其他线程开销，不是请求延迟。预期吞吐上升、每任务 CPU 成本下降，具体程度以实测为准。

优化后总 CPU 仍可能较高：闭环线程会把节省的时间用于处理更多任务。观察到这一现象时，应结合完成量判断，不能单凭总 CPU 未下降否定优化。

本次先完成一轮对比以理解方向；要形成稳定收益结论，按课程约定对两个版本各测至少三轮并记录波动，不把单轮比值当作稳定收益。

## 11. 首轮对比已经说明什么？

已核对学习者两次运行的 JDK、堆、线程数、时长和观测配置：问题版吞吐约 11.00 万任务/秒，优化版约 4,022.12 万任务/秒，为原来的约 365.50 倍。平均 CPU 核数为 3.867356 → 4.008803；每任务分摊进程 CPU 时间约为 35.14 → 0.09967 微秒，成本降低约 99.716%。

优化版 JFR 中评分方法不再主导采样，最多样本转到实验框架的任务测量循环。这支持重复计算已不再占据原先的位置，但不能将采样比例直接当作精确 CPU 时间。

P95 与 P99 同为 `0.102us`，属于直方图分桶和格式化后的结果。操作极短时，需要考虑计时分辨率与框架开销；它不能说明每次纯数组访问恰好耗时 102 纳秒。系统计时接口也不保证纳秒级分辨率，见 [官方说明](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime())。

本课应掌握：

1. 用进程 CPU 确认忙碌程度，用线程栈与 JFR 定位计算热点。
2. 结合输入范围和结果稳定性，判断重复工作是否可以省去。
3. 用相同条件下的完成量和每任务成本验证收益，同时检查业务正确性。
4. 正确解释持续负载下仍然较高的 CPU，以及极短耗时的测量边界。

详细数据和代价记录于 [实验记录](../../JVM调优实验记录.md)。首轮教学实验已走通，尚不将单轮倍数作为稳定结论。

## 12. 补充复测

两个版本各补跑两轮，即与首轮合并后各有至少三轮。可以在同一 PowerShell 中顺序执行：

```powershell
Set-Location 'D:\Files\Documents\ChatGPT\JVM调优学习'
$Jdk = 'J:\JDK\jdk_21\temurin-21.0.12.1'
foreach ($Round in 2..3) {
    foreach ($Mode in @('bad', 'fixed')) {
        Write-Host "Round $Round / $Mode"
        .\scripts\run.ps1 -JdkHome $Jdk -Scenario cpu -Mode $Mode -Threads 4 -Seconds 120 -Recording
    }
}
```

四次运行约需 8～9 分钟，期间尽量保持其他活动一致，不同时启动两份实验。脚本为每次运行生成独立结果目录。保留各次 `RESULT` 和 `Artifacts:` 路径，比较吞吐、每任务 CPU 成本和延迟的中位数及波动，再结束本课。
