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
package io.netty.util.concurrent;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 * MultithreadEventExecutorGroup 主要用来定义创建和管理Reactor的行为。
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    //Reactor线程组中的Reactor集合
    private final EventExecutor[] children;
    // 存放reactor组中所有的reactor集合（只读）
    private final Set<EventExecutor> readonlyChildren;
    // 记录已经关闭的Reactor个数，用来判断NioEventLoopGroup中的Reactor是否已经全部关闭
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    // 关闭future, 如果所有Reactor均已关闭，设置NioEventLoopGroup中的terminationFuture为success。表示Reactor线程组关闭成功
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    //从Reactor group中选择一个特定的Reactor的选择策略 用于channel注册绑定到一个固定的Reactor上
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        // 默认为DefaultEventExecutorChooserFactory
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance. reactor线程组的创建过程
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use. 当客户端连接完成三次握手后，
     *                          Main Reactor会创建客户端连接NioSocketChannel，并将其绑定到Sub Reactor Group中的一个固定Reactor，
     *                          那么具体要绑定到哪个具体的Sub Reactor上呢？这个绑定策略就是由chooserFactory来创建的。默认为DefaultEventExecutorChooserFactory
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException(String.format("nThreads: %d (expected: > 0)", nThreads));
        }

        if (executor == null) {
            // executor用于创建reactor线程，该线程池可以看到每调用一次execute方法就新创建一个线程
            // 而创建的这个线程正是netty的核心引擎Reactor线程。Netty会将Reactor线程要做的事情封装成Runnable，丢给exexutor启动。
            // 而Reactor线程的核心就是一个死循环不停的轮询IO就绪事件，处理IO事件，执行异步任务
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }
        // 可以看到reactor的数量就是传入的 nThreads 数量
        children = new EventExecutor[nThreads];

        // 循环创建reactor group中的Reactor
        for (int i = 0; i < nThreads; i ++) {
            boolean success = false;
            try {
                // 创建reactor，@see io.netty.channel.nio.NioEventLoopGroup.newChild
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    // 一旦有某个reactor创建失败了，则关闭之前创建的reactor
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.重置下中断，让调用方感知
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        //创建channel到Reactor的绑定策略（默认是轮询）
        chooser = chooserFactory.newChooser(children);

        // 用于reactor group优雅关闭
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                //当所有Reactor关闭后 才认为是关闭成功
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        //为所有Reactor添加terminationListener
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }

        // 将group中所有的reactor添加到 childrenSet中
        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        // 通过chooser选取下一个reactor（一般是用轮询选取）
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop: for (EventExecutor l: children) {
            for (;;) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
