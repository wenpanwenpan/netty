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

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

/**
 * Default {@link MessageSizeEstimator} implementation which supports the estimation of the size of
 * {@link ByteBuf}, {@link ByteBufHolder} and {@link FileRegion}.
 */
public final class DefaultMessageSizeEstimator implements MessageSizeEstimator {

    private static final class HandleImpl implements Handle {
        // 默认为8
        private final int unknownSize;

        private HandleImpl(int unknownSize) {
            this.unknownSize = unknownSize;
        }

        // 计算待发送的msg的大小
        @Override
        public int size(Object msg) {
            if (msg instanceof ByteBuf) {
                // ByteBuffer 的大小即为 Buffer 中未读取的字节数 writerIndex - readerIndex
                return ((ByteBuf) msg).readableBytes();
            }
            if (msg instanceof ByteBufHolder) {
                return ((ByteBufHolder) msg).content().readableBytes();
            }
            // 文件
            if (msg instanceof FileRegion) {
                return 0;
            }
            return unknownSize;
        }
    }

    /**
     * Return the default implementation which returns {@code 8} for unknown messages.
     */
    public static final MessageSizeEstimator DEFAULT = new DefaultMessageSizeEstimator(8);

    private final Handle handle;

    /**
     * Create a new instance
     *
     * @param unknownSize       The size which is returned for unknown messages.
     */
    public DefaultMessageSizeEstimator(int unknownSize) {
        checkPositiveOrZero(unknownSize, "unknownSize");
        // 默认handle是HandleImpl
        handle = new HandleImpl(unknownSize);
    }

    @Override
    public Handle newHandle() {
        return handle;
    }
}
