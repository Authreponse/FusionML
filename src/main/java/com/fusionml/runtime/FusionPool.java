package com.fusionml.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * 自研工作窃取线程池。专为"模组加载流水线"这类任务形状设计：
 *
 * <ul>
 *   <li><b>本地 LIFO + 窃取 FIFO</b>：工作线程从自己队尾取任务（LIFO，热数据在 L1 里），
 *       窃取者从队首偷（FIFO，偷到的是最早的任务，粒度最大、冲突最少）。</li>
 *   <li><b>调用方参与</b>：主线程调 {@link #parallelFor} 时不干等，自己也去抢块执行，
 *       这样主线程省下的等待时间直接变成吞吐。</li>
 *   <li><b>伪共享隔离</b>：所有计数器按 64 字节（8 个 long）步长铺开，
 *       避免多个 worker 写相邻字段导致缓存行来回弹跳（"伪共享"是并行加载器最常见的隐性减速）。</li>
 *   <li><b>分级退避</b>：自旋 → 让出 → 定时 park，逐级加深，空闲时几乎不吃 CPU，
 *       但一旦有活干能在纳秒级唤醒，兼顾"待机省电"与"响应快"。</li>
 *   <li><b>动态分块</b>：块大小按 CPU 缓存大小与任务数算出，长尾任务靠"块耗尽后再补抢"自动均衡，
 *       不需要预先知道每个任务的耗时。</li>
 *   <li><b>故障隔离</b>：单个模组抛异常不会带崩整池，错误被收集后在阶段边界统一抛出。</li>
 * </ul>
 *
 * <p>不依赖 ForkJoinPool —— 因为 ForkJoinPool 的任务必须满足"可分叉"模型，
 * 而我们的流水线是"大量同质小任务 + 阶段屏障"，用带窃取的共享游标调度器更贴形状、开销更低。
 */
public final class FusionPool implements AutoCloseable {

    /** 计数器步长：8 个 long = 64 字节 = 一条缓存行。 */
    private static final int STRIDE = 8;
    private static final int IDX_TASKS = 0 * STRIDE;
    private static final int IDX_STEALS = 1 * STRIDE;
    private static final int IDX_SPINS = 2 * STRIDE;
    private static final int IDX_PARKS = 3 * STRIDE;
    private static final int IDX_NESTED = 4 * STRIDE;
    private static final int IDX_ERRORS = 5 * STRIDE;
    private static final int IDX_CHUNKS = 6 * STRIDE;
    private static final int COUNTER_SLOTS = 7 * STRIDE + 1;

    private static final int SPIN_LIMIT = 64;
    private static final long PARK_MIN_NANOS = 20_000L;
    private static final long PARK_MAX_NANOS = 2_000_000L;

    private final String name;
    private final Worker[] workers;
    private final ConcurrentLinkedQueue<Runnable> global = new ConcurrentLinkedQueue<>();
    private final AtomicLongArray counters = new AtomicLongArray(COUNTER_SLOTS);
    private final AtomicReference<Throwable> firstError = new AtomicReference<>();
    private final List<Throwable> allErrors = new ArrayList<>();
    private volatile boolean running = true;
    private volatile boolean started;

    private final class Worker extends Thread {
        final ConcurrentLinkedDeque<Runnable> local = new ConcurrentLinkedDeque<>();
        final int index;

        Worker(int index) {
            super(name + "-w" + index);
            this.index = index;
            setDaemon(true);
            // 中等优先级：不要把 Minecraft 渲染/音频线程挤掉，但要高于后台维护线程。
            setPriority(Thread.NORM_PRIORITY + 1);
        }

        /** 用于判断"这个线程是不是本池的工人"，避免跨池误判。 */
        FusionPool ownerPool() {
            return FusionPool.this;
        }

        @Override
        public void run() {
            long idleSpins = 0;
            long parkNanos = PARK_MIN_NANOS;
            while (running) {
                Runnable task = local.pollLast();
                if (task == null) {
                    task = global.poll();
                }
                if (task == null) {
                    task = trySteal();
                }
                if (task != null) {
                    idleSpins = 0;
                    parkNanos = PARK_MIN_NANOS;
                    runSafely(task);
                    continue;
                }
                // 空闲路径：先自旋（保住 L1 状态、延迟最低），再让出，最后定时 park。
                if (idleSpins < SPIN_LIMIT) {
                    counters.incrementAndGet(IDX_SPINS);
                    Thread.onSpinWait();
                } else if (idleSpins < SPIN_LIMIT * 4) {
                    counters.incrementAndGet(IDX_SPINS);
                    Thread.yield();
                } else {
                    counters.incrementAndGet(IDX_PARKS);
                    LockSupport.parkNanos(parkNanos);
                    parkNanos = Math.min(parkNanos * 2, PARK_MAX_NANOS);
                }
                idleSpins++;
            }
        }

        private Runnable trySteal() {
            Worker[] ws = workers;
            int n = ws.length;
            if (n <= 1) {
                return null;
            }
            // 从随机位置开始探测，避免所有窃取者同时冲向同一个受害者。
            int start = (int) ((Thread.currentThread().threadId() * 0x9E3779B1L) >>> 33) % n;
            for (int i = 0; i < n; i++) {
                Worker victim = ws[(start + i) % n];
                if (victim == this || victim.local.isEmpty()) {
                    continue;
                }
                Runnable stolen = victim.local.pollFirst();
                if (stolen != null) {
                    counters.incrementAndGet(IDX_STEALS);
                    return stolen;
                }
            }
            return null;
        }
    }

    private FusionPool(String name, int workerCount) {
        this.name = name;
        this.workers = new Worker[Math.max(1, workerCount)];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(i);
        }
    }

    public static FusionPool create(String name, int workerCount) {
        return new FusionPool(name, workerCount);
    }

    /** 延迟启动：避免"创建时就把线程全拉起来"带来无谓的启动开销。 */
    private void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (this) {
            if (!started) {
                for (Worker w : workers) {
                    w.start();
                }
                started = true;
            }
        }
    }

    public int workers() {
        return workers.length;
    }

    /**
     * 提交一个任务。当前线程若正好是池内 worker，则进本地队列（LIFO，快路径）；
     * 否则进全局队列，等 next 或随机 worker 取走。
     */
    public void execute(Runnable task) {
        ensureStarted();
        Thread t = Thread.currentThread();
        if (t instanceof FusionPool.Worker w && w.ownerPool() == this) {
            w.local.addLast(task);
            return;
        }
        global.add(task);
        // 唤醒可能正在 park 的 worker。
        for (Worker wk : workers) {
            if (wk.getState() == Thread.State.WAITING || wk.getState() == Thread.State.TIMED_WAITING) {
                LockSupport.unpark(wk);
            }
        }
    }

    /**
     * 数据并行循环。把 [from,to) 切成动态块，所有 worker 与调用方共同消费同一个游标。
     *
     * <p>调用方自己也参与执行，所以实际并行度 = workers + 1（调用方）。
     * 从池内 worker 调用时（嵌套）退化为本地串行执行 —— 这样永远不会出现
     * "所有 worker 互相等对方"的死锁，嵌套并行在加载流水线里几乎不存在，退化的代价可以忽略。
     *
     * @param chunkHint 建议块大小；&lt;=0 时由 CPU 缓存大小自动推导
     */
    public void parallelFor(int from, int to, int chunkHint, IntConsumer body) {
        if (to <= from) {
            return;
        }
        int total = to - from;
        if (total == 1) {
            body.accept(from);
            return;
        }
        Thread current = Thread.currentThread();
        boolean nested = current instanceof Worker;
        int chunk = chunkHint > 0 ? chunkHint : defaultChunk(total);
        if (nested) {
            counters.incrementAndGet(IDX_NESTED);
            for (int i = from; i < to; i += chunk) {
                int end = Math.min(to, i + chunk);
                for (int k = i; k < end; k++) {
                    body.accept(k);
                }
            }
            return;
        }
        ensureStarted();
        AtomicInteger cursor = new AtomicInteger(from);
        CountDownLatch done = new CountDownLatch(workers.length);
        for (Worker w : workers) {
            w.local.addLast(() -> {
                try {
                    drain(cursor, to, chunk, body);
                } finally {
                    done.countDown();
                }
            });
        }
        for (Worker w : workers) {
            LockSupport.unpark(w);
        }
        // 调用方参与：这不是"帮忙"，而是把本来会浪费掉的等待时间直接用于计算。
        drain(cursor, to, chunk, body);
        try {
            if (!done.await(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("parallelFor 超时：疑似某个模组任务卡死");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rethrowIfFailed();
    }

    private void drain(AtomicInteger cursor, int to, int chunk, IntConsumer body) {
        for (;;) {
            int start = cursor.getAndAdd(chunk);
            if (start >= to) {
                return;
            }
            int end = Math.min(to, start + chunk);
            counters.incrementAndGet(IDX_CHUNKS);
            counters.addAndGet(IDX_TASKS, end - start);
            for (int i = start; i < end; i++) {
                body.accept(i);
            }
        }
    }

    private int defaultChunk(int total) {
        int perThread = Math.max(1, total / (workers.length * 4));
        return Math.max(1, Math.min(perThread, 256));
    }

    /** 并行跑一批 Callable 并保序收集结果（阶段级并行，例如并行解析 N 个 jar 的元数据）。 */
    public <T> List<T> invokeAll(List<? extends Callable<T>> tasks) {
        int n = tasks.size();
        List<T> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        int chunk = defaultChunk(n);
        parallelFor(0, n, chunk, i -> {
            try {
                results.set(i, tasks.get(i).call());
            } catch (Exception e) {
                recordError(e);
            }
        });
        rethrowIfFailed();
        return results;
    }

    /** 执行单个任务，异常被记录而不是抛出（用于"一个坏模组不该拖垮整个加载"）。 */
    public void runSafely(Runnable r) {
        try {
            r.run();
            counters.incrementAndGet(IDX_TASKS);
        } catch (Throwable t) {
            recordError(t);
        }
    }

    private void recordError(Throwable t) {
        counters.incrementAndGet(IDX_ERRORS);
        synchronized (allErrors) {
            allErrors.add(t);
        }
        firstError.compareAndSet(null, t);
    }

    private void rethrowIfFailed() {
        Throwable t = firstError.getAndSet(null);
        synchronized (allErrors) {
            allErrors.clear();
        }
        if (t != null) {
            throw t instanceof RuntimeException re ? re : new RuntimeException(t);
        }
    }

    public Stats stats() {
        return new Stats(
                workers.length,
                counters.get(IDX_TASKS),
                counters.get(IDX_STEALS),
                counters.get(IDX_SPINS),
                counters.get(IDX_PARKS),
                counters.get(IDX_NESTED),
                counters.get(IDX_CHUNKS),
                counters.get(IDX_ERRORS));
    }

    /** 统计快照。stealRate 是判断"并行度是否合适"的关键指标。 */
    public record Stats(int workers, long tasks, long steals, long spins, long parks, long nested,
                        long chunks, long errors) {
        public double stealRate() {
            long c = chunks == 0 ? 1 : chunks;
            return (double) steals / c;
        }

        /** 自旋占比高说明线程经常饿着，可能需要更多并行度或更大的块。 */
        public double idleRatio() {
            long total = spins + parks + tasks;
            return total == 0 ? 0 : (double) (spins + parks) / total;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "worker=%d 任务=%d 窃取=%d(%.2f) 块=%d 自旋=%d 休眠=%d 嵌套=%d 错误=%d",
                    workers, tasks, steals, stealRate(), chunks, spins, parks, nested, errors);
        }
    }

    @Override
    public void close() {
        running = false;
        for (Worker w : workers) {
            w.interrupt();
            LockSupport.unpark(w);
        }
    }
}
