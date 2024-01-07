/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import io.netty.util.Recycler;

/**
 * Light-weight object pool.
 * 对象池顶层接口，其中泛型为对象池中的对象类型
 * @param <T> the type of the pooled object
 */
public abstract class ObjectPool<T> {

    ObjectPool() { }

    /**
     * Get a {@link Object} from the {@link ObjectPool}. The returned {@link Object} may be created via
     * {@link ObjectCreator#newObject(Handle)} if no pooled {@link Object} is ready to be reused.
     * 定义了从对象池中获取对象的行为
     */
    public abstract T get();

    /**
     * Handle for an pooled {@link Object} that will be used to notify the {@link ObjectPool} once it can
     * reuse the pooled {@link Object} again.
     * Handle是池化对象在对象池中的一个模型，Handle里面包裹了池化对象，并包含了池化对象的一些回收信息，以及池化对象的回收状态。它的默认实现是DefaultHandle
     * @param <T>
     */
    public interface Handle<T> {
        /**
         * Recycle the {@link Object} if possible and so make it ready to be reused.
         * 将池化对象回收至对象池中的行为
         */
        void recycle(T self);
    }

    /**
     * Creates a new Object which references the given {@link Handle} and calls {@link Handle#recycle(Object)} once
     * it can be re-used.
     * 对象池中的池化对象创建器，用于创建池化对象
     * @param <T> the type of the pooled object
     */
    public interface ObjectCreator<T> {

        /**
         * Creates an returns a new {@link Object} that can be used and later recycled via
         * {@link Handle#recycle(Object)}. 创建一个池化对象
         */
        T newObject(Handle<T> handle);
    }

    /**
     * Creates a new {@link ObjectPool} which will use the given {@link ObjectCreator} to create the {@link Object}
     * that should be pooled.
     * 创建对象池，可以看到这里的对象池的类型是RecyclerObjectPool
     * 泛型类ObjectPool<T>是Netty为对象池设计的一个顶层抽象。对象池的行为功能均定义在这个泛型抽象类中。我们可以通过 ObjectPool#newPool
     * 方法创建指定的对象池。其参数 ObjectCreator 接口用来定义创建池化对象的行为。当对象池中需要创建新对象时，就会调用该接口方法 ObjectCreator#newObject 来创建对象。
     * @param creator 池化对象创建器，用于创建池化对象
     */
    public static <T> ObjectPool<T> newPool(final ObjectCreator<T> creator) {
        // 返回对象池
        return new RecyclerObjectPool<T>(ObjectUtil.checkNotNull(creator, "creator"));
    }

    /**
     * 对象池，可以看到该对象池只提供了get方法，表示可以从该对象池中获取池化对象，但是没有归还对象的方法，这是为什么呢？
     * @author wenpan 2024/1/6 5:42 下午
     */
    private static final class RecyclerObjectPool<T> extends ObjectPool<T> {
        /**真正的对象池*/
        private final Recycler<T> recycler;

        /**
         * 对象池构造函数
         * @param creator 对象池里的对象创建器，用于创建对象池里的池化对象
         * @author wenpan 2024/1/6 5:42 下午
         */
        RecyclerObjectPool(final ObjectCreator<T> creator) {
            // recycler 才是真正的对象池，这里采用匿名实现
             recycler = new Recycler<T>() {
                 // 创建池化对象
                @Override
                protected T newObject(Handle<T> handle) {
                    // 对象池里的对象创建时，handle对象就在这时候传入的
                    return creator.newObject(handle);
                }
            };
        }

        /**
         * 当我们要获取池化对象时，直接调用该方法即可从对象池中获取被池化的对象
         * @return T 真实的池化对象
         * @author wenpan 2024/1/7 3:05 下午
         */
        @Override
        public T get() {
            // 从对象池获取对象
            return recycler.get();
        }
    }
}
