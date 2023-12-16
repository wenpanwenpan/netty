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
package io.netty.channel.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 为什么要用SelectedSelectionKeySet替换掉原来的HashSet呢？
 * 因为这里涉及到对HashSet类型的sun.nio.ch.SelectorImpl#selectedKeys集合的两种操作：
 * 插入操作： 通过前边对sun.nio.ch.SelectorImpl类中字段的介绍我们知道，在Selector监听到IO就绪的SelectionKey后，会将IO就绪的SelectionKey插入sun.nio.ch.SelectorImpl#selectedKeys集合中，这时Reactor线程会从java.nio.channels.Selector#select(long)阻塞调用中返回（类似epoll_wait）。
 * 遍历操作：Reactor线程返回后，会从Selector中获取IO就绪的SelectionKey集合（也就是sun.nio.ch.SelectorImpl#selectedKeys），Reactor线程遍历selectedKeys,获取IO就绪的SocketChannel，并处理SocketChannel上的IO事件。
 * 我们都知道HashSet底层数据结构是一个哈希表，由于Hash冲突这种情况的存在，所以导致对哈希表进行插入和遍历操作的性能不如对数组进行插入和遍历操作的性能好
 * 还有一个重要原因是，数组可以利用CPU缓存的优势来提高遍历的效率
 * @author wenpan 2023/12/10 2:21 下午
 */
final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    // 用数组来表示SelectionKey，而不是JDK原生selector中的hashset
    SelectionKey[] keys;
    // 记录数组的大小
    int size;

    SelectedSelectionKeySet() {
        keys = new SelectionKey[1024];
    }

    /**
     * 数组的添加效率高于 HashSet 因为不需要考虑hash冲突
     * @author wenpan 2023/12/10 2:28 下午
     */
    @Override
    public boolean add(SelectionKey o) {
        if (o == null) {
            return false;
        }
        // 在向数组插入元素的时候可以直接定位到插入位置keys[size++]。操作一步到位，不用像哈希表那样还需要解决Hash冲突
        keys[size++] = o;
        if (size == keys.length) {
            // 数组满了进行扩容
            increaseCapacity();
        }

        return true;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    /**
     * 采用数组的遍历效率 高于 HashSet
     * */
    @Override
    public Iterator<SelectionKey> iterator() {
        return new Iterator<SelectionKey>() {
            private int idx;

            @Override
            public boolean hasNext() {
                return idx < size;
            }

            @Override
            public SelectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return keys[idx++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    void reset() {
        reset(0);
    }

    void reset(int start) {
        // 将数组从start开始到size结束全部置为null
        Arrays.fill(keys, start, size, null);
        size = 0;
    }

    private void increaseCapacity() {
        // 可以看到数组直接扩容为原来的两倍
        SelectionKey[] newKeys = new SelectionKey[keys.length << 1];
        System.arraycopy(keys, 0, newKeys, 0, size);
        keys = newKeys;
    }
}
