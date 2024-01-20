package io.netty.example.event;

/**
 * 自定义事件
 *
 * @author wenpan 2024/1/20 11:45 上午
 */
public final class OurOwnDefinedEvent {
 
    public static final OurOwnDefinedEvent INSTANCE = new OurOwnDefinedEvent();

    private OurOwnDefinedEvent() { }
}