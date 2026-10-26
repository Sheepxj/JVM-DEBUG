package lab;

/** 每个线程独占；32 个子桶/二进制量级，返回分位数所在桶的上界。 */
final class LatencyHistogram {
    private static final int SUB_BUCKETS = 32;
    private final long[] counts = new long[64 * SUB_BUCKETS];
    private long total;

    void record(long nanos) {
        long value = Math.max(1, nanos);
        int power = 63 - Long.numberOfLeadingZeros(value);
        long base = 1L << power;
        int sub = (int) (((double) value / base - 1) * SUB_BUCKETS);
        counts[power * SUB_BUCKETS + Math.min(31, sub)]++;
        total++;
    }

    void merge(LatencyHistogram other) {
        for (int i = 0; i < counts.length; i++) counts[i] += other.counts[i];
        total += other.total;
    }

    double percentileMicros(double quantile) {
        if (quantile <= 0 || quantile > 1) throw new IllegalArgumentException("quantile must be (0, 1]");
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * quantile);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= target) {
                int power = i / SUB_BUCKETS;
                int sub = i % SUB_BUCKETS;
                return Math.scalb(1.0, power) * (1 + (sub + 1.0) / SUB_BUCKETS) / 1000;
            }
        }
        throw new IllegalStateException("Histogram count mismatch");
    }

    long count() { return total; }
}
