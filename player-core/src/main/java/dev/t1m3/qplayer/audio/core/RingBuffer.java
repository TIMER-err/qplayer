package dev.t1m3.qplayer.audio.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时安全的单生产者单消费者环形缓冲。
 *
 * <p>音频回调是<strong>消费者</strong>。它的路径上不允许出现内存分配、锁、系统调用——
 * 一旦出现，表现就是随机爆音，而且极难复现。所有跨线程数据都从这里过。
 *
 * <p>实现要点（与上游一致）：
 *
 * <ul>
 *   <li>容量取 2 的幂，用位与代替取模</li>
 *   <li>{@code read} / {@code write} 是单调递增的游标，回绕交给位与处理；
 *       这里用 {@code int} 而非 {@code long}，容量上限 2^30，所以 {@code write - read}
 *       的差值运算天然安全（Rust 那边要显式 {@code wrapping_sub}）</li>
 *   <li>生产者只写 {@code write}，消费者只写 {@code read}；各自读对方的游标要能看到
 *       对方已经写完的数据。这里用 {@link AtomicInteger} 的 {@code get}/{@code set}
 *       （volatile 语义，强于 Rust 那边的 Acquire/Release，绝对安全）；
 *       {@code getAcquire}/{@code setRelease} 在 Android 上要 API 33+，为了两个宿主
 *       同一份代码而不用</li>
 * </ul>
 *
 * <p>缓冲内容是 <strong>交错 f32 采样</strong>。交错只在这一层出现，DSP 之前全是平面布局。
 *
 * <p>用法：{@link #create(int)} 拿到一对相连的端点，{@link Producer} 与 {@link Consumer}
 * 各自只能被一个线程访问。
 *
 * <p>对应 Sonalis 的 {@code sonalis_core::ring}。
 */
public final class RingBuffer {

    /** 容量下限，与上游一致。 */
    private static final int MIN_CAPACITY = 256;

    /** 容量上限：2 的幂且留出足够的差值空间。 */
    private static final int MAX_CAPACITY = 1 << 30;

    private RingBuffer() {
    }

    /** 一对相连的生产者 / 消费者。 */
    public static final class Ring {
        public final Producer producer;
        public final Consumer consumer;

        private Ring(Producer producer, Consumer consumer) {
            this.producer = producer;
            this.consumer = consumer;
        }
    }

    /**
     * 创建一对相连的生产者 / 消费者。
     *
     * @param minCapacity 最小<strong>采样</strong>容量，实际容量会向上取整到 2 的幂（下限 256）
     */
    public static Ring create(int minCapacity) {
        if (minCapacity > MAX_CAPACITY) {
            throw AudioException.config("环形缓冲容量过大：" + minCapacity + "（上限 " + MAX_CAPACITY + "）");
        }
        int capacity = Math.max(minCapacity, MIN_CAPACITY);
        int pow = 1;
        while (pow < capacity) {
            pow <<= 1;
        }
        Shared shared = new Shared(pow);
        return new Ring(new Producer(shared), new Consumer(shared));
    }

    private static final class Shared {
        /** 交错采样存储。生产者与消费者各自访问不相交的区间。 */
        final float[] data;
        /** 容量减一，用于位与取模。 */
        final int mask;
        /** 生产者写入游标（单调递增，回绕交给位与）。 */
        final AtomicInteger write = new AtomicInteger();
        /** 消费者读取游标（单调递增，回绕交给位与）。 */
        final AtomicInteger read = new AtomicInteger();

        /**
         * 「丢弃跳转之前产出的残留」的世代号：生产者 {@link Producer#flush()} 自增，
         * 消费者在自己的线程上发现它变了就把手上的残留丢掉。
         *
         * <p>为什么要绕一道，而不是让生产者直接改 {@code read}：
         * {@code read} <strong>归消费者独占</strong>。消费者的一次 {@code pop} 是
         * 「读 read → 按它算出区间 → 拷贝 → 写回 read+n」，中间被生产者插一脚把
         * read 推到 write，写回那一步就会把游标<strong>倒退</strong>回去——
         * 于是同一段采样被放两遍、后面的数据全乱。所以丢弃只能由消费者线程做，
         * 生产者只留一个「该丢了」的信号。
         *
         * <p>只有一个生产者、一个消费者：这里的写只在生产者线程，读只在消费者线程，
         * 用 {@code volatile}（比 Rust 那边的 Acquire/Release 更强；
         * {@code getAcquire/setRelease} 要 Android API 33+，所以不用）。
         */
        volatile long flushEpoch;
        /** 消费者已经看到过的世代号。**只有消费者线程读写**，所以不需要同步。 */
        long seenFlushEpoch;

        Shared(int capacity) {
            this.data = new float[capacity];
            this.mask = capacity - 1;
        }

        int capacity() {
            return mask + 1;
        }
    }

    /** 生产者端。只能被一个线程使用。 */
    public static final class Producer {
        private final Shared shared;

        private Producer(Shared shared) {
            this.shared = shared;
        }

        /** 采样容量。 */
        public int capacity() {
            return shared.capacity();
        }

        /** 还能写入多少采样。 */
        public int free() {
            int write = shared.write.get();
            int read = shared.read.get();
            return shared.capacity() - (write - read);
        }

        /** 写入采样，返回实际写入数量（放不下时截断）。 */
        public int push(float[] src) {
            return push(src, 0, src.length);
        }

        /** 写入 {@code src[offset, offset+length)}，返回实际写入数量。 */
        public int push(float[] src, int offset, int length) {
            int capacity = shared.capacity();
            int write = shared.write.get();
            int read = shared.read.get();
            int used = write - read;
            int free = capacity - used;
            int n = Math.min(length, free);
            if (n <= 0) {
                return 0;
            }

            int mask = shared.mask;
            int start = write & mask;
            int first = Math.min(n, capacity - start);
            // 生产者独占 [write, write+n)，且 n <= free 保证与消费者区间不相交。
            System.arraycopy(src, offset, shared.data, start, first);
            if (n > first) {
                System.arraycopy(src, offset + first, shared.data, 0, n - first);
            }

            shared.write.set(write + n);
            return n;
        }

        /**
         * 丢弃<strong>当前所有还没被读走</strong>的采样。跳转与换曲之后必须调它。
         *
         * <h3>为什么必须有这个方法</h3>
         *
         * <p>环形缓冲里通常压着近一个满环的音频（默认 200 ms）。跳转之后不清掉，
         * 设备会先把这些<strong>跳转之前</strong>的采样放出去，然后才跳到新位置——
         * 听感正是「跳过去又弹回来」。引擎那一侧（{@code PlaybackEngine.seek}）
         * 因此必须能下达「丢掉这些」的指令。
         *
         * <h3>怎么丢：生产者只发信号，动手的是消费者线程</h3>
         *
         * <p>生产者<strong>不能</strong>直接改 {@code read} 游标——那个游标归消费者独占，
         * 见 {@link Shared#flushEpoch} 的注释。所以这里只自增一个世代号；
         * 消费者在自己的线程上下一次 {@code pop} 时发现世代变了，就把游标推到当时的
         * {@code write}（此刻 {@code write} 之后的区间本来就不属于「已产出」）。
         *
         * <p>代价要说清：从 flush 到消费者发现它之间，生产者可能又推进去了一小段
         * <strong>新的</strong>采样（一次回调的量级，几十毫秒），那一段会跟着一起被丢掉。
         * 这是有意的取舍——跳转本身就是一次不连续，宁可多吐几十毫秒静音，
         * 也不要让旧位置的声音漏出来。
         *
         * <p>生产者线程独占调用（与 {@link #push} 同一条线程纪律）。
         */
        public void flush() {
            shared.flushEpoch = shared.flushEpoch + 1L;
        }
    }

    /** 消费者端。只能被一个线程使用。 */
    public static final class Consumer {
        private final Shared shared;

        private Consumer(Shared shared) {
            this.shared = shared;
        }

        /** 采样容量。 */
        public int capacity() {
            return shared.capacity();
        }

        /** 有多少采样可读。 */
        public int available() {
            return shared.write.get() - shared.read.get();
        }

        /** 读取采样，返回实际读到的数量。 */
        public int pop(float[] out) {
            return pop(out, 0, out.length);
        }

        /** 读到 {@code out[offset, offset+length)}，返回实际读到的数量。 */
        public int pop(float[] out, int offset, int length) {
            // 跳转之后生产者会调 flush()：残留的旧位置采样在这里被丢掉（见 Shared.flushEpoch）。
            // 判定只在这一层做（不在 available() 里）：这里一定是消费者线程。
            discardIfFlushed();
            int capacity = shared.capacity();
            int read = shared.read.get();
            int write = shared.write.get();
            int available = write - read;
            int n = Math.min(length, available);
            if (n <= 0) {
                return 0;
            }

            int mask = shared.mask;
            int start = read & mask;
            int first = Math.min(n, capacity - start);
            // 消费者独占 [read, read+n)，与生产者区间不相交。
            System.arraycopy(shared.data, start, out, offset, first);
            if (n > first) {
                System.arraycopy(shared.data, 0, out, offset + first, n - first);
            }

            shared.read.set(read + n);
            return n;
        }

        /**
         * 读满 {@code out}，不足部分补静音。返回真实读到的采样数。
         *
         * <p>这是音频回调该用的方法：保持输出时钟恒定，欠载时吐静音，而不是少写
         * 几个采样把时钟带偏。
         */
        public int popOrSilence(float[] out) {
            int got = pop(out);
            if (got < out.length) {
                java.util.Arrays.fill(out, got, out.length, 0f);
            }
            return got;
        }

        /**
         * 生产者发过 {@link Producer#flush()} 时，把「跳转之前产出的、还没读走的」采样丢掉。
         *
         * <p>丢掉的动作<strong>必须</strong>落在消费者线程上（游标归它独占，
         * 理由见 {@link Shared#flushEpoch}），所以判定与推进都在这里；
         * 生产者那边只是一个 volatile 自增，音频回调里多出的开销只有一次
         * volatile 读 + 一次比较——不分配、不加锁、不打日志，符合回调纪律。
         */
        private void discardIfFlushed() {
            long epoch = shared.flushEpoch;
            if (epoch == shared.seenFlushEpoch) {
                return;
            }
            shared.seenFlushEpoch = epoch;
            // 丢到「此刻的 write」：write 之后的区间本来就不属于已产出。
            // 生产者可能正在往更后面写，但那个区间与 read 无关，互不干扰。
            shared.read.set(shared.write.get());
        }
    }
}
