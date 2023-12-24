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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 * ServerBootstrap主要负责对主从Reactor线程组相关的配置进行管理，其中带child前缀的配置方法是对从Reactor线程组的相关配置管理。
 * 从Reactor线程组中的Sub Reactor负责管理的客户端NioSocketChannel相关配置存储在ServerBootstrap结构中。
 * 父类AbstractBootstrap则是主要负责对主Reactor线程组相关的配置进行管理，以及主Reactor线程组中的Main Reactor负责处理的服务端ServerSocketChannel相关的配置管理。
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes. 由于客户端NioSocketChannel是由从Reactor线程组中的Sub Reactor来负责处理，所以涉及到客户端NioSocketChannel所有的方法和配置全部是以child前缀开头
    // 客户端SocketChannel对应的ChannelOption配置
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    private volatile EventLoopGroup childGroup;
    // SocketChannel上的pipeline上的handler
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        //父类管理主Reactor线程组
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    @Override
    void init(Channel channel) {
        // 1、向NioServerSocketChannelConfig设置ServerSocketChannelOption，这里可以看到我们在Bootstrap中为NioServerSocketChannel设置的
        // 一些option属性，在这里会设置到channel上
        setChannelOptions(channel, newOptionsArray(), logger);
        // 2、向netty自定义的NioServerSocketChannel设置attributes
        setAttributes(channel, attrs0().entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY));

        // 获取到channel的pipeline（该pipeline在channel创建的时候就创建好了, @see io.netty.channel.AbstractChannel.AbstractChannel(io.netty.channel.Channel)）
        ChannelPipeline p = channel.pipeline();

        // 3、获取从Reactor线程组childGroup，以及用于初始化客户端NioSocketChannel的ChannelInitializer,ChannelOption,ChannelAttributes，
        // 这些信息均是由用户在启动的时候向ServerBootstrap添加的客户端NioServerChannel配置信息。这里用这些信息来初始化ServerBootstrapAcceptor。
        // 因为后续会在ServerBootstrapAcceptor中接收客户端连接以及创建NioServerChannel。
        // 获取从Reactor线程组
        final EventLoopGroup currentChildGroup = childGroup;
        // 获取用于初始化客户端NioSocketChannel的ChannelInitializer（在启动类里通过 childHandler 方法添加的）
        final ChannelHandler currentChildHandler = childHandler;
        // 获取用户配置的客户端SocketChannel的channelOption以及attributes
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        synchronized (childOptions) {
            // 可以看到 currentChildOptions 取的是childOptions（表示：用户为客户端channel设置的一些配置信息）,在启动类中通过 childOption()方法添加
            currentChildOptions = childOptions.entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
        // 用户为客户端channel设置的一些attribute
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = childAttrs.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);

        // 4、向 NioServerSocketChannel 中的pipeline添加初始化ChannelHandler的逻辑
        // 这里为什么不干脆直接将ChannelHandler添加到pipeline中，而是又使用到了ChannelInitializer呢? 其实原因有两点：
        // 1、为了保证线程安全地初始化pipeline，所以初始化的动作需要由Reactor线程进行，而当前线程是用户程序的启动Main线程 并不是Reactor线程。这里不能立即初始化。
        // 2、初始化Channel中pipeline的动作，需要等到Channel注册到对应的Reactor中才可以进行初始化，当前只是创建好了NioServerSocketChannel，但并未注册到Main Reactor上。
        // 初始化NioServerSocketChannel中pipeline的时机是：当NioServerSocketChannel注册到Main Reactor之后，绑定端口地址之前。
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                final ChannelPipeline pipeline = ch.pipeline();
                // ServerBootstrap中用户指定的channelHandler（比如：我们在 io.netty.example.echo.EchoServer 中配置的 LoggingHandler）
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    // 当执行 initChannel 方法时就会同步添加这个handler到pipeline，而下面的那个handler为啥又要提交到reactor线程进行异步添加呢？
                    pipeline.addLast(handler);
                }

                // 这里为啥又要异步添加呢？
                // 添加用于接收客户端连接的acceptor，这里是通过channel获取到reactor线程，然后将往pipeline上添加ServerBootstrapAcceptor的任务提交给reactor线程进行执行
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        // 上面获取的从reactor线程组和用户为从reactor配置的一些option和attribute都传递给ServerBootstrapAcceptor，保存到对应的属性上
                        // ServerBootstrapAcceptor 就是我们serverSocketChannel创建socketChannel的实现地方了，重点关注
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    /**
     * 用于处理NioServerSocketChannel上的accept
     * @author wenpan 2023/12/10 3:51 下午
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        // 从reactor线程组
        private final EventLoopGroup childGroup;
        // NioSocketChannel 的pipeline上添加handler信息，一般是ChannelInitializer（可以用来为NIOSocketChannel上添加多个handler）
        private final ChannelHandler childHandler;
        // NioSocketChannel 上需要添加的一些属性（由用户在配置 ServerBootstrap 时指定的）
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        // NioSocketChannel 上需要添加的一些属性（由用户在配置 ServerBootstrap 时指定的）
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // client NIOSocketChannel
            final Channel child = (Channel) msg;
            // 给创建的 NIOSocketChannel 上新增handler，这里就是我们通过 ServerBootstrap 指定的
            child.pipeline().addLast(childHandler);
            // 利用配置的属性初始化客户端NioSocketChannel，将我们在 ServerBootstrap 中指定的有关 NIOSocketChannel 的option和attrs设置到channel上
            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            try {
                /*
                 * 1：在Sub Reactor线程组中选择一个Reactor绑定
                 * 2：将客户端SocketChannel注册到绑定的Reactor上
                 * 3：SocketChannel注册到sub reactor中的selector上，并监听OP_READ事件
                 * 客户端NioSocketChannel向Sub Reactor Group注册的流程基本完全和服务端NioServerSocketChannel向Main Reactor Group注册流程一样
                 * */
                // 可以看到这里register传入的channel是NIOSocketChannel，区别于NIOServerSocketChannel注册时传入的channel为 NIOServerSocketChannel
                // @see io.netty.bootstrap.AbstractBootstrap.initAndRegister
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        // 在服务端NioServerSocketChannel注册的时候我们会在listener中向Main Reactor提交bind绑定端口地址任务。(@see io.netty.bootstrap.AbstractBootstrap.doBind)
                        // 但是在NioSocketChannel注册的时候，只会在listener中处理一下注册失败的情况。
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
