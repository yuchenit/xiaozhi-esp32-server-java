package com.xiaozhi.websocket.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.IllegalReferenceCountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.DEVICE_ID;
import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.SESSION_ID;

/**
 * WebSocket 异常处理器
 * 处理 pipeline 中的所有未捕获异常
 */
@Component
@ChannelHandler.Sharable
public class WebSocketExceptionHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger = LoggerFactory.getLogger(WebSocketExceptionHandler.class);

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    String sessionId = ctx.channel().attr(SESSION_ID).get();
    String deviceId = ctx.channel().attr(DEVICE_ID).get();

    logger.error("WebSocket处理异常 - SessionId: {}, DeviceId: {}, 异常: {}",
        sessionId, deviceId, cause.getMessage(), cause);

    // 1. 仅在致命异常时关闭连接
    if (isFatalException(cause)) {
      // 2. 发送 WebSocket 关闭帧（优雅关闭）
      ctx.writeAndFlush(new CloseWebSocketFrame(1000, "Internal Server Error"))
              .addListener(f -> {
                if (f.isSuccess()) {
                  ctx.close();
                } else {
                  ctx.close();
                }
              });
    }
  }

  /**
   * 判断是否为致命异常，需要关闭连接
   */
  private boolean isFatalException(Throwable cause) {
    // 网络IO异常通常需要关闭连接
    return cause instanceof java.io.IOException
            || cause instanceof java.net.SocketException
            // 协议解析错误也可能需要关闭连接
            || cause instanceof io.netty.handler.codec.DecoderException
            || cause instanceof io.netty.handler.codec.CorruptedFrameException
            // 引用计数错误
            || cause instanceof IllegalReferenceCountException
            ;
  }
}
