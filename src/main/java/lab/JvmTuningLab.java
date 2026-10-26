package lab;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 入口：同一个实验中一次只修改一个因素。 */
public final class JvmTuningLab {
    private static final double MIB = 1024.0 * 1024;
    private static final com.sun.management.ThreadMXBean THREADS = threadBean();
    private static final com.sun.management.OperatingSystemMXBean OS = osBean();
    private static volatile long consumedResult;

    private JvmTuningLab() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || List.of(args).contains("--help")) {
            System.out.println("JvmTuningLab --scenario allocation|leak|cpu|lock|report --mode bad|fixed|cpu-fixed "
                    + "[--seconds 30] [--warmup 5] [--threads 4] [--retained-limit-mb 64] "
                    + "[--cache-limit-mb 8] [--output-dir artifacts/manual]");
            System.out.println("cpu-fixed mode is only available for the report scenario.");
            return;
        }
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid arguments: " + e.getMessage());
            System.exit(2);
            return;
        }
        run(config);
    }

    static void run(Config config) throws Exception {
        Files.createDirectories(config.outputDir);
        Workloads.Workload workload = Workloads.create(config.scenario, config.mode,
                config.retainedLimitMiB, config.cacheLimitMiB);
        List<WorkerState> states = new ArrayList<>();
        for (int i = 0; i < config.threads; i++) states.add(new WorkerState(workload.newWorker()));
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(config.threads,
                task -> new Thread(task, "lab-worker-" + threadNumber.incrementAndGet()));
        System.out.printf(Locale.ROOT,
                "PID=%d java=%s scenario=%s mode=%s threads=%d maxHeap=%.0fMiB%n",
                ProcessHandle.current().pid(), System.getProperty("java.version"), config.scenario,
                config.mode, config.threads, Runtime.getRuntime().maxMemory() / MIB);
        System.out.println("Fixed-concurrency CLOSED LOOP. Task latency includes lock/park wait; excludes external queue/network.");
        try {
            if (config.warmup > 0) {
                System.out.println("Warmup: " + config.warmup + "s (not counted in summary)");
                phase(pool, states, config.warmup, workload, null);
            }
            System.out.println("Measurement: " + config.seconds + "s");
            try (BufferedWriter metrics = Files.newBufferedWriter(
                    config.outputDir.resolve("metrics.csv"), StandardCharsets.UTF_8)) {
                metrics.write("elapsed_s,completed_ops,interval_ops_per_s,heap_used_mib,retained_payload_mib,gc_count_delta,gc_time_ms_delta\n");
                Result result = phase(pool, states, config.seconds, workload, metrics);
                writeSummary(config, result, workload);
                System.out.printf(Locale.ROOT,
                        "RESULT ops=%d throughput=%.2f/s p95=%.3fus p99=%.3fus workerAlloc=%.3fMiB/s gcCount=%d gcTime=%dms cpuCores=%.3f retained=%.2fMiB capped=%s%n",
                        result.operations, result.operations / result.elapsedSeconds,
                        result.histogram.percentileMicros(.95), result.histogram.percentileMicros(.99),
                        result.allocatedBytes < 0 ? -1 : result.allocatedBytes / MIB / result.elapsedSeconds,
                        result.gcCount, result.gcMillis, result.cpuCores, workload.retainedBytes() / MIB,
                        workload.retentionCapped());
                System.out.println("Summary: " + config.outputDir.resolve("summary.csv").toAbsolutePath());
            }
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workers did not stop");
            }
        }
    }

    private static Result phase(ExecutorService pool, List<WorkerState> states, int seconds,
                                Workloads.Workload workload, BufferedWriter metrics) throws Exception {
        CountDownLatch ready = new CountDownLatch(states.size());
        CountDownLatch start = new CountDownLatch(1);
        long[] deadline = new long[1]; // 通过 start latch 安全发布。
        List<Future<WorkerResult>> futures = new ArrayList<>();
        for (WorkerState state : states) {
            state.completed = 0;
            futures.add(pool.submit(() -> {
                LatencyHistogram histogram = new LatencyHistogram();
                ready.countDown();
                start.await();
                long beforeAllocation = allocatedBytes();
                long checksum = 0;
                long operations = 0;
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline[0]) {
                    long before = System.nanoTime();
                    checksum ^= state.worker.execute(state.sequence++);
                    histogram.record(System.nanoTime() - before);
                    state.completed = ++operations;
                }
                long afterAllocation = allocatedBytes();
                consumedResult = checksum; // 消费计算结果，避免无效计算被优化掉。
                return new WorkerResult(operations, beforeAllocation < 0 || afterAllocation < 0
                        ? -1 : afterAllocation - beforeAllocation, histogram);
            }));
        }
        ready.await();
        long gcBefore = gcCount();
        long gcMsBefore = gcMillis();
        long cpuBefore = processCpuNanos();
        long started = System.nanoTime();
        deadline[0] = started + TimeUnit.SECONDS.toNanos(seconds);
        start.countDown();
        long previousOps = 0;
        long previousTime = started;
        long nextReport = started + TimeUnit.SECONDS.toNanos(1);
        while (futures.stream().anyMatch(f -> !f.isDone())) {
            // 失败时立即传播，而不是把部分工作线程的结果当成正常数据。
            for (Future<WorkerResult> future : futures) if (future.isDone()) future.get();
            long now = System.nanoTime();
            if (metrics != null && now >= nextReport) {
                long operations = states.stream().mapToLong(s -> s.completed).sum();
                double throughput = (operations - previousOps) * 1e9 / (now - previousTime);
                double heapMiB = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / MIB;
                metrics.write(String.format(Locale.ROOT, "%.3f,%d,%.2f,%.3f,%.3f,%d,%d%n",
                        (now - started) / 1e9, operations, throughput, heapMiB,
                        workload.retainedBytes() / MIB, gcCount() - gcBefore, gcMillis() - gcMsBefore));
                metrics.flush();
                System.out.printf(Locale.ROOT, "t=%.1fs ops/s=%.0f heap=%.1fMiB retained=%.1fMiB gc=%d%n",
                        (now - started) / 1e9, throughput, heapMiB, workload.retainedBytes() / MIB, gcCount() - gcBefore);
                previousOps = operations;
                previousTime = now;
                nextReport = now + TimeUnit.SECONDS.toNanos(1);
            }
            Thread.sleep(20);
        }
        long finished = System.nanoTime();
        long cpuAfter = processCpuNanos();
        long countAfter = gcCount();
        long gcMsAfter = gcMillis();
        long operations = 0;
        long allocated = 0;
        boolean allocationAvailable = true;
        LatencyHistogram combined = new LatencyHistogram();
        for (Future<WorkerResult> future : futures) {
            WorkerResult result = future.get();
            operations += result.operations;
            if (result.allocatedBytes < 0) allocationAvailable = false;
            else allocated += result.allocatedBytes;
            combined.merge(result.histogram);
        }
        double elapsed = (finished - started) / 1e9;
        return new Result(operations, elapsed, allocationAvailable ? allocated : -1,
                gcBefore < 0 || countAfter < 0 ? -1 : countAfter - gcBefore,
                gcMsBefore < 0 || gcMsAfter < 0 ? -1 : gcMsAfter - gcMsBefore,
                cpuBefore < 0 || cpuAfter < 0 ? -1 : (cpuAfter - cpuBefore) / 1e9 / elapsed, combined);
    }

    private static void writeSummary(Config config, Result result, Workloads.Workload workload) throws IOException {
        String header = "scenario,mode,java_version,threads,warmup_s,requested_s,elapsed_s,operations,ops_per_s,"
                + "p95_us,p99_us,worker_alloc_mib_s,worker_alloc_bytes_per_op,gc_count,gc_time_ms,"
                + "process_cpu_cores,heap_used_mib,max_heap_mib,retained_payload_mib,retention_capped,"
                + "retained_limit_mib,cache_limit_mib\n";
        double allocRate = result.allocatedBytes < 0 ? -1 : result.allocatedBytes / MIB / result.elapsedSeconds;
        double bytesPerOp = result.allocatedBytes < 0 || result.operations == 0 ? -1
                : (double) result.allocatedBytes / result.operations;
        String row = String.format(Locale.ROOT,
                "%s,%s,%s,%d,%d,%d,%.6f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.6f,%.3f,%.3f,%.3f,%s,%d,%d%n",
                config.scenario, config.mode, System.getProperty("java.version"), config.threads,
                config.warmup, config.seconds, result.elapsedSeconds, result.operations,
                result.operations / result.elapsedSeconds, result.histogram.percentileMicros(.95),
                result.histogram.percentileMicros(.99), allocRate, bytesPerOp, result.gcCount, result.gcMillis,
                result.cpuCores, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / MIB,
                Runtime.getRuntime().maxMemory() / MIB, workload.retainedBytes() / MIB,
                workload.retentionCapped(), config.retainedLimitMiB, config.cacheLimitMiB);
        Files.writeString(config.outputDir.resolve("summary.csv"), header + row, StandardCharsets.UTF_8);
    }

    private static com.sun.management.ThreadMXBean threadBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean extended && extended.isThreadAllocatedMemorySupported()) {
            if (!extended.isThreadAllocatedMemoryEnabled()) extended.setThreadAllocatedMemoryEnabled(true);
            return extended;
        }
        return null;
    }

    private static com.sun.management.OperatingSystemMXBean osBean() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended : null;
    }

    private static long allocatedBytes() {
        return THREADS == null ? -1 : THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    private static long processCpuNanos() { return OS == null ? -1 : OS.getProcessCpuTime(); }
    private static long gcCount() { return gcSum(false); }
    private static long gcMillis() { return gcSum(true); }
    private static long gcSum(boolean time) {
        long sum = 0;
        boolean available = false;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = time ? bean.getCollectionTime() : bean.getCollectionCount();
            if (value >= 0) { sum += value; available = true; }
        }
        return available ? sum : -1;
    }

    static record Config(String scenario, String mode, int seconds, int warmup, int threads,
                         int retainedLimitMiB, int cacheLimitMiB, Path outputDir) {
        static Config parse(String[] args) {
            if (args.length % 2 != 0) throw new IllegalArgumentException("Use --key value pairs");
            Set<String> allowed = Set.of("scenario", "mode", "seconds", "warmup", "threads",
                    "retained-limit-mb", "cache-limit-mb", "output-dir");
            Map<String, String> options = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --key: " + args[i]);
                String key = args[i].substring(2);
                if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown option: " + key);
                if (options.put(key, args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option: " + key);
            }
            String scenario = options.getOrDefault("scenario", "allocation");
            String mode = options.getOrDefault("mode", "bad");
            if (!Set.of("allocation", "leak", "cpu", "lock", "report").contains(scenario)) throw new IllegalArgumentException("Invalid scenario");
            if (!Set.of("bad", "fixed", "cpu-fixed").contains(mode)) throw new IllegalArgumentException("Invalid mode");
            if (mode.equals("cpu-fixed") && !scenario.equals("report")) {
                throw new IllegalArgumentException("cpu-fixed mode requires the report scenario");
            }
            return new Config(scenario, mode, integer(options, "seconds", 30, 1, 300),
                    integer(options, "warmup", scenario.equals("leak") ? 0 : 5, 0, 60),
                    integer(options, "threads", 4, 1, 32),
                    integer(options, "retained-limit-mb", 64, 1, 256),
                    integer(options, "cache-limit-mb", 8, 1, 256),
                    Path.of(options.getOrDefault("output-dir", "artifacts/manual")));
        }

        private static int integer(Map<String, String> options, String key, int defaultValue, int min, int max) {
            int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(defaultValue)));
            if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
            return value;
        }
    }

    private static final class WorkerState {
        private final Workloads.Worker worker;
        private long sequence;
        private volatile long completed;
        WorkerState(Workloads.Worker worker) { this.worker = worker; }
    }
    private record WorkerResult(long operations, long allocatedBytes, LatencyHistogram histogram) {}
    private record Result(long operations, double elapsedSeconds, long allocatedBytes,
                          long gcCount, long gcMillis, double cpuCores, LatencyHistogram histogram) {}
}
