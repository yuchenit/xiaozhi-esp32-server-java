package com.xiaozhi.websocket.config;

import com.xiaozhi.websocket.handler.ReactiveWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class WebFluxWebSocketConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebFluxWebSocketConfig.class);

    // 定义为public static以便其他类可以访问
    public static final String WS_PATH = "/ws/xiaozhi/v1/";

    @Bean
    public HandlerMapping webSocketHandlerMapping(ReactiveWebSocketHandler webSocketHandler) {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put(WS_PATH, webSocketHandler);

        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);

        return handlerMapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}