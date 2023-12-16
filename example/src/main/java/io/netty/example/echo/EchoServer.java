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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        // 创建主从reactor线程组
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            //配置主从Reactor
            b.group(bossGroup, workerGroup)
                    // 配置主Reactor的channel类型，用于绑定端口地址以及创建客户端SocketChannel，这里的channel可以近似的理解为socket
                    // Netty中的NioServerSocketChannel.class就是对JDK NIO中ServerSocketChannel的封装。
                    // 而用于表示客户端连接的NioSocketChannel是对JDK NIO SocketChannel封装。
                    // 注意这时只是配置阶段，NioServerSocketChannel此时并未被创建。它是在启动的时候才会被创建出来。
                    .channel(NioServerSocketChannel.class)
                    //设置被MainReactor管理的NioServerSocketChannel的Socket选项
                    .option(ChannelOption.SO_BACKLOG, 100)
                    // 设置主Reactor中Channel->pipline->handler，可以看到这里只能添加一个handler到pipeline上，如果要添加多个则需要用ChannelInitializer
             .handler(new LoggingHandler(LogLevel.INFO))
                    // 设置从Reactor中注册channel的pipeline。ChannelInitializer是用于当SocketChannel成功注册到绑定的Reactor（selector）上后，
                    // 用于初始化该SocketChannel的Pipeline。它的 initChannel 方法会在注册成功后被回调执行，然后在该方法里就会向channel的pipeline上添加多个自定义的handler
                    // ChannelInitializer是一种特殊的ChannelHandler，用于初始化pipeline。适用于向pipeline中添加多个ChannelHandler的场景。
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     // 获取channel上的pipeline并向其中添加handler
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

            // Start the server. 绑定端口启动服务，开始监听accept事件，上面的步骤都是在赋值，具体的启动流程源码要从这里作为入口进行阅读
            ChannelFuture f = b.bind(PORT).sync();

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads. 优雅关闭主从Reactor线程组里的所有Reactor线程
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
