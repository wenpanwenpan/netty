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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 * 动态可伸缩的 ByteBuf 分配器
 * AdaptiveRecvByteBufAllocator 主要的作用就是为接收数据的ByteBuffer进行扩容缩容，那么每次怎么扩容？扩容多少？怎么缩容？缩容多少呢？？
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    // 以下三个字段是AdaptiveRecvByteBufAllocator定义了三个关于ByteBuffer容量的字段：
    // 1、表示ByteBuffer最小的容量，默认为64，也就是无论ByteBuffer在怎么缩容，容量也不会低于64
    static final int DEFAULT_MINIMUM = 64;
    // 2、表示ByteBuffer的初始化容量。默认为2048。
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;
    // 3、表示ByteBuffer的最大容量，默认为65536，也就是无论ByteBuffer在怎么扩容，容量也不会超过65536
    static final int DEFAULT_MAXIMUM = 65536;

    //扩容步长
    private static final int INDEX_INCREMENT = 4;
    // 缩容步长
    private static final int INDEX_DECREMENT = 1;
    //RecvBuf分配容量表（扩缩容索引表）按照表中记录的容量大小进行扩缩容
    /**
     * Netty中定义了一个int型的数组SIZE_TABLE来存储每个扩容单位对应的容量大小。建立起扩缩容的容量索引表。每次扩容多少，缩容多少全部记录在这个容量索引表中。
     * 在AdaptiveRecvByteBufAllocatorl类初始化的时候会在static{}静态代码块中对扩缩容索引表SIZE_TABLE进行初始化。
     * 从源码中我们可以看出SIZE_TABLE的初始化分为两个部分：
     * 1、当索引容量小于512时，SIZE_TABLE中定义的容量索引是从16开始按16递增（16、32、48、64、.... 512）
     * 2、当索引容量大于512时，SIZE_TABLE中定义的容量索引是按前一个索引容量的2倍递增(512、1024、2048、4096、..... 2097152)
     */
    private static final int[] SIZE_TABLE;

    static {
        //初始化RecvBuf容量分配表
        List<Integer> sizeTable = new ArrayList<Integer>();
        //当分配容量小于512时，扩容单位为16递增
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        //当分配容量大于512时，扩容单位为一倍
        // Suppress a warning since i becomes negative when an integer overflow happens
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        //初始化RecbBuf扩缩容索引表
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**从SIZE_TABLE表中找出最小大于等于size的下标索引*/
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            //无符号右移，高位始终补0
            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
                // 以下两种情况就是  a <= size <= b了
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    /**
     * MaxMessageHandle 定义在 DefaultMaxMessagesRecvByteBufAllocator 类的内部。
     * HandleImpl 定义在 AdaptiveRecvByteBufAllocator 类的内部。
     * 而 AdaptiveRecvByteBufAllocator 继承了 DefaultMaxMessagesRecvByteBufAllocator
     * HandleImpl 继承了 MaxMessageHandle
     * @author wenpan 2023/12/24 6:45 下午
     */
    private final class HandleImpl extends MaxMessageHandle {
        //最小容量在扩缩容索引表中的index，默认是3
        private final int minIndex;
        //最大容量在扩缩容索引表中的index，默认是38
        private final int maxIndex;
        //当前容量在扩缩容索引表中的index 初始33 对应容量2048
        private int index;
        // 预计下一次分配buffer的容量，初始：2048。在每次申请内存分配ByteBuffer的时候，采用nextReceiveBufferSize的值指定容量
        private int nextReceiveBufferSize;
        //是否缩容
        private boolean decreaseNow;

        /**
         * 构造函数
         * @param minIndex 最小容量在扩缩容索引表中的index
         * @param maxIndex 最大容量在扩缩容索引表中的index
         * @param initial 初始容量
         * @author wenpan 2023/12/24 6:19 下午
         */
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;
            //在扩缩容索引表中二分查找到最小大于等于initial 的容量索引
            index = getSizeTableIndex(initial);
            // 2048 （当创建HandleImpl的时候，nextReceiveBufferSize便是 2048）
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 如果 真实读取的数据量 == 预计读取的数据量（本次读取满载而归），则进行扩容
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            //预计下一次分配buffer的容量，一开始为2048
            return nextReceiveBufferSize;
        }

        /**扩容缩容记录*/
        private void record(int actualReadBytes) {
            // 如果实际读取到的字节数量小于等于SIZE_TABLE[index - INDEX_DECREMENT]。表示本次读取到的字节数比当前ByteBuffer容量的下一级容量还要小，
            // 说明当前ByteBuffer的容量分配的有些大了，设置缩容标识decreaseNow = true。当下次OP_READ事件继续满足缩容条件的时候，
            // 开始真正的进行缩容。缩容后的容量为SIZE_TABLE[index - INDEX_DECREMENT]，但不能小于SIZE_TABLE[minIndex]
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                // 注意看这里的decreaseNow条件：需要满足两次缩容条件才会进行缩容（第一次把decreaseNow变成true，第二次来的时候才缩），且缩容步长为1，缩容比较谨慎
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
                // 如果本次OP_READ事件处理总共读取的字节数actualReadBytes 大于等于 当前ByteBuffer容量(nextReceiveBufferSize)时，
                // 说明ByteBuffer分配的容量有点小了，需要进行扩容。扩容后的容量为SIZE_TABLE[index + INDEX_INCREMENT]，但不能超过SIZE_TABLE[maxIndex]。
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                // 满足一次扩容条件就进行扩容，并且扩容步长为4， 扩容比较奔放
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            //是否对recvbuf进行扩容缩容
            record(totalBytesRead());
        }
    }

    // 这三个值会在构造函数中被初始化
    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 可以看到这里传递了三次初始参数 64，2048，65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        //计算minIndex maxIndex
        //在SIZE_TABLE中二分查找最小 >= minimum的容量索引 ：3
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        //在SIZE_TABLE中二分查找最大 <= maximum的容量索引 ：38
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
