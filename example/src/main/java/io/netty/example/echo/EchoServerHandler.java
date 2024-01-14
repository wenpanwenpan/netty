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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.AbstractNioMessageChannel;

/**
 * Handler implementation for the echo server.
 */
@Sharable
public class EchoServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * read-loop时，每再socket的接收缓冲区里读取一次数据就触发一次该方法，和下面的channelReadComplete类比，
     * channelReadComplete是整个read-loop读取完毕以后才会触发一次
     * @see AbstractNioMessageChannel.NioMessageUnsafe#read()
     * @author wenpan 2024/1/14 4:59 下午
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 处理网络请求，比如解码,反序列化等操作
        //此处的msg就是Netty在read loop中从NioSocketChannel中读取到的ByteBuffer
        // write 就是将msg写入到 ChannelOutboundBuffer 缓冲区里即可
        ChannelFuture future = ctx.write(msg);
        // 将msg写入channel的ChannelOutboundBuffer缓冲区里（单向链表），并且将msg从ChannelOutboundBuffer缓冲区刷写到socket待发送区
//        ctx.writeAndFlush(msg);

        // 从tail节点开始向后传播出站事件（write是出站事件）
        ctx.channel().write(msg);
        // 向future注册回调，当msg被写入到socket的发送缓冲区时，netty会回调这个方法
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                Throwable cause = future.cause();
                if (cause != null) {
                    // 处理异常情况
                } else {
                    // 写入Socket成功后，Netty会通知到这里
                }
            }
        });
        ctx.write(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        // 本次OP_READ事件处理完毕（应该是整个read-loop执行完毕后会传播这个事件，比如：read-loop读取了16次数据，或者socket中没有待读取的数据了）
        // 决定是否向客户端响应处理结果
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        cause.printStackTrace();
        ctx.close();
    }
}
