package lab;

import java.nio.file.Path;

/** 不依赖 JUnit；检查业务等价与统计边界，不用易波动的性能阈值作为断言。 */
public final class LabChecks {
    public static void main(String[] args) throws InterruptedException {
        for (String scenario : new String[]{"allocation", "cpu", "lock", "report"}) {
            var bad = Workloads.create(scenario, "bad", 2, 1).newWorker();
            String[] modes = scenario.equals("report")
                    ? new String[]{"cpu-fixed", "fixed"} : new String[]{"fixed"};
            for (String mode : modes) {
                var candidate = Workloads.create(scenario, mode, 2, 1).newWorker();
                for (long sequence : new long[]{0, 1, 127, 128, 255, 256, 1023, 32767, 32768}) {
                    check(bad.execute(sequence) == candidate.execute(sequence),
                            scenario + "/" + mode + " result differs");
                }
            }
        }
        var growing = new Workloads.RetainedCache(false, 2, 1);
        var bounded = new Workloads.RetainedCache(true, 2, 1);
        for (int i = 0; i < 200; i++) {
            check(growing.insert(i) == bounded.insert(i), "cache result differs");
        }
        check(growing.retainedBytes() == 2L * 1024 * 1024, "growing cache cap");
        check(growing.retentionCapped(), "cap marker missing");
        check(bounded.retainedBytes() == 1024 * 1024, "bounded cache capacity");
        check(!bounded.retentionCapped(), "bounded cache incorrectly marked as safety capped");
        checkConcurrentLockUpdates(false);
        checkConcurrentLockUpdates(true);

        LatencyHistogram a = new LatencyHistogram();
        check(a.percentileMicros(.99) == 0, "empty histogram");
        for (int i = 1; i <= 100; i++) a.record(i * 1000L);
        double p95 = a.percentileMicros(.95);
        double p99 = a.percentileMicros(.99);
        check(p95 >= 95 && p95 <= 98, "p95 out of bucket range");
        check(p99 >= 99 && p99 <= 103, "p99 out of bucket range");
        LatencyHistogram b = new LatencyHistogram();
        b.record(1_000_000_000);
        a.merge(b);
        check(a.count() == 101, "histogram merge count");
        check(a.percentileMicros(1) >= 1_000_000, "histogram tail lost");

        var config = JvmTuningLab.Config.parse(new String[]{"--scenario", "leak"});
        check(config.warmup() == 0, "leak should not warm up by default");
        check(config.outputDir().equals(Path.of("artifacts/manual")), "default output");
        check(JvmTuningLab.Config.parse(new String[]{"--scenario", "report"}).warmup() == 5,
                "report warmup default");
        rejects(new String[]{"--seconds", "0"});
        rejects(new String[]{"--threads", "33"});
        rejects(new String[]{"--warmup", "-1"});
        rejects(new String[]{"--mode", "unknown"});
        rejects(new String[]{"--scenario", "unknown"});
        rejects(new String[]{"--scenario", "cpu", "--mode", "cpu-fixed"});
        rejects(new String[]{"--seconds"});
        rejects(new String[]{"--seconds", "1", "--seconds", "2"});
        rejects(new String[]{"--unknown", "x"});
        System.out.println("PASS: workload equivalence, cache bounds, concurrent lock updates, histogram, argument validation");
    }

    private static void checkConcurrentLockUpdates(boolean fixed) throws InterruptedException {
        var workload = new Workloads.LockContention(fixed);
        Thread[] workers = new Thread[4];
        for (int i = 0; i < workers.length; i++) {
            var task = workload.newWorker();
            workers[i] = new Thread(() -> {
                for (long sequence = 0; sequence < 16; sequence++) task.execute(sequence);
            });
            workers[i].start();
        }
        for (Thread worker : workers) worker.join();
        check(workload.totalForTest() == 4L * 120, "shared updates lost, fixed=" + fixed);
    }

    private static void rejects(String[] args) {
        try { JvmTuningLab.Config.parse(args); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected argument rejection");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
