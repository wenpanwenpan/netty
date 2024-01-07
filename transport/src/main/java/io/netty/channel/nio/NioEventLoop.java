/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 * NioEventLoop 可以理解为就是NIO对reactor的实现，其实Reactor我们可以看作是一个单线程的线程池，只有一个线程用来执行IO就绪事件的监听，IO事件的处理，异步任务的执行
 * 可以看到该类继承了SingleThreadEventLoop，SingleThreadEventLoop从名字就能直观的看出，他只有一个线程
 * 也可以把Reactor理解成为一个单线程的线程池，类似于JDK中的SingleThreadExecutor，仅用一个线程来执行轮询IO就绪事件，处理IO就绪事件，
 * 执行异步任务。同时待执行的异步任务保存在Reactor里的taskQueue中。
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    //Selector优化开关 默认开启 为了遍历的效率 会对Selector中的SelectedKeys进行数据结构优化
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            // 从selector上非阻塞的获取一下是否有io就绪事件
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * 可以把Selector理解为操作对操作系统提供的Select,poll,epoll等多路复用技术的封装，它是JDK NIO对操作系统内核提供的这些IO多路复用技术的封装。
     */
    private Selector selector;
    // JDK原生的selector 或 经过netty优化后的selector（取决于是否要用netty优化配置打开）
    private Selector unwrappedSelector;
    // netty优化JDK NIO中的selector时会通过反射替换selector对象中的selectedKeySet保存就绪的selectKey（看这个字段赋值的地方）
    // 该字段为持有selector对象selectedKeys的引用，当IO事件就绪时，直接从这里获取（后续Reactor线程就会直接从
    // io.netty.channel.nio.NioEventLoop#selectedKeys中获取IO就绪的SocketChannel，而不是从selector里了）
    private SelectedSelectionKeySet selectedKeys;

    // 用于创建JDK NIO Selector,ServerSocketChannel
    private final SelectorProvider provider;

    // 表示reactor线程是苏醒状态
    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    // 用来存放Reactor从Selector上被唤醒的时间点，设置为最近需要被执行定时任务的deadline，
    // 如果当前并没有定时任务需要执行，那么就设置为Long.MAX_VALUE一直阻塞，直到有IO就绪事件到达或者有异步任务需要执行
    // 这里Netty用了一个AtomicLong类型的变量nextWakeupNanos，既能表示当前Reactor线程的状态，又能表示Reactor线程的阻塞超时时间
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    //Selector轮询策略 决定什么时候轮询，什么时候处理IO事件，什么时候执行异步任务
    private final SelectStrategy selectStrategy;
    // reactor线程执行io事件和执行异步任务的时间比例，默认为 50%
    private volatile int ioRatio = 50;
    // 从该reactor上取消绑定的selectionKey（channel）个数
    //记录Selector上移除socketChannel的个数 达到256个 则需要将无效的selectKey从SelectedKeys集合中清除掉
    private int cancelledKeys;
    //用于及时从selectedKeys中清除失效的selectKey 比如 socketChannel从selector上被用户移除
    private boolean needsToSelectAgain;

    /**
     * reactor构造函数
     * @param parent 标识这个reactor属于哪个reactor组（也就是 NioEventLoopGroup）
     * @param executor 用于创建reactor里的线程
     * @param selectorProvider selector创建器，SelectorProvider会根据操作系统的不同选择JDK在不同操作系统版本下的对应Selector的实现。Linux下会选择Epoll，Mac下会选择Kqueue
     * @param strategy 当socket向sub reactor上注册时选择sub reactor的策略
     * @param rejectedExecutionHandler 当向这个reactor提交异步任务时，若任务队列满了采取的拒绝策略
     * @param queueFactory reactor任务队列（reactor中一共有3个队列，分别是任务队列、tail队列、定时任务队列）
     * @author wenpan 2023/12/10 1:53 下午
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        // 看看这里的reactor上的 用于保存异步任务的队列是如何创建出来的？
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // openSelector是NioEventLoop类中用于创建IO多路复用的Selector，并对创建出来的JDK NIO 原生的Selector进行性能优化。
        final SelectorTuple selectorTuple = openSelector();
        //通过用SelectedSelectionKeySet装饰后的unwrappedSelector
        this.selector = selectorTuple.selector;
        // netty对原生NIO创建的selector封装后的selector
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    /**
     * 创建用于保存reactor上的异步任务的queue
     */
    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            //任务队列大小，默认是无界队列
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        // JDK原生的selector 或者 被netty优化过的selector
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 通过JDK NIO SelectorProvider创建原生Selector
            // SelectorProvider会根据操作系统的不同选择JDK在不同操作系统版本下的对应Selector的实现。Linux下会选择Epoll，Mac下会选择Kqueue。
            // 选择的过程具体体现在哪里呢？那就是创建provider的时候 @see java.nio.channels.spi.SelectorProvider.provider
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // Selector不优化开关（默认为false，表示要优化nio的selector）
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            // JDK NIO原生Selector ，Selector优化开关 默认开启需要对Selector进行优化，如果为true则表示不优化，这里直接返回原生的selector
            return new SelectorTuple(unwrappedSelector);
        }

        // 获取JDK NIO原生Selector的抽象实现类sun.nio.ch.SelectorImpl。JDK NIO原生Selector的实现均继承于该抽象类
        // 用于判断由SelectorProvider创建出来的Selector是否为JDK默认实现（SelectorProvider第三种加载方式）。
        // 因为SelectorProvider可以是自定义加载，所以它创建出来的Selector并不一定是JDK NIO 原生的
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // JDK原生的selector抽象类，JDK提供的selector实现都会继承该类，由于该类在JDK包内，不方便添加注释，所以我拷贝一份
                    /*
                     public abstract class SelectorImpl extends AbstractSelector {

                         // The set of keys with data ready for an operation
                         // IO就绪的SelectionKey（里面包裹着channel），这里用的是set集合，netty对原生JDK的selector优化点就在这里，
                         // 会利用反射将这个set集合替换为数组
                         protected Set<SelectionKey> selectedKeys;

                         // The set of keys registered with this Selector
                         //注册在该Selector上的所有SelectionKey（里面包裹着channel），类比注册到epoll对象的红黑树上的所有socket
                         // 这里用的是set集合，netty对原生JDK的selector优化点就在这里， 会利用反射将这个set集合替换为数组
                         protected HashSet<SelectionKey> keys;

                         // Public views of the key sets
                         //用于向调用线程返回的keys，不可变
                         private Set<SelectionKey> publicKeys;             // Immutable
                         //当有IO就绪的SelectionKey时，向调用线程返回。只可删除其中元素，不可增加
                         private Set<SelectionKey> publicSelectedKeys;     // Removal allowed, but not addition

                         protected SelectorImpl(SelectorProvider sp) {
                             super(sp);
                             keys = new HashSet<SelectionKey>();
                             selectedKeys = new HashSet<SelectionKey>();
                             if (Util.atBugLevel("1.4")) {
                             publicKeys = keys;
                             publicSelectedKeys = selectedKeys;
                             } else {
                             //不可变
                             publicKeys = Collections.unmodifiableSet(keys);
                             //只可删除其中元素，不可增加
                             publicSelectedKeys = Util.ungrowableSet(selectedKeys);
                             }
                         }
                     }
                     */
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        //判断是否可以对Selector进行优化，这里主要针对JDK NIO原生Selector的实现类进行优化，因为SelectorProvider可以加载的是自定义Selector实现
        //如果SelectorProvider创建的Selector不是JDK原生sun.nio.ch.SelectorImpl的实现类，那么无法进行优化，直接返回
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            // 不对selector进行优化，直接返回原生的selector
            return new SelectorTuple(unwrappedSelector);
        }

        // JDK原生的selector实现类的Class
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 创建SelectedSelectionKeySet 通过反射替换掉sun.nio.ch.SelectorImpl类中selectedKeys和publicSelectedKeys的默认HashSet实现。
        // 可以看到 SelectedSelectionKeySet 中是以数组来表示的SelectionKey，而不是set集合，为什么要替换？见SelectedSelectionKeySet注释
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 获取到原生selector中的 selectedKeys 属性（set）
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    // 获取到原生selector中的 publicSelectedKeys 属性(set)
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // Java9版本以上通过sun.misc.Unsafe设置字段值的方式
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 用 selectedKeySet 替换JDK原生selector中的 selectedKeys 属性
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    // 用 selectedKeySet 替换JDK原生selector中的 publicSelectedKeys 属性
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        // 优化出现异常，则使用原生的selector
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // selector中io就绪的socket集合selectedKeySet保存到selectedKeys属性上，方便在NioEventLoop中直接操作io就绪的channel，而不用再访问selector了
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 到这里 unwrappedSelector 就是被netty优化后的selector了
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        // 老套路了，如果当前线程不是该reactor的线程，那么就往这个reactor线程的队列提交一个任务
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    // 重建selector
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 这个方法就是reactor线程运转的核心逻辑了，可以看到是一个死循环
     * main reactor线程的运行逻辑和sub reactor线程的运行逻辑都是这个
     */
    @Override
    protected void run() {
        // 记录轮询次数 用于解决JDK epoll的空轮训bug
        int selectCnt = 0;
        // 可以看到reactor线程在这里是以死循环的方式执行，并且吃掉了异常
        // 以下核心逻辑是有任务需要执行，则Reactor线程立马执行异步任务，如果没有异步任务执行，则进行轮询IO事件
        for (;;) {
            try {
                int strategy;
                try {
                    // 1、根据轮询策略获取轮询结果 这里的hasTasks()主要检查的是普通队列和尾部队列中是否有异步任务等待执行
                    // 若 strategy > 0 则表示selector上有io就绪的channel，且 strategy 等于几就表示有几个io就绪的channel
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                        // 2、重新开启一轮IO轮询
                    case SelectStrategy.CONTINUE:
                        continue;

                        // 3、Reactor线程进行自旋轮询，由于NIO 不支持自旋操作，所以这里直接跳到SelectStrategy.SELECT策略
                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                        // 4、此时没有任何异步任务需要执行，Reactor线程可以安心的阻塞在Selector上等待IO就绪事件的来临
                    case SelectStrategy.SELECT:
                        // 4.1 当前没有异步任务执行，Reactor线程可以放心的阻塞等待IO就绪事件
                        // 从定时任务队列中取出即将快要执行的定时任务deadline，nextScheduledTaskDeadlineNanos方法会返回当前Reactor定时任务队列中最近的一个定时任务deadline时间点
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        // 等于 -1 则说明没有定时任务要执行，则将定时任务要执行的时间设置为long的最大值，也就是永不执行
                        if (curDeadlineNanos == -1L) {
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        // 最早执行定时任务的deadline作为 select的阻塞时间，意思是到了定时任务的执行时间，
                        // 不管有无IO就绪事件，必须唤醒selector，从而使reactor线程执行定时任务
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            //4.2 再次检查普通任务队列中是否有异步任务,没有的话开始select阻塞轮询IO就绪事件
                            if (!hasTasks()) {
                                // 无定时任务，无普通任务执行时，开始轮询IO就绪事件，没有就一直阻塞 直到唤醒条件成立
                                // 带超时时间的select，这里的curDeadlineNanos就是最近一次待执行的定时任务时间
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            // 4.3 执行到这里说明Reactor已经从Selector上被唤醒了，设置Reactor的状态为苏醒状态AWAKE
                            // lazySet优化不必要的volatile操作，不使用内存屏障，不保证写操作的可见性（单线程不需要保证）
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    //5、 出现io异常则重建selector并且将selectCnt置为0
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;
                // 调整Reactor线程执行IO事件和执行异步任务的CPU时间比例 默认50，表示执行IO事件和异步任务的时间比例是一比一
                final int ioRatio = this.ioRatio;
                boolean ranTasks;
                // 6、先一股脑执行IO事件，在一股脑执行异步任务（无时间限制）
                if (ioRatio == 100) {
                    try {
                        // strategy > 0 表示selector上有io就绪事件
                        if (strategy > 0) {
                            //如果有IO就绪事件 则处理IO就绪事件
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        // 一股脑处理所有异步任务，可以看到这个 runAllTasks 是没有超时时间参数的，对比下面的带时间参数的 runAllTasks
                        ranTasks = runAllTasks();
                    }
                    // 7、如果ioRatio不是100，则先判断是否有io就绪事件，如果有，则先执行IO事件 用时ioTime，执行异步任务只能用时ioTime * (100 - ioRatio) / ioRatio
                } else if (strategy > 0) {
                    // 记录开始处理io就绪事件的时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理io就绪事件（可以看到即使ioRatio ！= 100，这里也会先一股脑的去将selector上的所有io就绪事件执行完成，
                        // 然后再去按时间比例执行异步任务，这也是合理的，毕竟netty是一个处理通信的io框架嘛，不是异步任务框架，所以优先保证io事件得到处理）
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 时间按比例执行异步任务和io就绪任务，执行异步任务只能用时 ioTime * (100 - ioRatio) / ioRatio
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 执行任务，任务执行时间最多为 ioTime * (100 - ioRatio) / ioRatio
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    // 8、没有IO就绪事件处理，则只执行异步任务 最多执行64个 防止Reactor线程处理异步任务时间过长而导致 I/O 事件阻塞
                    // 这里传入 timeoutNanos = 0 ，最多能执行64个异步任务
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                // 9、这里就是netty解决JDK 空轮询bug的地方了
                // 至少执行了一次异步任务，或者至少处理了一次以上的io就绪事件（strategy > 0 表示至少有一个io就绪的channel）
                if (ranTasks || strategy > 0) {
                    /*
                     * 走到这里的条件是 既没有IO就绪事件，也没有异步任务，Reactor线程从Selector上被异常唤醒
                     * 这种情况可能是已经触发了JDK Epoll的空轮询BUG，如果这种情况持续512次 则认为可能已经触发BUG，于是重建Selector
                     * */
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    // 由于至少处理了一次io事件或执行过异步任务，所以要将selectCnt重新置为0
                    selectCnt = 0;
                    // reactor线程在selector上阻塞时异常被唤醒（即没有io就绪事件也没有异步任务需要处理）
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    // 既没有IO就绪事件，也没有异步任务，Reactor线程从Selector上被异常唤醒 触发JDK Epoll空轮训BUG
                    // 由于 unexpectedSelectorWakeup 中重建了一个selector，所以这里当然需要重置轮询次数了
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                // error异常才向外抛出
                throw (Error) e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    // 线程关闭了，比如：bossGroup.shutdownGracefully(); 就会关闭group中每个eventLoop（reactor），从而关闭reactor线程
                    if (isShuttingDown()) {
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw (Error) e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            // 重建selector，这里可以看到netty并没有从根本上解决JDK 空轮询的bug，
            // 而是巧妙地使用检测到空轮询后重新创建一个selector来替换他来解决了空轮询问题
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    // 处理selector上的io事件
    private void processSelectedKeys() {
        //是否采用netty优化后的selectedKey集合类型 是由变量DISABLE_KEY_SET_OPTIMIZATION决定的 默认为false，
        // 如果开启了netty对selector的优化，则selectedKeys不为空，selectedKeys表示selector中的selectedKeys（有io就绪事件发生的channel）
        if (selectedKeys != null) {
            // 1、直接从 selectedKeys 上获取有io就绪事件发生的channel并处理
            processSelectedKeysOptimized();
        } else {
            // 2、从JDK nio原生的selector上获取有就绪事件发生的channel
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        // 将key（也就是channel），从该reactor（reactor中的selector）上取消绑定，该方法调用完成后SelectionKey的valid属性就是false了
        // SelectionKey#cancel方法调用后，Selector会将要取消的这个SelectionKey加入到Selector中的cancelledKeys集合中，可点击进去看
        key.cancel();
        // 当从selector中移除的socketChannel数量达到256个，设置needsToSelectAgain为true
        // 在io.netty.channel.nio.NioEventLoop.processSelectedKeysPlain 中重新做一次轮询，将失效的selectKey移除，
        // 以保证selectKeySet的有效性
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            // 获取io就绪的 SelectionKey SelectionKey就相当于是Channel在Selector中的一种表示，当Channel上有IO就绪事件时，
            // Selector会将Channel对应的SelectionKey返回给Reactor线程，我们可以通过返回的这个SelectionKey里的attachment属性获取到对应的Netty自定义Channel。
            final SelectionKey k = i.next();
            // 这个a就是netty的channel向selector注册时，将自己附着在attachment上的channel
            final Object a = k.attachment();
            //注意每次迭代末尾的keyIterator.remove()调用。Selector不会自己从已选择键集中移除SelectionKey实例。
            //必须在处理完通道时自己移除。下次该通道变成就绪时，Selector会再次将其放入已选择键集中。
            // 当我们通过k.attachment()获取到Netty自定义的Channel时，就需要把这个Channel对应的SelectionKey从Selector的就绪集合
            // Set<SelectionKey> selectedKeys中删除。因为Selector自己不会主动删除已经处理完的SelectionKey，需要调用者自己主动删除，
            // 这样当这个Channel再次IO就绪时，Selector会再次将这个Channel对应的SelectionKey放入就绪集合Set<SelectionKey> selectedKeys中
            i.remove();

            // 无论是服务端使用的NioServerSocketChannel还是客户端使用的NioSocketChannel都属于AbstractNioChannel。Channel上的IO事件是由Netty框架负责处理
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                // NioTask和Channel其实本质上是一样的都是负责处理Channel上的IO就绪事件，只不过一个是用户自定义处理，一个是Netty框架处理。这里我们重点关注Channel的IO处理逻辑
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            // 处理完了所有io就绪的channel
            if (!i.hasNext()) {
                break;
            }

            // 目的是再次进入for循环 移除失效的selectKey(socketChannel可能从selector上移除)
            // 我们知道Channel可以将自己注册到Selector上，那么当然也可以将自己从Selector上取消移除。首先我们来看下在什么情况下会将needsToSelectAgain这个变量设置为true
            // 可以看到在 io.netty.channel.nio.AbstractNioChannel.doDeregister 中将needsToSelectAgain设置为true，条件是：当从selector中移除的socketChannel数量达到256个
            // 所以可以粗略的理解为当从selector中移除的socketChannel数量达到256个，则 needsToSelectAgain 为true
            if (needsToSelectAgain) {
                // 由于采用的是JDK NIO 原生的Selector，所以只需要执行SelectAgain就可以，Selector会自动清除无效Key(包括从selector所管理的所有
                // key集合中删除，从selector的就绪key集合中删除)
                selectAgain();
                // 从selector上获取就绪的channel集合
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                // 如果已经没有就绪的io事件了，则跳出循环（处理io事件完成）
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    // 继续处理下一个channel上的io事件
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    // 处理经过netty优化过的selector上的 io 就绪事件
    private void processSelectedKeysOptimized() {
        // 在openSelector的时候将JDK中selector实现类中的selectedKeys和publicSelectKeys字段类型
        // 由原来的HashSet类型替换为 Netty优化后的数组实现的SelectedSelectionKeySet类型，所以这里的selectedKeys就是selector中的io就绪的channel集合
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 获取io就绪的channel
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // Selector会在每次轮询到IO就绪事件时，将IO就绪的Channel对应的SelectionKey插入到selectedKeys集合，
            // 但是Selector只管向selectedKeys集合放入IO就绪的SelectionKey，当SelectionKey被处理完毕后，
            // Selector是不会自己主动将其从selectedKeys集合中移除的，典型的管杀不管埋。所以需要Netty自己在遍历到IO就绪的 SelectionKey后将其删除。
            // 在processSelectedKeysPlain中是直接将其从迭代器中删除。@see processSelectedKeysPlain里的 i.remove()
            // 在processSelectedKeysOptimized中将其在数组中对应的位置置为Null，方便垃圾回收。
            selectedKeys.keys[i] = null;

            // 获取netty的channel
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                // 在processSelectedKeysOptimized中由于是Netty自己实现的优化类型，所以需要Netty自己将SelectedSelectionKeySet数组
                // 中的SelectionKey全部清除，最后在执行SelectAgain
                selectedKeys.reset(i + 1);
                // 让底层selector将注册在其上的无效的channel删除
                selectAgain();
                i = -1;
            }
        }
    }

    // 处理selector上的io就绪事件
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 获取Channel的底层操作类Unsafe（对于NIOServerSocketChannel来说这里的unsafe是NioMessageUnsafe，对于NIOSocketChannel来说这里的unsafe是NioByteUnsafe）
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 如果SelectionKey已经失效则关闭对应的Channel
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            //获取IO就绪事件
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // 处理Connect事件，Netty客户端向服务端发起连接，并向sub Reactor注册Connect事件，当连接建立成功后，客户端的NioSocketChannel就会产生Connect就绪事件
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 移除对Connect事件的监听，否则Selector会一直通知 (意思就是：一旦client和server端建立连接成功后，client 的 NIOSocketChannel
                // 会被注册到某个 sub reactor 上，此时该NIOSocketChannel就会产生 connect事件，当这个事件被reactor线程处理后就需要
                // 将OP_CONNECT事件从客户端NioSocketChannel所关心的事件集合interestOps中删除。否则Selector会一直通知Connect事件就绪)，
                // 可以看到这采用了取反然后进行位运算进行移除感兴趣的 connect事件
                ops &= ~SelectionKey.OP_CONNECT;
                // 重新设置channel感兴趣的事件
                k.interestOps(ops);
                //触发channelActive事件处理Connect事件
                unsafe.finishConnect();
            }

            //处理Write事件，这里也是通过位运算来判断是否是write事件的
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // OP_WRITE事件的注册是由用户来完成的，当Socket发送缓冲区已满无法继续写入数据时，用户会向Reactor注册OP_WRITE事件，
                // 等到Socket发送缓冲区变得可写时，Reactor会收到OP_WRITE事件活跃通知，随后在这里调用客户端NioSocketChannel中的forceFlush方法将剩余数据发送出去。
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // 【重点】NIOServerSocketChannel的accept事件和NIOSocketChannel的read事件都是在这儿处理的，处理Read事件或者Accept事件，这里巧妙的使用了位运算来表达
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                // 这里可以看出Netty中处理Read事件和Accept事件都是由对应Channel中的Unsafe操作类中的read方法处理。
                // 服务端NioServerSocketChannel中的Read方法处理的是Accept事件，客户端NioSocketChannel中的Read方法处理的是Read事件。
                // 所以说如果要阅读 NioServerSocketChannel 是如何接受客户端连接并注册到sub reactor中的，那么就从这里开始
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        // 当nextWakeupNanos = AWAKE时表示当前Reactor正处于苏醒状态，
        // 既然是苏醒状态也就没有必要去执行selector.wakeup()重复唤醒Reactor了，同时也能省去这一次的系统调用开销。
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // 将reactor线程从selector上唤醒
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    // 获取JDK 原生的selector 或 被netty优化过的selector （默认会被netty优化）
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        // 非阻塞，返回值表示该selector上的io就绪事件个数
        return selector.selectNow();
    }

    private int select(long deadlineNanos) throws IOException {
        // deadlineNanos表示的就是Reactor中最近的一个定时任务执行时间点deadline，单位是纳秒。指的是一个绝对时间
        if (deadlineNanos == NONE) {
            // 无定时任务，无普通任务执行时，开始轮询IO就绪事件，没有就一直阻塞 直到唤醒条件成立
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        // 通过绝对时间计算相对时间，这里为什么要加 0.995毫秒 呢？
        // 从这里我们可以看出，Reactor在有定时任务的情况下，至少要阻塞1毫秒
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        // 执行selectAgain时立即将needsToSelectAgain置为false
        needsToSelectAgain = false;
        try {
            // 从selector上非阻塞的获取一下io就绪事件
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
