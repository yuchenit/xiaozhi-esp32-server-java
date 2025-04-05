package com.xiaozhi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xiaozhi.utils.CmsUtils;

@SpringBootApplication
@MapperScan("com.xiaozhi.dao")
public class XiaozhiApplication {

    Logger logger = LoggerFactory.getLogger(XiaozhiApplication.class);

    @Value("${netty.websocket.port:8082}")
    private int nettyPort;

    public static void main(String[] args) {
        SpringApplication.run(XiaozhiApplication.class, args);
    }

    @Bean
    public ApplicationListener<ServletWebServerInitializedEvent> webServerInitializedListener() {
        return event -> {
            int springPort = event.getWebServer().getPort();
            String contextPath = event.getApplicationContext().getEnvironment()
                    .getProperty("server.servlet.context-path", "");

            try {
                // 获取本地实际 IP 地址
                String localIp = CmsUtils.getLocalIPAddress();

                logger.info("==========================================================");
                logger.info("Spring Boot服务运行于: http://{}:{}{}", localIp, springPort, contextPath);
                logger.info("WebSocket服务运行于:");
                logger.info("ws://{}:{}/ws/xiaozhi/v1/", localIp, nettyPort);
                logger.info("==========================================================");
            } catch (Exception e) {
                logger.error("无法获取本地 IP 地址：" + e.getMessage());
            }
        };
    }

}