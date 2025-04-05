package com.xiaozhi.websocket.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WebSocket 控制帧处理器
 * 处理 WebSocket 协议的控制帧，如 Ping、Pong、Close 等
 */
@Slf4j
@Component
public class WebSocketControlFrameHandler extends SimpleChannelInboundHandler<Object> {

  private static final Logger logger = LoggerFactory.getLogger(WebSocketControlFrameHandler.class);

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Object frame){
    if (frame instanceof PingWebSocketFrame) {
      // 处理 Ping 帧
      logger.debug("收到 Ping 帧");
      ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) frame).content().retain()));

    } else if (frame instanceof CloseWebSocketFrame) {
      // 处理关闭帧
      logger.info("收到关闭帧");
      ctx.close();
    } else {
      // 将其他类型的帧传递给下一个处理器
      ReferenceCountUtil.retain(frame); // 增加引用计数
      ctx.fireChannelRead(frame);
    }
  }
}
