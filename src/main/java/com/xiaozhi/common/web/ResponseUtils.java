package com.xiaozhi.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * ServerHttpResponse帮助类
 */
public final class ResponseUtils {
    public static final Logger log = LoggerFactory.getLogger(ResponseUtils.class);

    /**
     * 发送文本。使用UTF-8编码。
     * 
     * @param exchange ServerWebExchange
     * @param text     发送的字符串
     */
    public static Mono<Void> renderText(ServerWebExchange exchange, String text) {
        return render(exchange, MediaType.TEXT_PLAIN, text);
    }

    /**
     * 发送json。使用UTF-8编码。
     * 
     * @param exchange ServerWebExchange
     * @param text     发送的字符串
     */
    public static Mono<Void> renderJson(ServerWebExchange exchange, String text) {
        return render(exchange, MediaType.APPLICATION_JSON, text);
    }

    /**
     * 发送xml。使用UTF-8编码。
     * 
     * @param exchange ServerWebExchange
     * @param text     发送的字符串
     */
    public static Mono<Void> renderXml(ServerWebExchange exchange, String text) {
        return render(exchange, MediaType.TEXT_XML, text);
    }

    /**
     * 发送内容。使用UTF-8编码。
     * 
     * @param exchange
     * @param mediaType
     * @param text
     */
    public static Mono<Void> render(ServerWebExchange exchange, MediaType mediaType, String text) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().setContentType(mediaType);
        response.getHeaders().add(HttpHeaders.PRAGMA, "No-cache");
        response.getHeaders().add(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.getHeaders().setExpires(0);
        
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}