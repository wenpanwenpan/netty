/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 * 真实的netty对象池实现抽象类
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    //一个空的Handle,表示该对象不会被池化
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    // 用于产生池化对象中的回收Id,主要用来标识池化对象被哪个线程回收，主要用于为创建线程以及回收线程创建Id标识，目的是区分创建线程和回收线程
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    //用于标识创建池化对象的线程Id 注意这里是static final字段 也就意味着所有的创建线程 OWN_THREAD_ID 都是相同的
    //这里主要用来区分创建线程与非创建线程。多个非创建线程拥有各自不同的Id
    //这里的视角只是针对池化对象来说的：区分创建它的线程，与其他回收线程
    // 在 Recycler 类初始化的时候，会利用ID_GENERATOR 为 OWN_THREAD_ID 字段赋值，从字面意思上我们也可以看出 OWN_THREAD_ID 是用来标识创建线程Id的。
    // 这里有一点大家需要注意的是，OWN_THREAD_ID 是一个 static final 字段，这也就意味着所有的Recycler对象池实例中的 OWN_THREAD_ID 都是一样的。
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    //对象池中每个线程对应的Stack中可以存储池化对象的默认初始最大个数 默认为4096个对象
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    // 对象池中线程对应的Stack可以存储池化对象默认最大个数 4096
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    // 初始容量 min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256) 初始容量不超过256个
    private static final int INITIAL_CAPACITY;
    //用于计算回收线程可帮助回收的最大容量因子  默认为2
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    //每个回收线程最多可以帮助多少个创建线程回收对象 默认：cpu核数 * 2
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    //回收线程对应的WeakOrderQueue节点中的Link链表中的节点存储待回收对象的容量 默认为16
    private static final int LINK_CAPACITY;
    private static final int RATIO;
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        // 定义每个创建线程对应的Stack结构中的数组栈初始默认的最大容量。默认为4096个。可由JVM启动参数 -D io.netty.recycler.maxCapacity 指定。
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        // 定义每个创建线程对应的Stack结构中的数组栈的最大容量。可由JVM启动参数 -D io.netty.recycler.maxCapacityPerThread 指定，
        // 如无特殊指定，即采用 DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD 的值，默认为4096个。
        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        // 针对创建线程中的 Stack，其对应的【所有回收线程】总共可帮助其回收的对象总量【计算因子】。默认为2。可通过JVM参数
        // -D io.netty.recycler.maxSharedCapacityFactor 指定，总共回收对象总量就是通过对象池的最大容量和该计算因子计算出来的。
        // 计算公式：max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY) 。由此我们可以知道创建线程对应的所有回收线程总共
        // 可帮助其回收的对象总量默认为2048个，最小回收容量为 LINK_CAPACITY  默认为16。
        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        //每个回收线程最多可以帮助多少个创建线程回收对象 默认：cpu核数 * 2
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        //
        // 在池化对象被回收的时候分别由两类线程来执行。
        // 1、一类是创建线程。池化对象在创建线程中被创建出来后，一直在创建线程中被处理，处理完毕后由创建线程直接进行回收。而为了避免对象池不可控制地迅速膨胀，
        // 所以需要对创建线程回收对象的频率进行限制。这个回收频率由参数 RATIO 控制，默认为8，可由JVM启动参数 -D io.netty.recycler.ratio 指定。
        // 表示创建线程只回收 1 / 8 的对象，也就是每创建 8 个对象最后只回收 1个对象。
        //
        // 2、另一类就是回收线程。池化对象在创建线程中被创建出来，但是业务的相关处理是在回收线程中，业务处理完毕后由回收线程负责回收。
        // 前边提到对象回收有一个基本原则就是对象是谁创建的，就要回收到创建线程对应的Stack中。所以回收线程就需要将池化对象回收至其创建线程对应的Stack中
        // 的WeakOrderQueue链表中。并等待创建线程将WeakOrderQueue链表中的待回收对象转移至Stack中的数组栈中。同样，回收线程也需要控制回收频率，
        // 由参数 DELAYED_QUEUE_RATIO 进行控制，默认也是8，可由JVM启动参数 -D io.netty.recycler.delayedQueue.ratio 指定，表示回收线程每处理完 8 个对象才回收 1 个对象。
        // ------------------------------------------------------------------------------------------------------------
        //【创建线程】回收对象时的回收比例，默认是8，表示只回收1/8的对象。也就是产生8个对象回收一个对象到对象池中
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        //【回收线程】回收对象时的回收比例，默认也是8，同样也是为了避免回收线程回收队列疯狂增长 回收比例也是1/8
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        // 定义每个创建线程对应的Stack结构中的数组栈的初始容量。计算公式为min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256)，默认为256个。
        // 当池化对象超过256个时，则对对象池进行扩容，但不能超过最大容量 DEFAULT_MAX_CAPACITY_PER_THREAD。
        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    //创建线程持有对象池的最大容量
    private final int maxCapacityPerThread;
    //所有回收线程可回收对象的总量(计算因子)
    private final int maxSharedCapacityFactor;
    //创建线程的回收比例
    private final int interval;
    //一个回收线程可帮助多少个创建线程回收对象
    private final int maxDelayedQueuesPerThread;
    //回收线程回收比例
    private final int delayedQueueInterval;

    // threadLocal保存每个线程对应的 stack结构
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            // 初始化，当第一次从该 threadLocal 获取值时会触发该stack的创建
            // 利用前边介绍过的已经初始化好的Recycler属性对Stack结构中的这些属性进行赋值
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            // 当从该 threadLocal中移除一个stack时，需要将
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    /**
     * 构造函数
     * @param maxCapacityPerThread 创建线程持有对象池的最大容量
     * @param maxSharedCapacityFactor 所有回收线程可回收对象的总量(计算因子)
     * @param ratio 创建线程的回收比例，如果为0则表示不回收对象
     * @param maxDelayedQueuesPerThread 一个回收线程可帮助多少个创建线程回收对象
     * @param delayedQueueRatio 回收线程回收比例，如果为0则表示不回收对象
     * @author wenpan 2024/1/7 4:04 下午
     */
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 从对象池中获取池化对象
     * @return T 池化对象
     * @author wenpan 2024/1/6 6:07 下午
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        //如果对象池容量为0，则立马新创建一个对象返回，但是该对象不会回收进对象池
        if (maxCapacityPerThread == 0) {
            //newObject为对象池recycler的抽象方法，由使用者初始化内存池的时候 匿名提供，可以看到这里的handle是 NOOP_HANDLE，表示不会被池化
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //获取当前线程 保存池化对象的stack，如果是第一次调用get方法，则会触发FastThreadLocal的初始化方法创建一个stack并放入当前线程
        Stack<T> stack = threadLocal.get();
        //从stack中pop出对象，handle是池化对象在对象池中的模型，包装了一些池化对象的回收信息和回收状态
        // 注意：这里的pop并不是简单的从栈里弹出，而是会先检查数组栈是否为空，如果不为空则弹出栈顶元素，如果为空则还会从weakOrderQueue中回收
        DefaultHandle<T> handle = stack.pop();
        // 如果当前线程的stack中没有可用的池化对象，则返回空，此时会直接创建对象
        if (handle == null) {
            //初始化的handle对象recycleId和lastRecyclerId均为0
            handle = stack.newHandle();
            //newObject为对象池recycler的抽象方法，由使用者初始化内存池的时候 匿名提供，这里就将 handle 和 池化对象关联起来了
            handle.value = newObject(handle);
        }
        // 返回真实的池化对象
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     * 归还池化对象给对象池
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        // 可以看到如果handle是NOOP_HANDLE，那么表示该池化对象不需要被回收，直接丢弃即可
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        // 池化对象所属的对象池不是当前对象池，则归还失败
        if (h.stack.parent != this) {
            return false;
        }

        // 归还池化对象
        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**
     * 创建池化对象，该方法交给具体的子类实现
     * @param handle 池化对象在对象池中的表示
     * @return T 池化对象
     * @author wenpan 2024/1/6 11:30 下午
     */
    protected abstract T newObject(Handle<T> handle);

    /***
     * 可以看到他继承了 ObjectPool的handle
     * @author wenpan 2024/1/6 5:46 下午
     */
    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    /** 池化对象在对象池中的表示（默认实现）*/
    private static final class DefaultHandle<T> implements Handle<T> {
        //
        // 为什么池化对象的回收还要分最近回收和最终回收呢？因为对象池中的池化对象回收可以分为两种情况：
        // 1、由创建线程直接进行回收：这种回收情况就是一步到位，直接回收至创建线程对应的Stack中。所以这种情况下是不分阶段的。recycleId = lastRecycledId = OWN_THREAD_ID。
        // 2、由回收线程帮助回收：这种回收情况下就要分步进行了，首先由回收线程将池化对象暂时存储在其创建线程对应Stack中的WeakOrderQueue链表中。
        // 此时并没有完成真正的对象回收。recycleId = 0，lastRecycledId = 回收线程Id（WeakOrderQueue#id）。当创建线程将WeakOrderQueue链表
        // 中的待回收对象转移至Stack结构中的数组栈之后，这时池化对象才算真正完成了回收动作。recycleId = lastRecycledId = 回收线程Id（WeakOrderQueue#id）。
        // 这两个字段 lastRecycledId ，recycleId 主要是用来标记池化对象所处的回收阶段，以及在这些回收阶段具体被哪个线程进行回收
        //
        // recycleId 与 lastRecycledId 之间的关系分为以下几种情况：
        // 1、recycleId = lastRecycledId = 0：表示池化对象刚刚被创建或者刚刚从对象池中取出即将被再次复用。这是池化对象的初始状态。
        // 2、recycleId = lastRecycledId != 0：表示当前池化对象已经被回收至对应Stack结构里的数组栈中。可以直接被取出复用。可能是被其创建线程直接回收，也可能是被回收线程回收。
        // 3、recycleId != lastRecycledId：表示当前池化对象处于半回收状态。池化对象已经被业务线程处理完毕，并被回收线程回收至对应的WeakOrderQueue节点中。并等待创建线程将其最终转移至Stack结构中的数组栈中
        // ------------------------------------------------------------------------------------------------------------------------
        //用于标识最近被哪个线程回收，被回收之前均是0
        int lastRecycledId;
        //用于标识最终被哪个线程回收，在没被回收前是0
        int recycleId;
        //该池化对象是否已经被回收
        boolean hasBeenRecycled;
        // 该池化对象所属的线程stack（也就是说该handle是被那个Stack创建出来的）
        Stack<?> stack;
        // 真实的池化对象
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 当我们使用完毕池化对象后，就需要调用该方法将池化对象归还给对象池，以便于下次再利用
         * 该归还逻辑就是将 Handle 对象放回到stack里的数组栈里
         * @param object 待归还的真实池化对象
         * @author wenpan 2024/1/7 3:06 下午
         */
        @Override
        public void recycle(Object object) {
            // 归还的对象不等于当前对象持有的真实池化对象，则禁止归还
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            // 1、handle初次创建以及从对象池中获取到时  recycleId = lastRecycledId = 0（对象被回收之前）
            // 2、创建线程回收对象后recycleId = lastRecycledId = OWN_THREAD_ID
            // 3、回收线程回收对象后lastRecycledId = 回收线程Id,当对象被转移到stack中后 recycleId = lastRecycledId = 回收线程Id
            // 4、stack == null ：这种情况其实前边我们也有提到过，就是当池化对象对应的创建线程挂掉的时候，对应的Stack随后也被GC回收掉。那么这时就不需要在回收该池化对象了
            // 此时对象的状态正处于已经被回收线程回收至对应 WeakOrderQueue 节点的半回收状态，但还未被转移至其创建线程对应的Stack中。
            // 所以这个条件要控制的事情就是如果对象已经被回收线程回收，那么就停止本次的回收操作
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            // 将该池化对象归还给对象池（也就是归还给当前线程的池化对象stack里），这里分为了【创建线程】归还和【回收线程】归还，点进去看
            stack.push(this);
        }
    }

    //实现跨线程回收的核心，这里保存的是当前线程为其他线程回收的对象（由其他线程创建的池化对象）
    //key: 池化对象对应的创建线程stack  value: 当前线程代替该创建线程回收的池化对象 存放在weakOrderQueue中
    //这里的value即是 创建线程对应stack中的weakOrderQueue链表中的节点（每个节点表示其他线程为当前创建线程回收的对象）
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        // 首次调用threadLocal的get方法时调用该方法
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            // WeakHashMap 结构中的 key 表示创建线程对应的 Stack 结构。意思是该回收线程为哪个创建线程回收对象。
            // value 表示这个回收线程在创建线程中对应Stack结构里的WeakOrderQueue链表中对应的节点，
            // 而这个WeakHashMap 的size即表示当前回收线程已经在为多少个创建线程回收对象了，size的值不能超过 maxDelayedQueuesPerThread 。
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    // 为了多线程能够无锁化回收对象，一个回收线程对应一个WeakOrderQueue节点，在WeakOrderQueue节点中持有对应回收线程的弱引用,
    // 目的也是为了当回收线程挂掉的时候，能够保证回收线程被GC及时的回收掉。通过继承 WeakReference 类，可以看到 WeakOrderQueue 是一个弱引用
    // 虽然该结构命名的后缀是一个Queue，但其实是一个链表，链表中的元素类型为Link，头结点指针Head永远指向第一个未被转移完毕的Link，
    // 当一个Link里的待回收对象被全部转移完毕后，head指针随即指向下一个节点，但是该Link节点并不会从链表中删除。尾指针Tail指向链表中最后一个Link节点。节点的插入是从链表的尾部开始插入
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        //作为一个标识，遇到DUMMY实例，则直接丢弃回收对象
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        // link结构是用于真正存储待回收对象的结构，继承AtomicInteger 本身可以用来当做 writeindex 使用
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            // 数组用来存储回收线程回收的池化对象，容量为16
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            // 创建线程在转移Link节点中的待回收对象时，通过这个readIndex来读取未被转移的对象。由于readIndex只会被创建线程使用，
            // 所以这里并不需要保证原子性和可见性。用一个普通的int变量存储就好
            int readIndex;
            //weakOrderQueue中的存储结构是由link结构节点元素组成的链表结构
            Link next;
            // Link结构继承于AtomicInteger类型，这就意味着Link结构本身就可以被当做一个writeIndex来使用，由于回收线程在向Link节点添加回收对象
            // 的时候需要修改writeIndex，于此同时创建线程在转移Link节点的时候需要读取writeIndex，所以writeIndex需要保证线程安全性，故采用AtomicInteger类型存储
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        // weakOrderQueue内部link链表的头结点
        // 头结点指针Head永远指向第一个未被转移完毕的Link，当一个Link里的待回收对象被全部转移完毕后，head指针随即指向下一个节点，
        // 但是该Link节点并不会从链表中删除。尾指针Tail指向链表中最后一个Link节点。节点的插入是从链表的尾部开始插入
        private static final class Head {
            //所有回收线程能够帮助创建线程回收对象的总容量 reserveSpaceForLink方法中会多线程操作该字段，用于指示当前回收线程是否继续为创建线程回收对象，
            // 所有回收线程都可以看到，这个值是所有回收线程共享的。以便可以保证所有回收线程回收的对象总量不能超过 availableSharedCapacity
            // 表达的语义是所有回收线程总共可以帮助创建线程一共可以回收多少对象。对所有回收线程回收对象的总量进行限制。
            // 每创建一个Link节点，它的值就减少一个LINK_CAPACITY ，每释放一个Link节点，它的值就增加一个LINK_CAPACITY
            private final AtomicInteger availableSharedCapacity;
            //link链表的头结点
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             * 回收head节点的所有空间，并从链表中删除head节点，head指针指向下一节点
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                // 释放掉每一个link引用，help gc
                while (head != null) {
                    // 每释放一个link节点就将可用容量增加 LINK_CAPACITY
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                //所有回收线程都可以看到，这个值是所有回收线程共享的。以便可以保证所有回收线程回收的对象总量不能超过availableSharedCapacity
                availableSharedCapacity.addAndGet(space);
            }

            //参数link为新的head节点，当前head指针指向的节点已经被回收完毕
            void relink(Link link) {
                //更新availableSharedCapacity，因为当前link节点中的待回收对象已经被转移完毕，所以需要增加availableSharedCapacity的值
                // 可以看到这里增加的是 LINK_CAPACITY
                reclaimSpace(LINK_CAPACITY);
                //head指针指向新的头结点（第一个未被回收完毕的link节点）
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             * 创建新的link节点
             */
            Link newLink() {
                //此处的availableSharedCapacity可能已经被多个回收线程改变，因为availableSharedCapacity是用来控制回收线程回收的总容量限制
                //每个回收线程再回收对象时都需要更新availableSharedCapacity
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            // 此处目的是为接下来要创建的link预留空间容量
            // 这里的预订容量其实就是将 availableSharedCapacity 的值减去一个 LINK_CAPACITY 大小。
            // 其他回收线程会看到这个 availableSharedCapacity 容量的变化，方便决定是否继续为创建线程回收对象。
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                //在创建新的Link节点之前需要调用该方法预订容量空间
                for (;;) {
                    //获取stack中允许异线程回收对象的总容量（异线程还能为该stack收集多少对象）
                    int available = availableSharedCapacity.get();
                    //当availbale可供回收容量小于一个Link时，说明异线程回收对象已经达到上限，不能在为stack回收对象了
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    //为Link预留到一个Link的空间容量，更新availableSharedCapacity
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        //link链表的头结点，head指针始终指向第一个未被转移完毕的LinK节点
        private final Head head;
        // 尾指针
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        //站在stack的视角中，stack中包含一个weakOrderQueue的链表，每个回收线程为当前stack回收的对象存放在回收线程对应的weakOrderQueue中
        //这样通过stack中的这个weakOrderQueue链表，就可以找到其他线程为该创建线程回收的对象
        private WeakOrderQueue next;
        // 回收线程回收Id,每个weakOrderQueue分配一个，同一个stack下的一个回收线程对应一个weakOrderQueue节点
        private final int id = ID_GENERATOR.getAndIncrement();
        //回收线程回收比例 默认是8
        private final int interval;
        //回收线程回收计数 回收1/8的对象
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        // 为了使stack能够被GC,这里不会持有其所属stack的引用，如果Stack结构对应的创建线程挂掉，而此时WeakOrderQueue又持有了Stack的引用，
        // 这样就使得Stack结构无法被GC掉。所以这里只会用Stack结构的相关属性去初始化WeakOrderQueue结构，在WeakOrderQueue中并不会持有Stack的引用。
        // 在复杂程序结构的设计中，我们要时刻对对象之间的引用关系保持清晰的认识。防止内存泄露。
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            //weakOrderQueue持有对应回收线程的弱引用
            super(thread);
            //创建尾结点
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            // 创建头结点  availableSharedCapacity = maxCapacity / maxSharedCapacityFactor
            head = new Head(stack.availableSharedCapacity);
            // WeakOrderQueue 创建时头尾指针都指向同一个节点
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        /**
         * 创建一个 WeakOrderQueue 对象
         * @param stack 该 WeakOrderQueue 所属的 stack
         * @param thread 该 WeakOrderQueue 所关联的线程
         * @return io.netty.util.Recycler.WeakOrderQueue
         * @author wenpan 2024/1/6 11:35 下午
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // link是weakOrderQueue中存储回收对象的最小结构，此处是为接下来要创建的Link预订空间容量
            // 如果stack指定的availableSharedCapacity 小于 LINK_CAPACITY大小，则分配失败
            // 而对于一个创建线程来说它的所有回收线程能够为其回收对象的总量是被availableSharedCapacity 限制的，每创建一个Link节点，它的值就减少一个LINK_CAPACITY ，
            // 每释放一个Link节点，它的值就增加一个LINK_CAPACITY 。这样就能保证所有回收线程的回收总量不会超过 availableSharedCapacity 的限制
            // 所以在为WeakOrderQueue结构创建首个Link节点时，需要判断当前所有回收线程回收的对象总量是否已经超过了 availableSharedCapacity 。
            // 如果容量还够回收一个Link大小的对象，则开始创建WeakOrderQueue结构。
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            //如果还够容量来分配一个link那么就创建weakOrderQueue
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 向stack中的weakOrderQueue链表中添加当前回收线程对应的weakOrderQueue节点（始终在头结点处添加节点，头插法 ）
            // 此处向stack中添加weakOrderQueue节点的操作被移到WeakOrderQueue构造器之外的目的是防止WeakOrderQueue.this指针
            // 逃逸避免被其他线程在其构造的过程中访问
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**从头节点开始逐个断开 WeakOrderQueue 中的link节点引用，最后断开当前 WeakOrderQueue 节点对他下一个 WeakOrderQueue 节点的引用*/
        void reclaimAllSpaceAndUnlink() {
            // 从头节点开始逐个断开每个link节点的引用
            head.reclaimAllSpaceAndUnlink();
            // 断开该 WeakOrderQueue 对他的next节点的引用
            this.next = null;
        }

        /**
         * 这里要做的事情就是，将回收对象添加到回收线程对应的WeakOrderQueue节点中，Netty会在Link链表的尾结点处添加回收对象，
         * 如果尾结点容量已满，就继续新创建一个Link。将回收对象添加到新的Link节点中
         * @param handle 待回收的对象
         * @author wenpan 2024/1/7 12:59 下午
         */
        void add(DefaultHandle<?> handle) {
            //将handler中的lastRecycledId标记为当前weakOrderQueue中的Id,一个stack和一个回收线程对应一个weakOrderQueue节点
            //表示该池化对象 最近的一次是被当前回收线程回收的。
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            // 控制异线程回收频率 只回收1/8的对象
            // 这里需要关注的细节是其实在scavengeSome方法中将weakOrderQueue中的待回收对象转移到创建线程的stack中时，Netty也会做回收频率的限制
            // 这里在回收线程回收的时候也会控制回收频率（总体控制两次）netty认为越早的做回收频率控制越好 这样可以避免weakOrderQueue中的容量迅速的增长从而失去控制
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            handleRecycleCount = 0;

            //从尾部link节点开始添加新的回收对象
            Link tail = this.tail;
            int writeIndex;
            //如果当前尾部link节点容量已满，就需要创建新的link节点
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //创建新的Link节点
                Link link = head.newLink();
                //如果availableSharedCapacity的容量不够了，则无法创建Link。丢弃待回收对象
                if (link == null) {
                    // 丢弃对象
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                //更新尾结点（尾插法）
                this.tail = tail = tail.next = link;

                writeIndex = tail.get();
            }
            //将回收对象handler放入尾部link节点中
            tail.elements[writeIndex] = handle;
            //这里将stack置为null，是为了方便stack被回收。
            //如果Stack不再使用，期望被GC回收，发现handle中还持有stack的引用，那么就无法被GC回收，从而造成内存泄漏
            //在从对象池中再次取出该对象时，stack还会被重新赋予
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            //注意这里用lazySet来延迟更新writeIndex。只有当writeIndex更新之后，在创建线程中才可以看到该待回收对象
            //保证线程最终可见而不保证立即可见的原因就是 其实这里Netty还是为了性能考虑避免执行内存屏障指令的开销。
            //况且这里也并不需要考虑线程的可见性，当创建线程调用scavengeSome从weakOrderQueue链表中回收对象时，看不到当前节点weakOrderQueue
            //新添加的对象也没关系，因为是多线程一起回收，所以继续找下一个节点就好。即使全没看到，大不了就在创建一个对象。主要还是为了提高weakOrderQueue的写入性能
            tail.lazySet(writeIndex + 1);
        }

        // WeakOrderQueue 中是否还有待转移到stack的池化对象
        boolean hasFinalData() {
            // 直接判断尾节点的读指针是否等于尾节点的写指针即可（如果不相等则说明还有元素）
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        // 用于将当前WeakOrderQueue节点中的待回收对象转移至创建线程对应的Stack中，转移逻辑如下：
        // 1、开始转移回收对象时会从WeakOrderQueue节点中的Link链表的头结点开始遍历，如果头结点中还有未被转移的对象，则将头结点剩余的未转移对象转移至Stack中。
        // 所以创建线程每次最多转移一个LINK_CAPACITY大小的对象至Stack中。只要成功转移了哪怕一个对象，transfer方法就会返回true。
        // 2、如果头结点中存储的对象已经全部转移完毕，则更新head指针指向下一个Link节点，开始转移下一个Link节点。创建线程每次只会转移一个Link节点。
        // 如果Link链表是空的，没有转移成功一个对象，则transfer方法返回false
        // 这里什么时候会返回false呢？1、当前WeakOrderQueue没有可转移的池化对象了，2、stack的数组栈达到最大容量了
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            //获取当前weakOrderQueue节点中的link链表头结点
            Link head = this.head.link;
            //头结点为null说明还没有待回收对象，直接返回转移失败
            if (head == null) {
                return false;
            }

            //如果头结点中的待回收对象已经被转移完毕（readIndex指针已经到达最大容量了）
            if (head.readIndex == LINK_CAPACITY) {
                //判断是否有后续Link节点
                if (head.next == null) {
                    //整个link链表没有待回收对象了已经
                    return false;
                }
                // 如果有下一个link节点，则将head指针指向下一个
                head = head.next;
                //当前Head节点已经被转移完毕，head指针向后移动，head指针始终指向第一个未被转移完毕的LinK节点
                this.head.relink(head);
            }

            // 此时Head节点已经校验完毕，可以执行正常的转移逻辑了。但在转移逻辑正式开始之前，还需要对本次转移对象的容量进行计算，
            // 并评估Stack的当前容量是否可以容纳的下，如果Stack的当前容量不够，则需要对Stack进行扩容。
            // ------------------------------------------------------------------------------------------------
            // 该head节点读指针
            final int srcStart = head.readIndex;
            // writeIndex（写指针）
            int srcEnd = head.get();
            // 该link节点可被转移的对象容量（读指针和写指针之间的元素个数）
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            // 获取创建线程stack中的当前回收对象数量总量
            final int dstSize = dst.size;
            // 待回收对象从weakOrderQueue中转移到stack后，stack的新容量 = 转移前stack容量 + 转移的待回收对象个数
            final int expectedCapacity = dstSize + srcSize;

            // 计算转移后的stack容量超过了当前stack的容量，dst.elements.length表示数组栈的总容量
            if (expectedCapacity > dst.elements.length) {
                //如果转移后的stack容量超过当前stack的容量 则对stack进行扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                // 根据扩容后的容量最终决定本次转移多少对象，确保不能超过Stack可容纳的空间。
                //每次转移最多一个Link的容量，actualCapacity - dstSize 表示扩容后的stack还有多少剩余空间
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            // 从这里可以看到每次转移最多为一个link内的全部元素（也就是最多16个池化对象）
            // 不相等，说明读指针和写指针之间还有可转移的池化对象元素
            if (srcStart != srcEnd) {
                //待转移对象集合 也就是Link节点中存储的元素
                final DefaultHandle[] srcElems = head.elements;
                //stack中存储池化对象的数组
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                // 从link节点里的数组的读指针开始直到 srcEnd 写指针处结束，逐个向stack转移池化对象
                for (int i = srcStart; i < srcEnd; i++) {
                    // 获取link里的每一个被回收线程帮助回收的池化对象
                    DefaultHandle<?> element = srcElems[i];
                    //recycleId == 0 表示对象还没有被真正的回收到stack中
                    if (element.recycleId == 0) {
                        //设置recycleId 表明是被哪个weakOrderQueue回收的，此处的lastRecycledId为当前WeakOrderQueue节点对应的回收线程Id
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        //既被创建线程回收 同时也被回收线程回收  回收多次 则停止转移
                        throw new IllegalStateException("recycled already");
                    }
                    //对象转移后需要置空Link节点对应的位置，便于该池化对象被gc
                    srcElems[i] = null;

                    // 这里从weakOrderQueue将待回收对象真正回收到所属stack之前 需要进行回收频率控制，如果返回true则表示不进行转移
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    // 重新为defaultHandle设置其所属stack(初始创建该handle的线程对应的stack)
                    // 该defaultHandle在被回收线程回收的时候，会将其stack置为null，防止极端情况下，创建线程挂掉，对应stack无法被GC
                    element.stack = dst;
                    //此刻，handle才真正的被回收到所属stack中
                    dstElems[newDstSize ++] = element;
                }

                // srcEnd == LINK_CAPACITY 说明当前link节点已经转移完毕，head.next != null保证头节点用于不为空
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    // 如果当前Link已经被回收完毕，且link链表还有后续节点，则更新head指针
                    this.head.relink(head.next);
                }

                //更新当前回收Link的readIndex，表示该节点已转移完成
                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    //如果没有转移任何数据 return false
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                // 如果当前Stack已经达到最大容量，无法再继续扩容：actualCapacity - dstSize = 0，则停止本次转移操作，直接返回false。
                return false;
            }
        }
    }

    /**
     * 对象池所使用的栈，该结构不是普通的栈，他具有栈的基本特性，里面还有链表，以及众多的特性属性
     * @author wenpan 2024/1/6 11:39 下午
     */
    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items. 该stack所关联的对象池
        // 创建线程保存池化对象的stack结构所属对象池recycler实例
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        // Stack会通过弱引用的方式引用到其对应的创建线程。这里使用弱引用来持有对应创建线程的原因是因为对象池的设计中存在这样一个引用关系：
        // 池化对象 -> DefaultHandle -> stack -> threadRef。而池化对象是暴露给用户的，如果用户在某个地方持有了池化对象的强引用忘记清理，
        // 而Stack持有创建线程的强引用的话，当创建线程死掉的之后，因为这样一个强引用链的存在从而导致创建线程一直不能被GC回收
        final WeakReference<Thread> threadRef;
        // 当前创建线程对应的所有回收线程可以帮助当前创建线程回收的对象总量,availableSharedCapacity 在多个回收线程中是共享的，
        // 回收线程每回收一个对象它的值就会减1，当小于 LINK_CAPACITY(回收线程对应WeakOrderQueue节点的最小存储单元Link)时，
        // 回收线程将不能在为该stack回收对象了。该值的计算公式为前边介绍的 max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY)
        final AtomicInteger availableSharedCapacity;
        // 当前Stack对应的创建线程作为其他创建线程的回收线程时可以帮助多少个线程回收其池化对象
        private final int maxDelayedQueues;

        //当前创建线程对应的stack结构中的最大容量。 默认4096个对象
        private final int maxCapacity;
        //当前创建线程回收对象时的回收比例
        private final int interval;
        //当前创建线程作为其他线程的回收线程时回收其他线程的池化对象比例
        private final int delayedQueueInterval;
        // 当前Stack中的数组栈 默认初始容量256，最大容量为4096
        DefaultHandle<?>[] elements;
        //数组栈 栈顶指针
        int size;
        // 回收对象计数 与 interval配合 实现只回收一定比例的池化对象
        private int handleRecycleCount;
        // Stack结构中的WeakOrderQueue链表，多线程回收的设计，核心还是无锁化，避免多线程回收相互竞争
        // 当前节点和前一个节点，这三个指针初始都为空。一个 WeakOrderQueue 就代表一个回收线程对应的信息。WeakOrderQueue节点的加入链表都是采用的头插法
        private WeakOrderQueue cursor, prev;
        // 头节点
        private volatile WeakOrderQueue head;

        /**
         * 构造函数
         * @param parent 该stack所属的对象池，一个对象池可被多个线程访问获取对象，所以一个对象池对应多个Stack，每个Stack的parent属性指向所属的Recycler实例
         * @param thread 该stack的线程
         * @param maxCapacity 当前创建线程对应的stack结构中的最大容量
         * @param maxSharedCapacityFactor 因子
         * @param interval 当前创建线程回收对象时的回收比例
         * @param maxDelayedQueues 当前Stack对应的创建线程作为其他创建线程的回收线程时可以帮助多少个创建线程回收其池化对象
         * @param delayedQueueInterval 当前创建线程作为其他线程的回收线程时回收其他线程的池化对象比例
         * @author wenpan 2024/1/6 11:41 下午
         */
        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            // 可以看到stack通过 threadRef 属性对创建他的线程进行弱引用
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        //整个recycler对象池唯一的一个同步方法，而且同步块非常小，逻辑简单，执行迅速
        synchronized void setHead(WeakOrderQueue queue) {
            //始终在weakOrderQueue链表头结点插入新的节点
            queue.setNext(head);
            head = queue;
        }

        // 对栈容量进行扩容
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                // 容量增加一倍
                newCapacity <<= 1;
                // 扩容后的容量还小于期望的容量并且扩容后的容量小于栈的最大容量，则继续扩大栈容量
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            //扩容后的新容量为最接近指定容量expectedCapacity的最大2的次幂
            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                // 扩容后栈内元素拷贝
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * 【重要】这里就是业务线程从对象池中真正获取池化对象的地方
         * @return io.netty.util.Recycler.DefaultHandle<T>
         * @author wenpan 2024/1/6 6:47 下午
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            //普通出栈操作，从栈顶弹出一个回收对象
            int size = this.size;
            // 1、如果数组栈里没有可用的池化对象，则尝试看看有没有回收线程帮忙回收的池化对象，如果有则将他转移到stack中
            if (size == 0) {
                //如果当前线程所属stack已经没有对象可用，则遍历stack中的weakOrderQueue链表（其他线程帮助回收的对象存放在这里）将这些待回收对象回收进stack
                // 如果返回为false则表示转移失败，直接返回null，外层会判空，如果为空则创建一个handle池化对象
                if (!scavenge()) {
                    return null;
                }
                // 再次获取栈顶指针，如果此时栈顶指针 size > 0 则说明上面有从 weakOrderQueue链表 成功转移池化对象到stack里的数组栈
                size = this.size;
                // 如果从 WeakOrderQueue 转移到 stack 后stack里可用的池化对象个数还是0，则认为没有可用池化对象
                if (size <= 0) {
                    // double check, avoid races
                    // 如果WeakOrderQueue链表中也没有待回收对象可转移，直接返回null 新创建一个对象
                    return null;
                }
            }
            // 2、数组栈中有元素了，出栈栈顶元素
            size --;
            // 栈顶元素
            DefaultHandle ret = elements[size];
            // 释放掉栈顶元素的引用
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before. 更新栈顶指针
            this.size = size;

            // 3、最近回收的线程 ！= 最终回收的线程，说明对象至少被一个线程回收了
            if (ret.lastRecycledId != ret.recycleId) {
                // 这种情况表示对象至少被一个线程回收了，要么是创建线程，要么是回收线程
                throw new IllegalStateException("recycled multiple times");
            }
            // 4、对象初次创建以及回收对象再次使用时  它的 recycleId = lastRecycleId = 0
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        /**
         * 如果WeakOrderQueue链表中还有待回收对象并转移成功则返回 true 。如果WeakOrderQueue链表为空没有任何待回收对象可转移，则重置链表相关的指针，
         * cursor重新指向head节点，prev指向null。因为在遍历WeakOrderQueue链表搜寻可转移对象时，cursor指针已经发生变化了，这里需要重置
         * */
        private boolean scavenge() {
            // continue an existing scavenge, if any
            // 从其他线程回收的weakOrderQueue里 转移 待回收对像 到当前线程的stack中，注意这里执行该方法的线程是谁？当然是创建线程
            // 如果转移成功则返回true
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            // 如果每个weakOrderQueue中都没有待回收对象可转移，那么就重置stack中的cursor.prev
            // 因为在扫描weakOrderQueue链表的过程中，cursor已经发生变化了
            prev = null;
            cursor = head;
            return false;
        }

        // 从 WeakOrderQueue 中转移回收线程帮忙回收的池化对象到stack中
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            //获取当前线程stack 的weakOrderQueue链表指针（本次扫描起始节点）
            WeakOrderQueue cursor = this.cursor;
            //在stack初始化完成后，cursor，prev,head等指针全部是null，这里如果cursor == null 意味着当前stack第一次开始扫描weakOrderQueue链表
            // 所以需要把 cursor 指向头节点（此时prev也是空），然后开始扫描
            if (cursor == null) {
                prev = null;
                cursor = head;
                // 头节点都是空，说明目前weakOrderQueue链表里还没有节点，并没有其他线程帮助回收的池化对象
                if (cursor == null) {
                    return false;
                }
            } else {
                //获取prev指针，用于操作链表（删除当前cursor节点）
                prev = this.prev;
            }

            // 标识是否从 weakOrderQueue 向 stack 数组栈转移池化对象成功
            boolean success = false;
            //循环遍历weakOrderQueue链表 转移待回收对象
            do {
                //将weakOrderQueue链表中当前节点中包含的待回收对象，转移到当前stack中，一次转移一个link
                // 这里什么时候会返回false呢？1、当前WeakOrderQueue没有可转移的池化对象了，2、stack的数组栈达到最大容量了
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }

                //如果当前cursor节点没有待回收对象可转移，那么就继续遍历链表获取下一个weakOrderQueue节点
                WeakOrderQueue next = cursor.getNext();
                //如果当前weakOrderQueue对应的回收线程已经挂掉了，则一次性将该 WeakOrderQueue 里的所有该回收线程回收的池化对象转移到stack里
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    // 判断当前weakOrderQueue节点是否还有可回收对象，如果有则将 weakOrderQueue 中的池化对象全部转移到stack中
                    if (cursor.hasFinalData()) {
                        //回收 weakOrderQueue 中最后一点可回收对象，因为对应的回收线程已经死掉了，这个weakOrderQueue不会再有任何对象了
                        // 可以看到这里将当前 weakOrderQueue 里的所有待回收的池化对象都转移到当前线程的stack里
                        for (;;) {
                            // 这里是个死循环，一直转移，直到 weakOrderQueue 中没有可转移的池化对象了，transfer返回false，退出死循环
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    // 回收线程已死，对应的weakOrderQueue节点中的最后一点待回收对象也已经回收完毕，就需要将当前节点从链表中删除。unlink当前cursor节点
                    // 这里需要注意的是，netty永远不会删除第一个节点，因为更新头结点是一个同步方法，避免更新头结点而导致的竞争开销
                    // 【prev == null 说明当前cursor节点是头结点】。不用unlink，如果不是头结点 就将其从链表中删除，因为这个节点不会再有线程来收集池化对象了
                    // 注意：这里如果 cursor 当前指向的是头节点，那么即使头节点对应的回收线程挂掉了，头结点也不会被删除（仔细体会为什么？）
                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        //确保当前 weakOrderQueue 节点在被GC之前，我们已经回收掉它所有的占用空间（也就是weakOrderQueue中的每一个link）
                        cursor.reclaimAllSpaceAndUnlink();
                        //利用prev指针删除cursor节点
                        prev.setNext(next);
                    }
                } else {
                    //如果当前cursor节点没有待回收对象可转移，那么就继续遍历链表获取下一个weakOrderQueue节点
                    prev = cursor;
                }

                //向后移动prev,cursor指针继续遍历weakOrderQueue链表
                cursor = next;

                // 退出条件为：遍历到最后一个节点或往stack转移成功了
            } while (cursor != null && !success);

            // 重新更新这两个指针
            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            //判断当前线程是否为创建线程  对象池的回收原则是谁创建，最终由谁回收。其他线程只是将回收对象放入weakOrderQueue中
            //最终是要回收到创建线程对应的stack中的
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 如果当前线程正是创建对象的线程，则直接进行回收 直接放入与创建线程关联的stack中
                pushNow(item);
            }
//            else if(threadRef.get() == null){
//                // todo 这里是个bug，应该考虑创建stack的线程挂掉的情况（threadRef.get() = null），此时在为创建线程回收对象已经没有任何意义了
//                // todo 这里应该尽早处理掉 threadRef.get() == null 的情况，因为创建线程已经死掉，此时在为创建线程回收对象已经没有任何意义了，这种情况直接 return 掉就好
                  // todo 这个修复方案也不完美，从引用链：池化对象-> defaultHandle-> stack （弱引用）-> 创建线程，如果创建线程挂掉了
                  // 那么stack里的空闲池化对象再也不会被使用到了，照理说stack早晚应该被回收，由于用户迟迟没有把池化对象归还（调用recycler方法）
            //导致stack一直被引用，造成内存泄漏。所以当创建线程死掉后需要将stack也能回收
//                // when the thread that belonged to the Stack was died or GC'ed，
//                // There is no need to add this item to WeakOrderQueue-linked-list which belonged to the Stack any more
//                item.stack = null;
//            }
            else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 当前线程不是创建线程，则将回收对象放入创建线程对应的stack中的weakOrderQueue链表相应节点中（currentThread对应的节点）
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            // 池化对象被回收前 recycleId = lastRecycleId = 0，如果其中之一不为0 说明已经被回收了（可能是创建线程或者回收线程干的）
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //此处是由创建线程回收，则将池化对象的recycleId与lastRecycleId设置为创建线程Id-OWN_THREAD_ID
            //注意这里的OWN_THREAD_ID是一个固定的值，是因为这里的视角是池化对象的视角，只需要区分创建线程和非创建线程即可。
            //对于一个池化对象来说创建线程只有一个 所以用一个固定的OWN_THREAD_ID来表示创建线程Id
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //如果当前stack中池化对象的容量已经超过最大容量 则丢弃该池化对象（不回收到stack中了）
            //为了避免池化对象的急速膨胀，这里只会回收1/8的对象，剩下的对象都需要丢弃
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                // 丢弃对象
                return;
            }
            //当前线程对应的stack容量已满但是还没超过最大容量限制，则对stack进行扩容
            if (size == elements.length) {
                // 如果当前Stack容量已满但是还没超过最大容量限制，则对stack进行扩容。一次性扩容两倍但不能超过最大容量。
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            //将对象回收至当前stack中，完成回收
            elements[size] = item;
            //更新当前stack的栈顶指针
            this.size = size + 1;
        }

        /**
         * 将待回收的池化对象放入stack的 WeakOrderQueue 列表里，稍后让stack创建线程来进行回收
         * @param item 待回收的池化对象
         * @param thread 回收线程
         * @author wenpan 2024/1/7 12:12 下午
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            //maxDelayQueues == 0 表示不支持对象的跨线程回收
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                //直接丢弃
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            //注意这里的视角切换，当前线程为回收线程
            // 从回收线程threadLocal里获取map(如果是第一次get，则创建该map)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //获取当前回收对象属于的stack 由当前线程帮助其回收  注意这里是跨线程回收 当前线程并不是创建线程
            WeakOrderQueue queue = delayedRecycled.get(this);
            //queue == null 表示当前线程是第一次为该stack回收对象
            if (queue == null) {
                //maxDelayedQueues指示一个线程最多可以帮助多少个线程回收其创建的对象
                //delayedRecycled.size()表示当前线程已经帮助多少个线程回收对象
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    //如果超过指定帮助线程个数，则停止为其创建WeakOrderQueue，停止为其回收对象
                    //WeakOrderQueue.DUMMY这里是一个标识，后边遇到这个标识  就不会为其回收对象了
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 创建为回收线程对应的WeakOrderQueue节点以便保存当前线程为其回收的对象【这里正是多线程无锁化回收对象的核心所在】
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // 创建失败则丢弃对象
                    // drop object
                    return;
                }
                //在当前线程的threadLocal中建立 回收对象对应的stack 与 weakOrderQueue的对应关系
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                // 如果queue的值是WeakOrderQueue.DUMMY 表示当前已经超过了允许帮助的线程数 直接丢弃对象
                return;
            }

            //回收线程为对象的创建线程回收对象  放入对应的weakOrderQueue中，点进去看详细流程
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 创建一个 WeakOrderQueue
         * 当回收线程第一次为创建线程回收对象的时候，需要在创建线程对应Stack结构中的WeakOrderQueue链表中创建与回收线程对应的WeakOrderQueue节点
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            // 如果该池化对象还没有被回收到对象池
            if (!handle.hasBeenRecycled) {
                //回收计数handleRecycleCount 初始值为8 这样可以保证创建的第一个对象可以被池化回收。interval控制回收频率 8个对象回收一个
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                //回收一个对象后，回收计数清零
                handleRecycleCount = 0;
                //设置defaultHandler的回收标识为true
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        // 从结构设计角度上来说，池化对象是隶属于其创建线程对应的Stack结构的，由于这层结构关系的存在，池化对象的DefaultHandler应该由Stack来进行创建。
        // 可以看到这个stack里提供了创建handle的方法
        DefaultHandle<T> newHandle() {
            // 可以看到这里将stack传递给了handle，该handle就知道是哪个stack创建了他
            return new DefaultHandle<T>(this);
        }
    }
}
