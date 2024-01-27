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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    // 执行flush操作的task，将channel的write缓冲链表上的待发送数据entry刷写到socket的发送缓冲区
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    //表示Input已经shutdown了，再次对channel进行读取返回-1  设置该标志
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        // 可以看到NioSocketChannel关心的是读事件，NIOSocketChannel 继承AbstractNioByteChannel，而 NIOServerSocketChannel 继承
        // AbstractNioMessageChannel，但他们都有一个共同的父类 AbstractNioChannel
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        // 可以在启动 ServerBootstrap 中配置是否允许半关闭
        //  ServerBootstrap sb = new ServerBootstrap();
        //    sb.childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    /**这个unsafe类主要用来操作客户端channel（NIOSocketChannel）对底层的一些操作，具体可以看类的继承关系*/
    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            // 如果socket的读通道没有关闭（如果有client第一次发起断开连接，server端这里的读写通道都没有关闭）
            if (!isInputShutdown0()) {
                // 是否允许TCP连接半关闭，TCP是一个面向连接的、可靠的、基于字节流的传输协议，拥有读写通道，半关闭的意思就是只关闭读通道或写通道
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    // 在pipeline上传播读通道关闭事件，我们可以在 ChannelInputShutdownEvent 事件的回调方法中（也即是ChannelHandler里），
                    // 向客户端发送遗留的数据(比如：调用 ctx.writeAndFlush(message); 将数据进行发送)，做到真正的优雅关闭
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    // 如果不支持半关闭，则服务端直接调用close方法向客户端发送fin,结束close_wait状态进如last_ack状态
                    close(voidPromise());
                }
            } else {
                // TCP半连接已关闭的逻辑处理
                // 设置 inputClosedSeenErrorOnRead = true 表示此时 Channel 的读通道已经关闭了，不能再继续响应 OP_READ 事件，
                // 因为半关闭状态下，Selector 会不停的通知 OP_READ 事件，如果不停无脑响应的话，会造成极大的 CPU 资源的浪费
                inputClosedSeenErrorOnRead = true;
                // 在 pipeline 中触发 ChannelInputShutdownReadComplete 事件，此事件的触发标志着服务端在 CLOSE_WAIT 状态下已经将所有
                // 遗留的数据发送给了客户端，服务端可以在该事件的回调中关闭 Channel ，结束 CLOSE_WAIT 进入 LAST_ACK 状态。
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        /**
         * 处理reactor线程从channel的socket缓冲区读取数据时发生异常
         */
        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                // byteBuf中有可读的数据
                if (byteBuf.isReadable()) {
                    readPending = false;
                    //如果发生异常时，已经读取到了部分数据，则触发ChannelRead事件
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            // 触发 ChannelReadComplete 事件
            pipeline.fireChannelReadComplete();
            // 触发ExceptionCaught事件
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        /**执行该方法的线程为Sub Reactor线程，处理连接数据读取逻辑是在NioSocketChannel中*/
        @Override
        public final void read() {
            // 获取channel的配置对象 NioSocketChannelConfig
            final ChannelConfig config = config();
            // 这里的 shouldBreakReadReady 方法是用来判断在半关闭期间是否取消 OP_READ 事件的监听。这里的 inputClosedSeenErrorOnRead 已经设置为 true 了
            if (shouldBreakReadReady(config)) {
                // 调用 clearReadPending 方法将读事件从 Reactor 中取消掉，停止对 OP_READ 事件的监听。
                // 否则 Reactor 线程就会在半关闭期间内一直在这里空转，导致 CPU 100%。
                clearReadPending();
                return;
            }
            //获取NioSocketChannel的pipeline
            final ChannelPipeline pipeline = pipeline();
            // PooledByteBufAllocator为Netty中的内存池，用来管理堆外内存DirectByteBuffer。
            // PooledByteBufAllocator 具体用于实际分配ByteBuf的分配器（它会根据AdaptiveRecvByteBufAllocator动态调整出来的大小去真正的申请内存分配ByteBuffer）
            final ByteBufAllocator allocator = config.getAllocator();
            // 自适应ByteBuf分配器 AdaptiveRecvByteBufAllocator ,用于动态调节ByteBuf容量（这里需要注意的是AdaptiveRecvByteBufAllocator
            // 并不会真正的去分配ByteBuffer，它只是负责动态调整分配ByteBuffer的大小）需要与具体的ByteBuf分配器配合使用 比如这里的 PooledByteBufAllocator
            // 这里的 allocHandle 其实质就是 ：MaxMessageHandle(HandleImpl)，可以点进去查看
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            //allocHandler用于统计每次读取数据的大小，方便下次分配合适大小的ByteBuf，重置清除上次的统计指标
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // 利用PooledByteBufAllocator分配合适大小的byteBuf 初始大小为2048
                    byteBuf = allocHandle.allocate(allocator);
                    // 记录本次读取了多少字节数，读取socket缓冲区的数据逻辑就在下面的 doReadBytes 方法里
                    // 【缩容扩容点一】、lastBytesRead 这个方法可能会触发 byteBuf 扩容（@see io.netty.channel.AdaptiveRecvByteBufAllocator.HandleImpl.lastBytesRead）
                    // 如果client发送的是强制关闭连接的RST包，则这里的doReadBytes方法会抛出 IOExcetion异常 ，在下面的catch里处理强制关闭流程，这里会有两种情况抛出异常
                    // 1、此时Socket接收缓冲区中只有 RST 包，并没有其他正常数据。
                    // 2、Socket 接收缓冲区有正常的数据，OP_READ 事件活跃，当调用 doReadBytes 方法从 Channel 中读取数据的过程中，
                    // 对端发送 RST 强制关闭连接，这时会在读取的过程中抛出 IOException 异常。
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    //如果本次没有读取到任何字节，则退出循环 进行下一轮事件轮询
                    if (allocHandle.lastBytesRead() <= 0) {
                        // 如果进入了这里，则说明client 发起了断开连接请求，需要释放掉byteBuf所占用的空间
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        // 清空byteBuf，便于垃圾回收
                        byteBuf = null;
                        // 小于0说明是断开连接（也就是client主动发起了close连接请求（四次挥手）），在client主动断开连接时，server端的内核协议栈接收到
                        // 断开连接的fin包后，会向socket的接收缓冲区写入一个EOF（也就是-1），然后该channel上的读事件活跃，然后被reactor线程进行读取到
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            // 表示客户端发起连接关闭
                            readPending = false;
                        }
                        // 可以看到，当读取到断开连接的信号后，直接跳出循环不继续读了
                        break;
                    }

                    //read loop读取数据次数+1，@see io.netty.channel.DefaultMaxMessagesRecvByteBufAllocator.MaxMessageHandle.incMessagesRead
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    //客户端NioSocketChannel的pipeline中触发ChannelRead事件(可以看到每读取一次数据都会调用一下pipeline上的ChannelRead方法)
                    pipeline.fireChannelRead(byteBuf);
                    //解除本次读取数据分配的ByteBuffer引用，方便下一轮read loop分配
                    byteBuf = null;
                    // 在每次read loop循环的末尾都需要通过调用allocHandle.continueReading()来判断是否继续read loop循环读取NioSocketChannel中的数据
                } while (allocHandle.continueReading());

                // 【扩容缩容点二】、根据本次read loop总共读取的字节数，决定下次是否扩容或者缩容
                allocHandle.readComplete();
                // 可以看到只有当while循环退出时才会触发pipeline上的fireChannelReadComplete事件
                //在NioSocketChannel的pipeline中触发ChannelReadComplete事件，表示一次read事件处理完毕
                //但这并不表示 客户端发送来的数据已经全部读完，因为如果数据太多的话，这里只会读取16次，剩下的会等到下次read事件到来后在处理
                pipeline.fireChannelReadComplete();

                // 连接关闭流程处理，断开连接的核心处理地点
                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                // 读取数据时发生异常处理（比如：client发出强制断开连接的RST报文，后server端从socket接收缓冲区读取数据时就会抛出IOException）
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
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

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 文件已经传输完毕
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            //零拷贝的方式传输文件
            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            // 最终会在 doWriteFileRegion 方法中通过 FileChannel#transferTo 方法底层用到的系统调用为 sendFile 实现零拷贝网络文件的传输
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        //走到这里表示 此时Socket已经写不进去了 退出writeLoop，注册OP_WRITE事件
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            //这里处理还没写满16次 但是socket缓冲区已满写不进去的情况 注册write事件
            // 什么时候socket可写了， epoll会通知reactor线程继续写
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            //这里处理的是socket缓冲区依然可写，但是写了16次还没写完，这时就不能在写了，reactor线程需要处理其他channel上的io事件
            //因为此时socket是可写的，必须清除op_write事件，否则会一直不停地被通知
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            //如果本次writeLoop还没写完，则提交flushTask到reactor
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
