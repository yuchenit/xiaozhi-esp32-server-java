package com.xiaozhi.websocket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.SESSION_ID;

/**
 * WebSocket 消息服务
 * 负责构建和发送 WebSocket 文本消息
 */
@Service("WebSocketMessageService")
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 发送简单状态消息
     * 
     * @param channel Netty 通道
     * @param type    消息类型
     * @param state   消息状态
     */
    public void sendMessage(Channel channel, String type, String state) {
        if (channel == null || !channel.isActive()) {
            logger.warn("无法发送消息 - 通道不可用或为null");
            return;
        }

        try {
            String sessionId = channel.attr(SESSION_ID).get();
            ObjectNode response = objectMapper.createObjectNode();
            response.put("session_id", sessionId);
            response.put("type", type);
            response.put("state", state);

            channel.writeAndFlush(new TextWebSocketFrame(response.toString()));
            logger.info("消息发送成功 - SessionId: {}, Type: {}, State: {}", sessionId, type, state);
        } catch (Exception e) {
            logger.error("消息发送异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送带文本内容的消息
     * 
     * @param channel Netty 通道
     * @param type    消息类型
     * @param state   消息状态
     * @param text    文本内容
     */
    public void sendMessage(Channel channel, String type, String state, String text) {
        if (channel == null || !channel.isActive()) {
            logger.warn("无法发送消息 - 通道不可用或为null");
            return;
        }

        try {
            String sessionId = channel.attr(SESSION_ID).get();
            ObjectNode response = objectMapper.createObjectNode();
            response.put("session_id", sessionId);
            response.put("type", type);
            response.put("state", state);
            response.put("text", text);

            channel.writeAndFlush(new TextWebSocketFrame(response.toString()));
            logger.info("发送消息 - SessionId: {}, Type: {}, State: {}, Text: {}",
                    sessionId, type, state, text);
        } catch (Exception e) {
            logger.error("发送消息时发生异常: {}", e.getMessage(), e);
        }
    }
}
