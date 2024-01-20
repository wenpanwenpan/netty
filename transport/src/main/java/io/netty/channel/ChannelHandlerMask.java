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
package io.netty.channel;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <p>
 * netty 会将其支持的所有异步事件用掩码来表示，定义在 ChannelHandlerMask 类中， netty 框架通过这些事件掩码可以很方便的知道用户自定义的 ChannelHandler
 * 是属于什么类型的（ChannelInboundHandler or ChannelOutboundHandler ）。
 *
 * 除此之外，inbound 类事件如此之多，用户也并不是对所有的 inbound 类事件感兴趣，用户可以在自定义的 ChannelInboundHandler
 * 中覆盖自己感兴趣的 inbound 事件回调，从而达到针对特定 inbound 事件的监听。
 *
 * 这些用户感兴趣的 inbound 事件集合同样也会用掩码的形式保存在自定义 ChannelHandler 对应的 ChannelHandlerContext 中，这样当特定 inbound 事件在
 * pipeline 中开始传播的时候，netty 可以根据对应 ChannelHandlerContext 中保存的 inbound 事件集合掩码来判断，用户自定义的 ChannelHandler 是否对该
 * inbound 事件感兴趣，从而决定是否执行用户自定义 ChannelHandler 中的相应回调方法或者跳过对该 inbound 事件不感兴趣的 ChannelHandler 继续向后传播。
 * </p>
 * <p>
 *     从以上描述中，我们也可以窥探出，Netty 引入 ChannelHandlerContext 来封装 ChannelHandler 的原因，在代码设计上还是遵循单一职责的原则，
 *     ChannelHandler 是用户接触最频繁的一个 netty 组件，netty 希望用户能够把全部注意力放在最核心的 IO 处理上，用户只需要关心自己对哪些异步事件感兴趣
 *     并考虑相应的处理逻辑即可，而并不需要关心异步事件在 pipeline 中如何传递，如何选择具有执行条件的 ChannelHandler 去执行或者跳过。这些切面性质的逻辑，
 *     netty 将它们作为上下文信息全部封装在 ChannelHandlerContext 中由netty框架本身负责处理。
 * </p>
 * @author wenpan 2024/1/14 6:04 下午
 */
final class ChannelHandlerMask {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerMask.class);

    // ========================== inbound事件相关掩码 ==========================
    // Using to mask which methods must be called for a ChannelHandler.
    static final int MASK_EXCEPTION_CAUGHT = 1;
    // channelRegistered 方法掩码（channel注册到reactor上）
    static final int MASK_CHANNEL_REGISTERED = 1 << 1;
    // channelUnregistered 方法（channel从reactor上取消注册）
    static final int MASK_CHANNEL_UNREGISTERED = 1 << 2;
    // channelActive 方法
    static final int MASK_CHANNEL_ACTIVE = 1 << 3;
    // channelInactive 方法
    static final int MASK_CHANNEL_INACTIVE = 1 << 4;
    // channelRead 方法
    static final int MASK_CHANNEL_READ = 1 << 5;
    // channelReadComplete 方法
    static final int MASK_CHANNEL_READ_COMPLETE = 1 << 6;
    // userEventTriggered 方法
    static final int MASK_USER_EVENT_TRIGGERED = 1 << 7;
    // channelWritabilityChanged 方法
    static final int MASK_CHANNEL_WRITABILITY_CHANGED = 1 << 8;

    // ========================== outbound事件相关掩码 ==========================
    // bind方法
    static final int MASK_BIND = 1 << 9;
    // connect方法
    static final int MASK_CONNECT = 1 << 10;
    // disconnect方法
    static final int MASK_DISCONNECT = 1 << 11;
    // close 方法
    static final int MASK_CLOSE = 1 << 12;
    // deregister 方法
    static final int MASK_DEREGISTER = 1 << 13;
    // read 方法
    static final int MASK_READ = 1 << 14;
    // write 方法
    static final int MASK_WRITE = 1 << 15;
    // flush 方法
    static final int MASK_FLUSH = 1 << 16;

    //inbound事件的掩码集合（代表和入站相关的方法）
    static final int MASK_ONLY_INBOUND =  MASK_CHANNEL_REGISTERED |
            MASK_CHANNEL_UNREGISTERED | MASK_CHANNEL_ACTIVE | MASK_CHANNEL_INACTIVE | MASK_CHANNEL_READ |
            MASK_CHANNEL_READ_COMPLETE | MASK_USER_EVENT_TRIGGERED | MASK_CHANNEL_WRITABILITY_CHANGED;
    // 所有inbound事件的掩码集合
    private static final int MASK_ALL_INBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_INBOUND;
    //outbound事件掩码集合（代表和出站相关的方法）
    static final int MASK_ONLY_OUTBOUND =  MASK_BIND | MASK_CONNECT | MASK_DISCONNECT |
            MASK_CLOSE | MASK_DEREGISTER | MASK_READ | MASK_WRITE | MASK_FLUSH;
    // 所有outbound事件掩码集合
    private static final int MASK_ALL_OUTBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_OUTBOUND;

    // handler 掩码缓存，以handler的Clazz为key，该Class的掩码为value
    // 这里需要一个 FastThreadLocal 类型的 MASKS 字段来缓存 ChannelHandler 对应的执行掩码。因为 ChannelHandler 类一旦被定义出来它的执行掩码就固定了，
    // 而 netty 需要接收大量的连接，创建大量的 channel ，并为这些 channel 初始化对应的 pipeline ，需要频繁的记录 channelHandler 的执行掩码到 context 类中，
    // 所以这里需要将掩码缓存起来
    private static final FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>> MASKS =
            new FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>>() {
                @Override
                protected Map<Class<? extends ChannelHandler>, Integer> initialValue() {
                    return new WeakHashMap<Class<? extends ChannelHandler>, Integer>(32);
                }
            };

    /**
     * Return the {@code executionMask}.
     */
    static int mask(Class<? extends ChannelHandler> clazz) {
        // Try to obtain the mask from the cache first. If this fails calculate it and put it in the cache for fast
        // lookup in the future.
        // 因为每建立一个channel就会初始化一个pipeline，这里需要将ChannelHandler对应的mask缓存
        Map<Class<? extends ChannelHandler>, Integer> cache = MASKS.get();
        Integer mask = cache.get(clazz);
        // 如果缓存中还没有则计算一次并缓存
        if (mask == null) {
            // 计算ChannelHandler对应的mask（什么类型的ChannelHandler，对什么事件感兴趣）
            mask = mask0(clazz);
            cache.put(clazz, mask);
        }
        return mask;
    }

    /**
     * Calculate the {@code executionMask}.
     * 计算 handlerType Class的掩码
     * 从这个过程中我们可以看到，ChannelHandler 的执行掩码包含的是该 ChannelHandler 感兴趣的事件掩码集合。当事件在 pipeline 中传播的时候
     * ，在 ChannelHandlerContext 中可以利用这个执行掩码来判断，当前 ChannelHandler 是否符合响应该事件的资格。
     */
    private static int mask0(Class<? extends ChannelHandler> handlerType) {
        int mask = MASK_EXCEPTION_CAUGHT;
        try {
            // 入站处理器handler
            if (ChannelInboundHandler.class.isAssignableFrom(handlerType)) {
                // 使用或运算将所有的入站事件都添加到mask掩码里
                mask |= MASK_ALL_INBOUND;

                // ============================下面的这些方法都是检查某些方法使用要从掩码里去除============================

                //最后在对不感兴趣的事件一一排除（handler中的事件回调方法如果标注了@Skip注解，则认为handler对该事件不感兴趣）
                // 检查handlerType是否需要跳过channelRegistered方法，如果为true则从mask里去除该方法
                if (isSkippable(handlerType, "channelRegistered", ChannelHandlerContext.class)) {
                    // 取反再做与运算，就相当于将 channelRegistered 方法从掩码中去除了
                    mask &= ~MASK_CHANNEL_REGISTERED;
                }
                if (isSkippable(handlerType, "channelUnregistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_UNREGISTERED;
                }
                if (isSkippable(handlerType, "channelActive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_ACTIVE;
                }
                if (isSkippable(handlerType, "channelInactive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_INACTIVE;
                }
                if (isSkippable(handlerType, "channelRead", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_CHANNEL_READ;
                }
                if (isSkippable(handlerType, "channelReadComplete", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_READ_COMPLETE;
                }
                if (isSkippable(handlerType, "channelWritabilityChanged", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_WRITABILITY_CHANGED;
                }
                if (isSkippable(handlerType, "userEventTriggered", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_USER_EVENT_TRIGGERED;
                }
            }

            // 出站handler
            if (ChannelOutboundHandler.class.isAssignableFrom(handlerType)) {
                //如果handler为Outbound类型的，则先将全部outbound事件设置进掩码中
                mask |= MASK_ALL_OUTBOUND;

                //最后对handler不感兴趣的事件从掩码中一一排除
                if (isSkippable(handlerType, "bind", ChannelHandlerContext.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_BIND;
                }
                if (isSkippable(handlerType, "connect", ChannelHandlerContext.class, SocketAddress.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_CONNECT;
                }
                if (isSkippable(handlerType, "disconnect", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DISCONNECT;
                }
                if (isSkippable(handlerType, "close", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_CLOSE;
                }
                if (isSkippable(handlerType, "deregister", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DEREGISTER;
                }
                if (isSkippable(handlerType, "read", ChannelHandlerContext.class)) {
                    mask &= ~MASK_READ;
                }
                if (isSkippable(handlerType, "write", ChannelHandlerContext.class,
                        Object.class, ChannelPromise.class)) {
                    mask &= ~MASK_WRITE;
                }
                if (isSkippable(handlerType, "flush", ChannelHandlerContext.class)) {
                    mask &= ~MASK_FLUSH;
                }
            }

            if (isSkippable(handlerType, "exceptionCaught", ChannelHandlerContext.class, Throwable.class)) {
                mask &= ~MASK_EXCEPTION_CAUGHT;
            }
        } catch (Exception e) {
            // Should never reach here.
            PlatformDependent.throwException(e);
        }

        // 计算出的掩码需要缓存，因为每次向pipeline中添加该类型的handler的时候都需要获取掩码（创建一个channel 就需要为其初始化pipeline）
        return mask;
    }

    @SuppressWarnings("rawtypes")
    private static boolean isSkippable(
            final Class<?> handlerType, final String methodName, final Class<?>... paramTypes) throws Exception {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Method m;
                try {
                    // 从 handlerType clazz里根据方法名称和参数取出method
                    m = handlerType.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Class {} missing method {}, assume we can not skip execution", handlerType, methodName, e);
                    }
                    return false;
                }
                // 方法上有标注skip注解，则认为需要跳过
                return m != null && m.isAnnotationPresent(Skip.class);
            }
        });
    }

    private ChannelHandlerMask() { }

    /**
     * Indicates that the annotated event handler method in {@link ChannelHandler} will not be invoked by
     * {@link ChannelPipeline} and so <strong>MUST</strong> only be used when the {@link ChannelHandler}
     * method does nothing except forward to the next {@link ChannelHandler} in the pipeline.
     * <p>
     * Note that this annotation is not {@linkplain Inherited inherited}. If a user overrides a method annotated with
     * {@link Skip}, it will not be skipped anymore. Similarly, the user can override a method not annotated with
     * {@link Skip} and simply pass the event through to the next handler, which reverses the behavior of the
     * supertype.
     * </p>
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Skip {
        // no value
    }
}
