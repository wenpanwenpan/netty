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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 * ChannelOutboundBuffer 其实是一个单链表结构的缓冲队列，链表中的节点类型为 Entry ，由于 ChannelOutboundBuffer 在 Netty 中的作用
 * 就是缓存应用程序待发送的网络数据，所以 Entry 中封装的就是待写入 Socket 中的网络发送数据相关的信息，以及 ChannelHandlerContext#write
 * 方法中返回给用户的 ChannelPromise 。这样可以在数据写入Socket之后异步通知应用程序。
 *
 * 此外 ChannelOutboundBuffer 中还封装了三个重要的指针：
 * 1、unflushedEntry ：该指针指向 ChannelOutboundBuffer 中第一个待发送数据的 Entry。
 * 2、tailEntry ：该指针指向 ChannelOutboundBuffer 中最后一个待发送数据的 Entry。通过 unflushedEntry 和 tailEntry 这两个指针，
 * 我们可以很方便的定位到待发送数据的 Entry 范围。
 * 3、flushedEntry ：当我们通过 flush 操作需要将 ChannelOutboundBuffer 中缓存的待发送数据发送到 Socket 中时，flushedEntry 指针
 * 会指向 unflushedEntry 的位置，这样 flushedEntry 指针和 tailEntry 指针之间的 Entry 就是我们即将发送到 Socket 中的网络数据。
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 6 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding
    //不考虑指针压缩的大小 entry对象在堆中占用的内存大小为96,如果开启指针压缩，entry对象在堆中占用的内存大小 会是64, Netty这里默认是 96 字节
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };

    // 持有对channel的引用
    private final Channel channel;

    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    //
    // The Entry that is the first in the linked-list structure that was flushed
    // 当我们通过 flush 操作需要将 ChannelOutboundBuffer 中缓存的待发送数据发送到 Socket 中时，flushedEntry 指针
    // 会指向 unflushedEntry 的位置，这样 flushedEntry 指针和 tailEntry 指针之间的 Entry 就是我们即将发送到 Socket 中的网络数据
    private Entry flushedEntry;
    // The Entry which is the first unflushed in the linked-list structure
    // 该指针指向 ChannelOutboundBuffer 中第一个待发送数据的 Entry
    private Entry unflushedEntry;
    // The Entry which represents the tail of the buffer
    // 该指针指向 ChannelOutboundBuffer 中最后一个待发送数据的 Entry。通过 unflushedEntry 和 tailEntry 这两个指针，
    // 我们可以很方便的定位到待发送数据的 Entry 范围
    private Entry tailEntry;
    // The number of flushed entries that are not written yet
    private int flushed;

    // 本次write loop中需要发送的 JDK ByteBuffer个数
    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    // ChannelOutboundBuffer中占用内存总数据量水位线指针
    // volatile 关键字在 Java 内存模型中只能保证变量的可见性，以及禁止指令重排序。但无法保证多线程更新的原子性，
    // 这里我们可以通过AtomicLongFieldUpdater 来帮助 totalPendingSize 字段实现原子性的更新。
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    // 0表示channel可写，1表示channel不可写
    private volatile int unwritable;

    private volatile Runnable fireChannelWritabilityChangedTask;

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     */
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);
        if (tailEntry == null) {
            flushedEntry = null;
        } else {
            Entry tail = tailEntry;
            tail.next = entry;
        }
        tailEntry = entry;
        if (unflushedEntry == null) {
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     */
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        Entry entry = unflushedEntry;
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                // 将 flushedEntry 指针指向unflushedEntry，表示从 unflushedEntry 开始刷写Entry到socket发送队列
                flushedEntry = entry;
            }
            // 这里do-while想表达的是从 unflushedEntry 节点开始将 ChannelOutboundBuffer 上已取消的 entry去除掉，并释放已占用的内存
            do {
                flushed ++;
                // 如果当前entry对应的write操作被用户取消，则释放msg，并降低channelOutboundBuffer水位线
                // 在 flush 发送数据流程开始时，数据的发送流程就不能被取消了，在这之前我们都是可以通过 ChannelPromise 取消数据发送流程的。
                // 所以这里需要对 ChannelOutboundBuffer 中所有 Entry 节点包裹的 ChannelPromise 设置为不可取消状态。
                // 如果这里的 setUncancellable() 方法返回 false 则说明在这之前用户已经将 ChannelPromise 取消掉了，
                // 接下来就需要调用 entry.cancel() 方法来释放为待发送数据 msg 分配的堆外内存
                if (!entry.promise.setUncancellable()) {
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    int pending = entry.cancel();
                    // 降低 ChannelOutboundBuffer 中总共待发送数据的水位线
                    decrementPendingOutboundBytes(pending, false, true);
                }
                entry = entry.next;
            } while (entry != null);

            // All flushed so reset unflushedEntry 所有数据都flush了，所以将 unflushedEntry 置为空
            unflushedEntry = null;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     * 在将 Entry 对象添加进 ChannelOutboundBuffer 之后，就需要更新用于记录当前 ChannelOutboundBuffer 中关于待发送数据所占内存总量的水位线指示
     * 如果更新后的水位线超过了 Netty 指定的高水位线 DEFAULT_HIGH_WATER_MARK = 64 * 1024，则需要将当前 Channel 的状态设置为不可写，
     * 并在 pipeline 中传播 ChannelWritabilityChanged 事件，注意该事件是一个 inbound 事件
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }

    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }

        //更新总共待写入数据的大小
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        // 更新ChannelOutboundBuffer中待发送数据占用内存总量后水位线超过了netty指定的高水位线，则将channel设置为不可写，并在pipeline中传播 ChannelWritabilityChanged 事件
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {
            setUnwritable(invokeLater);
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }
        // 降低总待发送数据水位线
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        // 当更新后的水位线低于低水位线 DEFAULT_LOW_WATER_MARK = 32 * 1024 时，就将当前 channel 设置为可写状态
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Return the current message flush progress.
     * @return {@code 0} if nothing was flushed before for the current message or there is no current message
     */
    public long currentProgress() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return 0;
        }
        return entry.progress;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */
    public void progress(long amount) {
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        long progress = e.progress + amount;
        e.progress = progress;
        if (p instanceof ChannelProgressivePromise) {
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */
    public boolean remove() {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);
            safeSuccess(promise);
            decrementPendingOutboundBytes(size, false, true);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    /**
     * 在 remove0 方法中 netty 会将已经关闭的 Channel 对应的 ChannelOutboundBuffer 中还没来得及 flush 进 Socket 发送缓存区中的数据全部清除掉
     * Entry 对象中封装了用户待发送数据的 ByteBuffer，以及用于通知用户发送结果的 promise 实例
     */
    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            //清空当前reactor线程缓存的所有待发送数据
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        //从channelOutboundBuffer中删除该Entry节点
        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            //释放msg所占用的内存空间
            ReferenceCountUtil.safeRelease(msg);

            //编辑promise发送失败，并通知相应的Lisener
            safeFail(promise, cause);
            //由于msg得到释放，所以需要降低channelOutboundBuffer中的内存占用水位线，并根据notifyWritability决定是否触发ChannelWritabilityChanged事件
            decrementPendingOutboundBytes(size, false, notifyWritability);
        }

        // recycle the entry 回收Entry实例对象 到对象池
        e.recycle();

        return true;
    }

    private void removeEntry(Entry e) {
        if (-- flushed == 0) {
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {
            // flushedEntry 置为下一个节点
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     */
    public void removeBytes(long writtenBytes) {
        for (;;) {
            Object msg = current();
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            if (readableBytes <= writtenBytes) {
                if (writtenBytes != 0) {
                    progress(readableBytes);
                    writtenBytes -= readableBytes;
                }
                remove();
            } else { // readableBytes > writtenBytes
                if (writtenBytes != 0) {
                    buf.readerIndex(readerIndex + (int) writtenBytes);
                    progress(writtenBytes);
                }
                break;
            }
        }
        clearNioBuffers();
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() {
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0;
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * 由于最终 Netty 会调用 JDK NIO 的 SocketChannel 发送数据，所以这里需要首先将当前 Channel 中的写缓冲队列 ChannelOutboundBuffer 里存储的
     * DirectByteBuffer（ Netty 中的 ByteBuffer 实现）转换成 JDK NIO 的 ByteBuffer 类型。最终将转换后的待发送数据存储在 ByteBuffer[] nioBuffers 数组中
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     */
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);
        Entry entry = flushedEntry;
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) {
            if (!entry.cancelled) {
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();
                final int readableBytes = buf.writerIndex() - readerIndex;

                if (readableBytes > 0) {
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - https://linux.die.net//man/2/writev
                        break;
                    }
                    nioBufferSize += readableBytes;
                    int count = entry.count;
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        entry.count = count = buf.nioBufferCount();
                    }
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    if (neededSpace > nioBuffers.length) {
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    if (count == 1) {
                        ByteBuffer nioBuf = entry.buf;
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        nioBuffers[nioBufferCount++] = nioBuf;
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount >= maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next;
        }
        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     * 该ChannelOutboundBuffer对应的channel 是否可以写
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    /**设置channel可写*/
    private void setWritable(boolean invokeLater) {
        // 可以看到这里通过CAS的方式来更新
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                // 如果oldValue != 0 && newValue == 0说明更新之前channel是不可写的，更新之后channel可写了
                if (oldValue != 0 && newValue == 0) {
                    // 在pipeline上传播channel可写事件
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            // 获取channel是否可写的旧值
            final int oldValue = unwritable;
            final int newValue = oldValue | 1;
            // 原子更新
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                // 如果旧值是0，则说明之前channel是可写的，新值是不可写的
                if (oldValue == 0) {
                    // 在pipeline上传播channel不可写事件
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        // 如果需要稍后执行，则将channel不可写事件包装为一个task提交到channel的reactor线程任务队列
        if (invokeLater) {
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            // 立即在pipeline上传播channel不可写事件
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */
    public boolean isEmpty() {
        return flushed == 0;
    }

    /**
     * 该方法用于在 Netty 在发送数据的时候，如果发现当前 channel 处于非活跃状态，则将 ChannelOutboundBuffer 中 flushedEntry 与tailEntry
     * 之间的 Entry 对象节点全部删除，并释放发送数据占用的内存空间，同时回收 Entry 对象实例。
     * @author wenpan 2024/1/14 11:59 上午
     */
    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                // 循环清除channelOutboundBuffer中的待发送数据
                // 将entry从buffer中删除，并释放entry中的bytebuffer，通知promise failed
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        //循环清理channelOutboundBuffer中的unflushedEntry，因为在执行关闭之前有可能用户有一些数据write进来，需要清理掉
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);

                if (!e.cancelled) {
                    //释放unflushedEntry中的bytebuffer
                    ReferenceCountUtil.safeRelease(e.msg);
                    //通知unflushedEntry中的promise failed
                    safeFail(e.promise, cause);
                }
                // 归还当前节点给对象池，并且获取当前节点的下一个节点
                e = e.recycleAndGetNext();
            }
        } finally {
            inFail = false;
        }
        //清理channel用于缓存JDK nioBuffer的 threadLocal缓存NIO_BUFFERS
        clearNioBuffers();
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     */
    public long bytesBeforeUnwritable() {
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() {
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        ObjectUtil.checkNotNull(processor, "processor");

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    // channelOutboundBuffer 里的Entry，用于暂存 channel中用户要发送的数据 channelOutboundBuffer，每条用户要发送的数据都是一个entry
    static final class Entry {

        // Entry对象池的引用，可以看到这是一个静态的final的变量
        // 匿名实现 ObjectCreator接口来定义对象创建的行为方法。ObjectPool#newPool 创建用于管理Entry对象的对象池
        //静态变量引用类型地址 这个是在Klass Point(类型指针)中定义 8字节（开启指针压缩 为4字节）
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            // 创建池化对象
            @Override
            public Entry newObject(Handle<Entry> handle) {
                // 在对象池创建对象时，会为池化对象创建其在对象池中的句柄Handler，随后将Handler传入创建好的池化对象中。
                // 当对象使用完毕后，我们可以通过Handler来将对象回收至对象池中等待下次继续使用。
                return new Entry(handle);
            }
        });

        //recyclerHandle用于回收对象(默认实现类型为 DefaultHandle ，用于数据发送完毕后，对象池回收 Entry 对象。由对象池 RECYCLER 在创建 Entry 对象的时候传递进来)
        private final Handle<Entry> handle;
        // ChannelOutboundBuffer 是一个单链表的结构，这里的 next 指针用于指向当前 Entry 节点的后继节点。
        Entry next;
        //应用程序待发送的网络数据，这里 msg 的类型为 DirectByteBuffer 或者 FileRegion（用于通过零拷贝的方式网络传输文件）
        Object msg;
        // 这里的 ByteBuffer 类型为 JDK NIO 原生的 ByteBuffer 类型，因为 Netty 最终发送数据是通过 JDK NIO 底层的 SocketChannel 进行发送，
        // 所以需要将 Netty 中实现的 ByteBuffer 类型转换为 JDK NIO ByteBuffer 类型。应用程序发送的 ByteBuffer 可能是一个也可能是多个，
        // 如果发送多个就用 ByteBuffer[] bufs 封装在 Entry 对象中，如果是一个就用 ByteBuffer buf 封装
        ByteBuffer[] bufs;
        ByteBuffer buf;
        // ChannelHandlerContext#write 异步写操作返回的 ChannelFuture。当 Netty 将待发送数据写入到 Socket 中时会通过这个 ChannelPromise 通知应用程序发送结果
        ChannelPromise promise;
        //表示当前的一个发送进度，已经发送了多少数据。
        long progress;
        // Entry中总共需要发送多少数据。注意：这个字段并不包含 Entry 对象的内存占用大小。只是表示待发送网络数据的大小
        long total;
        // 表示待发送数据的内存占用总量。待发送数据在内存中的占用量分为两部分：
        // 1、Entry对象中所封装的待发送网络数据大小。
        // 2、Entry对象本身在内存中的占用量。
        int pendingSize;
        // 表示待发送数据 msg 中一共包含了多少个 ByteBuffer 需要发送
        int count = -1;
        //应用程序调用的 write 操作是否被取消。
        boolean cancelled;

        //Entry对象只能通过对象池获取，不可外部自行创建
        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        // 由于Entry对象在设计上是被对象池管理的，所以不能对外提供public构造函数，无法在外面直接创建Entry对象。
        //所以池化对象都会提供一个获取对象实例的 static 方法 newInstance。在该方法中通过RECYCLER.get()从对象池中获取对象实例
        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            // 从内存池获取一个池化对象
            Entry entry = RECYCLER.get();
            // 初始化池化对象相关属性
            entry.msg = msg;
            //待发数据数据大小 + entry对象大小
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        int cancel() {
            // 如果没有被取消才进行entry的取消动作
            if (!cancelled) {
                // 设置该entry已经被取消
                cancelled = true;
                // 待发送数据的内存占用总量(待发送数据大小 + entry本身大小)
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                // 将entry相关属性置为初始状态
                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        // 回收池化对象（将池化对象归还给对象池）
        void recycle() {
            // 将池化对象相关属性置为初始状态
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            // 通过池化对象在对象池中的句柄（handle）来将池化对象归还给对象池
            handle.recycle(this);
        }

        // 归还当前节点给对象池，并且返回当前节点的下一个节点
        Entry recycleAndGetNext() {
            // 指向下一个节点
            Entry next = this.next;
            // 归还当前节点给对象池
            recycle();
            // 返回下一个节点
            return next;
        }
    }
}
