package lab;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/** 单项实验与综合任务；各场景保留业务结果一致的 bad/fixed 分支。 */
final class Workloads {
    interface Worker {
        long execute(long sequence);
    }

    interface Workload {
        Worker newWorker();
        default long retainedBytes() { return 0; }
        default boolean retentionCapped() { return false; }
    }

    static Workload create(String scenario, String mode, int retainedLimitMiB, int cacheLimitMiB) {
        boolean fixed = mode.equals("fixed");
        return switch (scenario) {
            case "allocation" -> new Allocation(fixed);
            case "leak" -> new RetainedCache(fixed, retainedLimitMiB, cacheLimitMiB);
            case "cpu" -> new Cpu(fixed);
            case "lock" -> new LockContention(fixed);
            case "report" -> new Report(fixed || mode.equals("cpu-fixed"), fixed);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
    }

    static final class Report implements Workload {
        private final Cpu calculation;
        private final Allocation rendering;

        Report(boolean precompute, boolean reuseBuffers) {
            calculation = new Cpu(precompute);
            rendering = new Allocation(reuseBuffers);
        }

        public Worker newWorker() {
            Worker calculator = calculation.newWorker();
            Worker renderer = rendering.newWorker();
            return sequence -> {
                long value = calculator.execute(sequence);
                long contentDigest = renderer.execute(sequence);
                return value ^ contentDigest;
            };
        }
    }

    static final class Allocation implements Workload {
        private final boolean fixed;
        Allocation(boolean fixed) { this.fixed = fixed; }
        public Worker newWorker() { return new AllocationWorker(fixed); }
    }

    static final class AllocationWorker implements Worker {
        private static final int SIZE = 32 * 1024;
        private final boolean fixed;
        private final byte[] reusable;
        // volatile 发布使数组逃逸，避免实验中的分配被 JIT 消除。
        private volatile byte[] published;

        AllocationWorker(boolean fixed) {
            this.fixed = fixed;
            this.reusable = fixed ? new byte[SIZE] : null;
        }

        public long execute(long sequence) {
            long checksum = 0;
            for (int i = 0; i < 8; i++) {
                // bad: 一次任务分配约 256 KiB；fixed: 每线程复用一块缓冲区。
                byte[] buffer = fixed ? reusable : new byte[SIZE];
                Arrays.fill(buffer, (byte) (sequence + i));
                published = buffer;
                checksum += buffer[(int) ((sequence + i) & (SIZE - 1))];
            }
            return checksum;
        }
    }

    static final class RetainedCache implements Workload {
        private static final int ENTRY_BYTES = 16 * 1024;
        private final ArrayDeque<byte[]> cache = new ArrayDeque<>();
        private final boolean fixed;
        private final int limitEntries;
        private volatile long retained;
        private volatile boolean capped;

        RetainedCache(boolean fixed, int retainedLimitMiB, int cacheLimitMiB) {
            this.fixed = fixed;
            int limitMiB = fixed ? cacheLimitMiB : retainedLimitMiB;
            this.limitEntries = limitMiB * 1024 * 1024 / ENTRY_BYTES;
        }

        public Worker newWorker() {
            return sequence -> {
                // 模拟四线程合计约 200 次任务/秒；避免瞬间吃满实验堆。
                LockSupport.parkNanos(20_000_000);
                return insert(sequence);
            };
        }

        synchronized long insert(long sequence) {
            if (!fixed && cache.size() >= limitEntries) {
                // 仅为教学保护：真实无界缓存没有这个上限。
                capped = true;
                return (byte) sequence;
            }
            byte[] value = new byte[ENTRY_BYTES];
            value[0] = (byte) sequence;
            value[value.length - 1] = (byte) ~sequence;
            if (fixed && cache.size() == limitEntries) {
                cache.removeFirst(); // 淘汰最早的条目；生产系统还需明确 TTL/一致性。
            }
            cache.addLast(value);
            retained = (long) cache.size() * ENTRY_BYTES;
            return value[0];
        }

        public long retainedBytes() { return retained; }
        public boolean retentionCapped() { return capped; }
    }

    static final class Cpu implements Workload {
        private final boolean fixed;
        private final long[] precomputed;

        Cpu(boolean fixed) {
            this.fixed = fixed;
            precomputed = fixed ? new long[256] : null;
            if (fixed) {
                for (int key = 0; key < precomputed.length; key++) {
                    precomputed[key] = expensiveScore(key);
                }
            }
        }

        public Worker newWorker() {
            // 业务前提：输入只有 256 个值，映射在整个实验期间不变。
            return sequence -> {
                int key = (int) (sequence & 255);
                return fixed ? precomputed[key] : expensiveScore(key);
            };
        }

        static long expensiveScore(int key) {
            long value = key + 0x9E3779B97F4A7C15L;
            for (int i = 0; i < 20_000; i++) {
                value = Long.rotateLeft(value ^ (i * 0x9E3779B97F4A7C15L), 13);
                value = value * 0xBF58476D1CE4E5B9L + key;
            }
            return value;
        }
    }

    static final class LockContention implements Workload {
        private final Object gate = new Object();
        private final boolean fixed;
        private long sharedTotal;

        LockContention(boolean fixed) { this.fixed = fixed; }

        public Worker newWorker() {
            return sequence -> {
                if (fixed) {
                    simulatedSlowOperation();
                    synchronized (gate) { sharedTotal += sequence; }
                } else {
                    synchronized (gate) {
                        // bad: 持锁执行模拟 I/O；其他工作线程只能等。
                        simulatedSlowOperation();
                        sharedTotal += sequence;
                    }
                }
                return sequence;
            };
        }

        private static void simulatedSlowOperation() {
            LockSupport.parkNanos(4_000_000);
        }

        long totalForTest() {
            synchronized (gate) { return sharedTotal; }
        }
    }
}
