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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used 这是JDK NIO 原生的channel
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        super(parent, socket);
        // 创建用于承载 NioSocketChannel 配置的配置对象 NioSocketChannelConfig
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        // 客户端NioSocketChannel判断是否激活的标准为是否处于Connected状态
        return ch.isOpen() && ch.isConnected();
    }

    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    @Override
    public boolean isInputShutdown() {
        // 判断该channel（也就是socket）的读通道是否关闭
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        // 关闭channel的写通道，TCP是一个面向连接的、可靠的、基于字节流的全双工通道（一个读通道一个写通道，所以可以分开关闭）
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        // 我们可以看出对于 shutdownOutput 的操作也是必须在 Reactor 线程中完成。
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdownInput() {
        // 关闭读通道
        // 调用 shutdownInput 方法关闭服务端 Channel 的读通道，如果此时 Socket 接收缓冲区还有数据，则会将这些数据统统丢弃。
        // 注意关闭读通道并不会向对端发送 FIN ，此时服务端连接依然处于 CLOSE_WAIT 状态
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            //调用底层JDK socketChannel关闭接收方向的通道
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        // 关闭底层 JDK 中的 SocketChannel
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        // 设置allocHandle中本次尝试读取的数据量，byteBuf.writableBytes() 表示该byteBuf 里还可写的容量
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        // 从 jdk nio 的channel （socket）就绪缓存区中读取数据到 bytebuf中
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        // sendFile零拷贝
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        //获取NioSocketChannel中封装的jdk nio底层socketChannel
        SocketChannel ch = javaChannel();
        // 没轮write-loop最大写入次数 默认为16 目的是为了保证SubReactor可以平均的处理注册其上的所有Channel
        int writeSpinCount = config().getWriteSpinCount();
        // write-loop
        do {
            if (in.isEmpty()) {
                // All written so clear OP_WRITE
                // 如果全部数据已经写完 则移除OP_WRITE事件并直接退出writeLoop
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            //  SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数  293976 = 146988 << 1
            // 从 ChannelConfig 中获取本次 write loop 最大允许发送的字节数 maxBytesPerGatheringWrite 。初始值为
            // SO_SNDBUF大小 * 2 = 293976 = 146988 << 1，最小值为 2048。
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();
            // 将ChannelOutboundBuffer中缓存的DirectBuffer转换成JDK NIO 的 ByteBuffer
            // 由于最终 Netty 会调用 JDK NIO 的 SocketChannel 发送数据，所以这里需要首先将当前 Channel 中的写缓冲队列 ChannelOutboundBuffer
            // 里存储的 DirectByteBuffer（ Netty 中的 ByteBuffer 实现）转换成 JDK NIO 的 ByteBuffer 类型。最终将转换后的待发送数据存储在ByteBuffer[] nioBuffers 数组中
            // maxBytesPerGatheringWrite: 表示本次 write loop 中最多从 ChannelOutboundBuffer 中转换 maxBytesPerGatheringWrite 个字节出来。也就是本次 write loop 最多能发送多少字节
            // 1024 : 本次 write loop 最多转换 1024 个 ByteBuffer（ JDK NIO 实现）。也就是说本次 write loop 最多批量发送多少个 ByteBuffer
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);
            //本次write loop中需要发送的 JDK ByteBuffer个数
            // 获取本次 write loop 总共需要发送的 ByteBuffer 数量 nioBufferCnt 。注意这里已经变成了 JDK NIO 实现的 ByteBuffer 了。
            int nioBufferCnt = in.nioBufferCount();

            // ======================================上面都是发送数据前的准备工作======================================

            // Always use nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            // 向底层jdk nio socketChannel发送数据
            switch (nioBufferCnt) {
                //这里主要是针对 网络传输文件数据 的处理 FileRegion
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    // 零拷贝发送网络文件
                    writeSpinCount -= doWrite0(in);
                    break;
                    // 处理单个NioByteBuffer发送的情况
                case 1: {
                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    final int localWrittenBytes = ch.write(buffer);
                    if (localWrittenBytes <= 0) {
                        //如果当前Socket发送缓冲区满了写不进去了，则注册OP_WRITE事件，等待Socket发送缓冲区可写时 在写
                        // SubReactor在处理OP_WRITE事件时，直接调用flush方法
                        incompleteWrite(true);
                        return;
                    }
                    //根据当前实际写入情况调整 maxBytesPerGatheringWrite数值
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    //如果ChannelOutboundBuffer中的某个Entry被全部写入 则删除该Entry
                    // 如果Entry被写入了一部分 还有一部分未写入  则更新Entry中的readIndex 等待下次writeLoop继续写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
                // 批量处理多个NioByteBuffers发送的情况
                default: {
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe
                    // ChannelOutboundBuffer中总共待写入数据的字节数
                    long attemptedBytes = in.nioBufferSize();
                    //批量写入
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    //根据实际写入情况调整一次写入数据大小的最大值
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);
                    //移除全部写完的BUffer，如果只写了部分数据则更新buffer的readerIndex，下一个writeLoop写入
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);

        // 处理本轮write loop未写完的情况
        // 1、writeSpinCount == 0：这种情况很好理解，就是已经写满了 16 次，但是还没写完，同时 Socket 的写缓冲区未满，还可以继续写入。这种情况下即使 Socket 还可以继续写入，
        // Netty 也不会再去写了，因为执行 flush 操作的是 reactor 线程，而 reactor 线程负责执行注册在它上边的所有 channel 的 IO 操作，Netty 不会允许 reactor
        // 线程一直在一个 channel 上执行 IO 操作，reactor 线程的执行时间需要均匀的分配到每个 channel 上。所以这里 Netty 会停下，转而去处理其他 channel 上的 IO 事件。
        // 2、writeSpinCount < 0：这种情况有点不好理解，我们在介绍 Netty 通过零拷贝的方式传输网络文件也就是这里的 case 0 分支逻辑时，详细介绍了 doWrite0 方法的几种返回值，
        // 当 Netty 在传输文件的过程中发现 Socket 缓冲区已满无法在继续写入数据时，会返回 WRITE_STATUS_SNDBUF_FULL = Integer.MAX_VALUE，这就使得 writeSpinCount的值 < 0。
        // 随后 break 掉 write loop 来到 incompleteWrite(writeSpinCount < 0) 方法中，最后会在 incompleteWrite 方法中向 reactor 注册 OP_WRITE 事件。
        // 当 Socket 缓冲区变得可写时，epoll 会通知 reactor 线程继续发送文件。
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    /**
     * NioSocketChannelUnsafe 用于封装NioSocketChannel对底层的操作
     * @author wenpan 2023/12/23 6:22 下午
     */
    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        /**
         * 要理解这段逻辑，首先我们需要理解 SO_LINGER 这个 Socket 选项，他会影响 Socket 的关闭行为。
         * 在默认情况下，当我们调用 Socket 的 close 方法后 ，close 方法会立即返回，剩下的事情会交给内核协议栈帮助我们处理，如果此时 Socket
         * 对应的发送缓冲区还有数据待发送，接下来内核协议栈会将 Socket 发送缓冲区的数据发送出去，随后会向对端发送 FIN 包关闭连接。
         * 注意：此时应用程序是无法感知到这些数据是否已经发送到对端的，因为应用程序在调用 close 方法后就立马返回了，剩下的这些都是内核在替我们完成。
         * 接着主动关闭方就进入了 TCP 四次挥手的关闭流程最后进入TIME_WAIT状态。而 SO_LINGER 选项会控制调用 close 方法关闭 Socket 的行为。
         * @return executor 返回一个 Executor，这个 Executor 用于执行真正的 Channel 关闭任务
         * @author wenpan 2024/1/27 12:12 下午
         */
        @Override
        protected Executor prepareToClose() {
            try {
                //在设置SO_LINGER后，channel会延时关闭，在延时期间我们仍然可以进行读写，这样会导致io线程eventloop不断的循环浪费cpu资源
                //所以需要在延时关闭期间 将channel注册的事件全部取消。
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    // 从reactor上取消该channel的注册
                    doDeregister();
                    /**
                     * 设置了SO_LINGER,不管是阻塞socket还是非阻塞socket，在关闭的时候都会发生阻塞，所以这里不能使用Reactor线程来
                     * 执行关闭任务，否则Reactor线程就会被阻塞。
                     * */
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            //在没有设置SO_LINGER的情况下，可以使用Reactor线程来执行关闭任务(返回null表示用reactor线程来执行channel的关闭)
            return null;
        }
    }

    /**
     * NioSocketChannel 相关配置信息
     * @author wenpan 2023/12/24 10:39 上午
     */
    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        //293976 = 146988 << 1
        //SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数，最小值为2048
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        
        /**
         * 构造函数
         * @param channel 这个config所关联的netty包装后的channel
         * @param javaSocket  JDK NIO 原生的 socket
         * @author wenpan 2023/12/24 10:39 上午
         */
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            // 每次扩大1倍
            int newSendBufferSize = getSendBufferSize() << 1;
            // 293976 = 146988 << 1
            // SO_SNDBUF设置的发送缓冲区大小 * 2 作为 最大写入字节数
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(newSendBufferSize);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
