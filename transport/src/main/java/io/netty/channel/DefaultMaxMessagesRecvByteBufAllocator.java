/*
 * Copyright 2015 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     * MaxMessageHandle中包含了用于动态调整ByteBuffer容量的统计指标。
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config;
        //每次事件轮询时，最多读取16次，默认为16次，可在启动配置类ServerBootstrap中通过ChannelOption.MAX_MESSAGES_PER_READ选项设置
        private int maxMessagePerRead;
        //本次事件轮询总共读取的message数（对服务端的NIOServerSocketChannel来说这个指标指的是接收连接的数量，对NIOSocketChannel来说就是读取的socket缓冲区数据的次数）
        private int totalMessages;
        //用于统计在read loop中总共接收到客户端连接上的数据大小
        private int totalBytesRead;
        //表示本次read loop 尝试读取多少字节，byteBuffer剩余可写的字节数
        private int attemptedBytesRead;
        //本次read loop读取到的字节数
        private int lastBytesRead;
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                //判断本次读取byteBuffer是否满载而归(当前ByteBuffer预计尝试要写入的字节数 == 真实读取到的字节数)
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            //默认每次最多读取16次
            maxMessagePerRead = maxMessagesPerRead();
            // 重置总共接收的字节数和总共读取的message数量为0
            totalMessages = totalBytesRead = 0;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            // 通过 alloc 分配一块大小为 guss 的 byteBuf 内存，所以这里可以看出ByteBufAllocator才是真正负责分配byteBuf的
            // 而 MaxMessageHandle 只负责计算要分配的byteBuf大小(alloc可以分配堆外内存和JVM堆内内存)
            return alloc.ioBuffer(guess());
        }

        @Override
        public final void incMessagesRead(int amt) {
            // 接收到的消息数量+amt
            totalMessages += amt;
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            // defaultMaybeMoreSupplier用于判断经过本次read loop读取数据后，ByteBuffer是否满载而归。如果是满载而归的话（attemptedBytesRead == lastBytesRead），
            // 表明可能NioSocketChannel里还有数据。如果不是满载而归，表明NioSocketChannel里没有数据了已经。
            return continueReading(defaultMaybeMoreSupplier);
        }

        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return config.isAutoRead() &&
                    // !respectMaybeMoreData || maybeMoreDataSupplier.get() 这个条件比较复杂，它其实就是通过respectMaybeMoreData字段来控制NioSocketChannel中可能还有数据可读的情况下该如何处理。
                    //maybeMoreDataSupplier.get()：true表示本次读取从NioSocketChannel中读取数据，ByteBuffer满载而归。说明可能NioSocketChannel中还有数据没读完。fasle表示ByteBuffer还没有装满，说明NioSocketChannel中已经没有数据可读了。
                    //respectMaybeMoreData = true表示要对可能还有更多数据进行处理的这种情况要respect认真对待,如果本次循环读取到的数据已经装满ByteBuffer，表示后面可能还有数据，那么就要进行读取。如果ByteBuffer还没装满表示已经没有数据可读了那么就退出循环。
                    // respectMaybeMoreData = false表示对可能还有更多数据的这种情况不认真对待 not respect。不管本次循环读取数据ByteBuffer是否满载而归，都要继续进行读取，直到读取不到数据在退出循环，属于无脑读取
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                    // 对服务端NIOServerSocketChannel来说totalMessages表示一次read中接收client连接的次数，maxMessagePerRead默认为16
                    // 如果一次read loop中读取的次数超过了16次，则会返回false，main reactor线程退出循环，进行下一轮的io事件处理
                    // 对于 client channel （NIOSocketChannel 来说，每个Sub Reactor管理了多个NioSocketChannel，
                    // 不能在一个NioSocketChannel上占用太多时间，要将机会均匀地分配给Sub Reactor所管理的所有NioSocketChannel，所以当读取
                    // 次数 >= maxMessagePerRead 时就要退出while循环了
                   totalMessages < maxMessagePerRead &&
                    // totalBytesRead > 0：用于判断当客户端NioSocketChannel上的OP_READ事件活跃时，sub reactor线程在read loop中是否读取到了网络数据
                    // 对于服务端 NIOServerSocketChannel来说这里是个bug，因为totalBytesRead一直不会被更新，导致这里永远返回false
                    // 所以server端每次只接收一个client端连接后就退出循环，降低了吞吐率，该bug在4.1.69.final版本被修复了
                   totalBytesRead > 0;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
