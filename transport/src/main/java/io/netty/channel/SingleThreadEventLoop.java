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
package io.netty.channel;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}s that execute all its submitted tasks in a single thread.
 * Reactor负责执行的异步任务分为三类：
 * 普通任务：这是Netty最主要执行的异步任务，存放在普通任务队列taskQueue中。在NioEventLoop构造函数中创建。
 * 定时任务： 存放在优先级队列中。后续我们介绍。
 * 尾部任务： 存放于尾部任务队列tailTasks中，尾部任务一般不常用，在普通任务执行完后 Reactor线程会执行尾部任务。**使用场景：**比如对Netty 的运行状态做一些统计数据，例如任务循环的耗时、占用物理内存的大小等等都可以向尾部队列添加一个收尾任务完成统计数据的实时更新。
 * SingleThreadEventLoop负责对尾部任务队列tailTasks进行管理。并且提供Channel向Reactor注册的行为。
 * SingleThreadEventExecutor主要负责对普通任务队列的管理，以及异步任务的执行，Reactor线程的启停。
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

    // 任务队列大小，默认是无界队列
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));
    // 尾部任务队列
    private final Queue<Runnable> tailTasks;

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        //尾部队列 执行一些统计任务 不常用
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        //尾部队列 执行一些统计任务 不常用
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    /**
     * 构造函数
     * @param parent parent为Reactor所属的NioEventLoopGroup Reactor线程组
     * @param executor 用于启动Reactor线程的executor -> ThreadPerTaskExecutor
     * @param addTaskWakesUp 向Reactor添加任务时，是否唤醒Selector停止轮询IO就绪事件，马上执行异步任务
     * @param taskQueue 存放reactor异步任务的queue
     * @param tailTaskQueue 尾部队列
     * @param rejectedExecutionHandler 任务队列满时的拒绝策略
     * @author wenpan 2023/12/10 3:02 下午
     */
    protected SingleThreadEventLoop(EventLoopGroup parent,
                                    Executor executor,
                                    boolean addTaskWakesUp,
                                    Queue<Runnable> taskQueue,
                                    Queue<Runnable> tailTaskQueue,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, taskQueue, rejectedExecutionHandler);
        //尾部队列 执行一些统计任务 不常用
        tailTasks = ObjectUtil.checkNotNull(tailTaskQueue, "tailTaskQueue");
    }

    @Override
    public EventLoopGroup parent() {
        return (EventLoopGroup) super.parent();
    }

    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    @Override
    public ChannelFuture register(Channel channel) {
        // 将channel和eventLoop包装成DefaultChannelPromise对象，注册channel到绑定的Reactor上，这里传入的是 DefaultChannelPromise
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        //unsafe负责channel底层的各种操作
        // 获取到channel上的unsafe对象，然后利用unsafe对象将channel注册到Reactor上
        // 对于server端NIOServerSocketChannel来说这里的unsafe是 io.netty.channel.nio.AbstractNioMessageChannel.NioMessageUnsafe
        // 对于client的NIOSocketChannel来说这里的unsafe是 io.netty.channel.nio.AbstractNioByteChannel.NioByteUnsafe
        promise.channel().unsafe().register(this, promise);
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        ObjectUtil.checkNotNull(channel, "channel");
        channel.unsafe().register(this, promise);
        return promise;
    }

    /**
     * Adds a task to be run once at the end of next (or current) {@code eventloop} iteration.
     *
     * @param task to be added.
     */
    @UnstableApi
    public final void executeAfterEventLoopIteration(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (isShutdown()) {
            reject();
        }

        if (!tailTasks.offer(task)) {
            reject(task);
        }

        if (!(task instanceof LazyRunnable) && wakesUpForTask(task)) {
            wakeup(inEventLoop());
        }
    }

    /**
     * Removes a task that was added previously via {@link #executeAfterEventLoopIteration(Runnable)}.
     *
     * @param task to be removed.
     *
     * @return {@code true} if the task was removed as a result of this call.
     */
    @UnstableApi
    final boolean removeAfterEventLoopIterationTask(Runnable task) {
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    protected void afterRunningAllTasks() {
        runAllTasksFrom(tailTasks);
    }

    @Override
    protected boolean hasTasks() {
        // 检查普通队列和tail队列里是否有待执行的任务
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    @Override
    public int pendingTasks() {
        return super.pendingTasks() + tailTasks.size();
    }

    /**
     * Returns the number of {@link Channel}s registered with this {@link EventLoop} or {@code -1}
     * if operation is not supported. The returned value is not guaranteed to be exact accurate and
     * should be viewed as a best effort.
     */
    @UnstableApi
    public int registeredChannels() {
        return -1;
    }
}
