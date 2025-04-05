package com.xiaozhi.websocket.config;

import com.xiaozhi.websocket.handler.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Configuration
@Profile("!test")
public class NettyWebSocketConfig {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketConfig.class);

    @Value("${netty.websocket.port:8091}")
    private int port;

    @Value("${netty.websocket.path:/ws/xiaozhi/v1/}")
    private String websocketPath;

    @Value("${netty.websocket.bossThreads:1}")
    private int bossThreads;

    @Value("${netty.websocket.workerThreads:4}")
    private int workerThreads;

    @Value("${netty.websocket.maxFrameSize:65536}")
    private int maxFrameSize;

    @Value("${netty.websocket.idleTimeout:300}")
    private int idleTimeout;

    @Value("${netty.websocket.writeIdleTimeout:120}")
    private int writeIdleTimeout;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @PostConstruct
    public void start() throws Exception {
        logger.info("启动Netty WebSocket服务器，端口：{}，路径：{}", port, websocketPath);

        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    // HTTP协议编解码器
                                    .addLast(new HttpServerCodec())
                                    // 支持大数据流
                                    .addLast(new ChunkedWriteHandler())
                                    // HTTP消息聚合器
                                    .addLast(new HttpObjectAggregator(maxFrameSize))
                                    // WebSocket握手处理
                                    .addLast(new WebSocketHandshakeHandler())
                                    //处理 WebSocket 协议的升级和消息解析
                                    .addLast(new WebSocketServerProtocolHandler(
                                            websocketPath,
                                            null,
                                            true,
                                            maxFrameSize,
                                            false,  // 不检查起始帧
                                            true    // 允许扩展
                                    ))
                                    // WebSocket压缩支持
                                    .addLast(new WebSocketServerCompressionHandler())
                                    // 空闲连接检测
                                    .addLast(new IdleStateHandler(idleTimeout, idleTimeout,
                                            idleTimeout, TimeUnit.SECONDS))
                                    // 心跳处理
                                    .addLast(new WebSocketHeartbeatHandler())
                                    // WebSocket文本帧处理
                                    .addLast(new TextWebSocketFrameHandler())
                                    // WebSocket控制帧处理
                                    .addLast(new WebSocketControlFrameHandler())
                                    // WebSocket二进制帧处理
                                    .addLast(new BinaryWebSocketFrameHandler())
                                    // 异常处理（放在最后捕获所有未处理的异常）
                                    .addLast(new WebSocketExceptionHandler());
                        }
                    });

            bootstrap.bind(port).sync();
            logger.info("Netty WebSocket服务器启动成功，监听端口: {}", port);
        } catch (Exception e) {
            logger.error("Netty WebSocket服务器启动失败", e);
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        logger.info("关闭Netty WebSocket服务器");
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
