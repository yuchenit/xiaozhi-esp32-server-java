package com.xiaozhi;

import com.xiaozhi.websocket.config.WebFluxWebSocketConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.reactive.context.ReactiveWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xiaozhi.utils.CmsUtils;

@SpringBootApplication
@MapperScan("com.xiaozhi.dao")
public class XiaozhiApplication {

    Logger logger = LoggerFactory.getLogger(XiaozhiApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(XiaozhiApplication.class, args);
    }

    @Bean
    public ApplicationListener<ReactiveWebServerInitializedEvent> webServerInitializedListener() {
        return event -> {
            int port = event.getWebServer().getPort();
            String contextPath = event.getApplicationContext().getEnvironment()
                    .getProperty("spring.webflux.base-path", "");

            try {
                // 获取本地实际 IP 地址
                String localIp = CmsUtils.getLocalIPAddress();

                logger.info("==========================================================");
                logger.info("WebFlux WebSocket service is running at:");
                logger.info("ws://" + localIp + ":" + port + contextPath + WebFluxWebSocketConfig.WS_PATH);
                logger.info("==========================================================");
            } catch (Exception e) {
                logger.error("无法获取本地 IP 地址：" + e.getMessage());
            }
        };
    }
}