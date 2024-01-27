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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 * AbstractNioMessageChannel类主要是对NioServerSocketChannel底层读写行为的封装和定义，比如accept接收客户端连接
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        // 这里创建的是 NioMessageUnsafe
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    /**这个unsafe实现主要是用来操作server端channel（NIOServerSocketChannel）对底层的一些动作，具体可以看类的继承关系*/
    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        // 存放连接建立后，创建的客户端SocketChannel。对于服务端NioServerSocketChannel来说，它上边的IO数据就是客户端的连接，
        // 它的长度和类型都是固定的，所以在接收客户端连接的时候并不需要这样的一个ByteBuffer来接收，我们会将接收到的客户端连接存放在List<Object> readBuf集合中
        private final List<Object> readBuf = new ArrayList<Object>();

        /**
         * 这里从内核全连接队列读取完成三次握手后的client连接遵循如下规则：
         * 1、在限定的16次读取中，已经没有新的客户端连接要接收了。退出循环。
         * 2、从NioServerSocketChannel中读取客户端连接的次数达到了16次，无论此时是否还有客户端连接都需要退出循环（保证reactor中的异步任务也能即使得到执行）
         *
         * 当满足以上两个退出条件时，main reactor线程就会退出read loop，由于在read loop中接收到的客户端连接全部暂存在
         * List<Object> readBuf集合中,随后开始遍历readBuf，在NioServerSocketChannel的pipeline中传播ChannelRead事件
         * @author wenpan 2023/12/23 6:42 下午
         */
        @Override
        public void read() {
            //必须在Main Reactor线程中执行，确保处理接收客户端连接的线程必须为Main Reactor 线程
            assert eventLoop().inEventLoop();
            //注意下面的config和pipeline都是服务端ServerSocketChannel中的（NioServerSocketChannel的属性配置类NioServerSocketChannelConfig,它是在Reactor的启动阶段被创建出来的）
            // @see io.netty.channel.socket.nio.NioServerSocketChannel.NioServerSocketChannel(java.nio.channels.ServerSocketChannel)
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            // 创建接收数据Buffer分配器（用于分配容量大小合适的byteBuffer用来容纳接收数据）
            // 在接收连接的场景中，这里的allocHandle只是用于控制read loop的循环读取创建连接的次数。
            // 这个buffer分配器是在哪里被创建的呢？在 NioServerSocketChannel 创建的时候会创建 NioServerSocketChannelConfig 配置
            // 在配置的构造函数里就会创建这个buffer分配器，可以看到类型为 AdaptiveRecvByteBufAllocator ，顾名思义，
            // 这个类型的RecvByteBufAllocator可以根据Channel上每次到来的IO数据大小来自适应动态调整ByteBuffer的容量
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            // 每次读取数据前都要，重置bytebuf分配器里边的统计指标
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    // 可以看到这里循环在读取
                    do {
                        // 底层调用NioServerSocketChannel->doReadMessages 创建客户端 SocketChannel（可以看到客户端连接就是在这里被创建的）
                        // 返回值localRead表示接收到了多少客户端连接，客户端连接通过accept方法只会一个一个的接收，所以这里的localRead正常情况下都会返回1
                        int localRead = doReadMessages(readBuf);
                        // 已无新的连接可接收则退出read loop，当localRead = 0时意味着已经没有新的客户端连接可以接收了，
                        // 本次main reactor接收客户端的任务到这里就结束了，跳出read loop。开始新的一轮IO事件的监听处理
                        if (localRead == 0) {
                            break;
                        }
                        // 如果说小于0，则表示关闭
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        //统计在当前事件循环中已经读取到得Message数量（对 NIOServerSocketChannel 来说就是统计do-while循环创建连接的个数）
                        allocHandle.incMessagesRead(localRead);
                        // 统计在当前事件循环中已经读取到得Message数量（创建连接的个数），对NIOServerSocketChannel来说，默认如果大于16次则会退出这个while循环
                        // 这里想表达的意思是在这个read loop循环中尽可能多的去接收客户端的并发连接，同时又不影响main reactor线程执行异步任务
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    exception = t;
                }

                int size = readBuf.size();
                // 如果上面while循环里读取到的已完成三次握手的client数量大于0，则在这里统一处理
                // 注意对比 NIOServerSocketChannel的触发fireChannelRead事件的时机和 NIOSocketChannel 触发fireChannelRead事件的时机
                // NIOServerSocketChannel是先统一读取（上面的do-while），然后循环触发 fireChannelRead 事件，而NIOSocketChannel则是
                // 每读取一次（do-while每循环一次），则触发一次 fireChannelRead @see io.netty.channel.nio.AbstractNioByteChannel.NioByteUnsafe.read
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    //在NioServerSocketChannel对应的pipeline中传播ChannelRead事件
                    //初始化客户端SocketChannel，并将其绑定到Sub Reactor线程组中的一个Reactor上
                    //所以将client注册到某个sub reactor上是在NIOServerSocketChannel的pipeline上的handler（ServerBootstrapAcceptor）
                    // 里完成的，详情见：io.netty.bootstrap.ServerBootstrap.ServerBootstrapAcceptor.channelRead
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                // 清除本次accept 创建的客户端SocketChannel集合
                readBuf.clear();
                allocHandle.readComplete();
                // 触发NIOServerSocketChannel的pipeline上的readComplete事件传播
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    closed = closeOnReadError(exception);
                    // 一旦出现异常，则在pipeline上进行传播
                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
