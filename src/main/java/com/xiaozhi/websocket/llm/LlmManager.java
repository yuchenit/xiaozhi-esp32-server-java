package com.xiaozhi.websocket.llm;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.websocket.llm.api.LlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import com.xiaozhi.websocket.llm.factory.LlmServiceFactory;
import com.xiaozhi.websocket.llm.memory.ChatMemory;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * LLM管理器
 * 负责管理和协调LLM相关功能
 */
@Slf4j
@Service
public class LlmManager {

    @Autowired
    private SysConfigService configService;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    @Qualifier("baseThreadPool")
    private ExecutorService baseThreadPool;

    // 设备LLM服务缓存，每个设备只保留一个服务
    private ConcurrentHashMap<String, LlmService> deviceLlmServices = new ConcurrentHashMap<>();

    // 设备当前使用的configId缓存
    private ConcurrentHashMap<String, Integer> deviceConfigIds = new ConcurrentHashMap<>();

    /**
     * 处理用户查询（同步方式）
     * 
     * @param device  设备信息
     * @param message 用户消息
     * @return 模型回复
     */
    public String chat(SysDevice device, String message) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getModelId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory);

            // 调用LLM
            return llmService.chat(message, modelContext);

        } catch (Exception e) {
            log.error("处理查询时出错: {}", e.getMessage(), e);
            return "抱歉，我在处理您的请求时遇到了问题。请稍后再试。";
        }
    }

    /**
     * 处理用户查询（流式方式）
     * 
     * @param device         设备信息
     * @param message        用户消息
     * @param streamListener 流式响应监听器
     */
    public void chatStream(SysDevice device, String message, StreamResponseListener streamListener) {
        try {
            String deviceId = device.getDeviceId();
            Integer configId = device.getModelId();

            // 获取LLM服务
            LlmService llmService = getLlmService(deviceId, configId);

            // 创建模型上下文
            ModelContext modelContext = new ModelContext(
                    deviceId,
                    device.getSessionId(),
                    device.getRoleId(),
                    chatMemory);

            baseThreadPool.execute(()->{
                // 调用LLM流式接口
                try {
                    llmService.chatStream(message, modelContext, streamListener);
                } catch (IOException e) {
                    log.error("处理流式查询时出错: {}", e.getMessage(), e);
                    streamListener.onError(e);
                }
            });

        } catch (Exception e) {
            log.error("处理流式查询时出错: {}", e.getMessage(), e);
            streamListener.onError(e);
        }
    }

    /**
     * 处理用户查询（流式方式，使用lambda表达式）
     * 
     * @param device       设备信息
     * @param message      用户消息
     * @param tokenHandler token处理函数，接收每个生成的token
     */
    public void chatStream(SysDevice device, String message, Consumer<String> tokenHandler) {
        try {
            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                private final StringBuilder fullResponse = new StringBuilder();

                @Override
                public void onStart() {
                    log.info("开始流式响应");
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);

                    // 调用处理函数
                    tokenHandler.accept(token);
                }

                @Override
                public void onComplete(String completeResponse) {
                    log.info("流式响应完成，完整响应: {}", completeResponse);
                }

                @Override
                public void onError(Throwable e) {
                    log.error("流式响应出错: {}", e.getMessage(), e);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            log.error("处理流式查询时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理用户聊天（流式方式，使用句子切分，带有开始和结束标志）
     * 
     * @param device          设备信息
     * @param message         用户消息
     * @param sentenceHandler 句子处理函数，接收句子内容、是否是开始句子、是否是结束句子
     */
    public void chatStreamBySentence(SysDevice device, String message,
            TriConsumer<String, Boolean, Boolean> sentenceHandler) {
        try {
            final SentenceProcessor processor = new SentenceProcessor(sentenceHandler);

            StreamResponseListener listener = new StreamResponseListener() {
                @Override
                public void onStart() {
                }

                @Override
                public void onToken(String token) {
                    processor.processToken(token);
                }

                @Override
                public void onComplete(String response) {
                    processor.finalizeProcessing();
                }

                @Override
                public void onError(Throwable e) {
                    processor.handleError(e);
                }
            };

            chatStream(device, message, listener);

        } catch (Exception e) {
            log.error("处理流式查询时出错: {}", e.getMessage(), e);
            // 发送错误信号
            sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
        }
    }

    /**
     * 获取或创建LLM服务
     * 
     * @param deviceId 设备ID
     * @param configId 配置ID
     * @return LLM服务
     */
    public LlmService getLlmService(String deviceId, Integer configId) {
        baseThreadPool.execute(()->{
            // 检查设备是否已有服务且配置ID不同
            Integer currentConfigId = deviceConfigIds.get(deviceId);
            if (currentConfigId != null && !currentConfigId.equals(configId)) {
                // 配置ID变更，移除旧服务
                deviceLlmServices.remove(deviceId);
            }

            // 更新设备当前使用的configId
            deviceConfigIds.put(deviceId, configId);
        });

        // 获取或创建服务
        return deviceLlmServices.computeIfAbsent(deviceId, k -> createLlmService(configId));
    }

    /**
     * 创建LLM服务
     * 
     * @param configId 配置ID
     * @return LLM服务
     */
    private LlmService createLlmService(Integer configId) {
        // 根据配置ID查询配置
        SysConfig config = configService.selectConfigById(configId);
        if (config == null) {
            throw new RuntimeException("找不到配置ID: " + configId);
        }

        String provider = config.getProvider().toLowerCase();
        String model = config.getConfigName();
        String endpoint = config.getApiUrl();
        String apiKey = config.getApiKey();
        String appId = config.getAppId();
        String apiSecret = config.getApiSecret();

        return LlmServiceFactory.createLlmService(provider, endpoint, appId, apiKey, apiSecret, model);
    }

    /**
     * 清除设备缓存
     * 
     * @param deviceId 设备ID
     */
    public void clearDeviceCache(String deviceId) {
        deviceLlmServices.remove(deviceId);
        deviceConfigIds.remove(deviceId);
        chatMemory.clearMessages(deviceId);
    }

}