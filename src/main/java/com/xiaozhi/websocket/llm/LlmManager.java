package com.xiaozhi.websocket.llm;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.websocket.llm.api.LlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import com.xiaozhi.websocket.llm.factory.LlmServiceFactory;
import com.xiaozhi.websocket.llm.memory.ChatMemory;
import com.xiaozhi.websocket.llm.memory.ModelContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM管理器
 * 负责管理和协调LLM相关功能
 */
@Service
public class LlmManager {
    private static final Logger logger = LoggerFactory.getLogger(LlmManager.class);

    // 句子结束标点符号模式（中英文句号、感叹号、问号）
    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("[。！？!?]");
    
    // 逗号、分号等停顿标点
    private static final Pattern PAUSE_PATTERN = Pattern.compile("[，、；,;]");
    
    // 数字模式（用于检测小数点是否在数字中）
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+\\.\\d+");

    // 最小句子长度（字符数）
    private static final int MIN_SENTENCE_LENGTH = 5;
    
    // 新句子判断的token阈值
    private static final int NEW_SENTENCE_TOKEN_THRESHOLD = 15;

    @Autowired
    private SysConfigService configService;

    @Autowired
    private ChatMemory chatMemory;

    // 设备LLM服务缓存，每个设备只保留一个服务
    private Map<String, LlmService> deviceLlmServices = new ConcurrentHashMap<>();
    // 设备当前使用的configId缓存
    private Map<String, Integer> deviceConfigIds = new ConcurrentHashMap<>();

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
            logger.error("处理查询时出错: {}", e.getMessage(), e);
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

            // 调用LLM流式接口
            llmService.chatStream(message, modelContext, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
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
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理用户查询（流式方式，使用句子切分，带有开始和结束标志）
     * 
     * @param device          设备信息
     * @param message         用户消息
     * @param sentenceHandler 句子处理函数，接收句子内容、是否是开始句子、是否是结束句子
     */
    public void chatStreamBySentence(SysDevice device, String message,
            TriConsumer<String, Boolean, Boolean> sentenceHandler) {
        try {
            final StringBuilder currentSentence = new StringBuilder(); // 当前句子的缓冲区
            final StringBuilder contextBuffer = new StringBuilder(); // 上下文缓冲区，用于检测数字中的小数点
            final AtomicInteger sentenceCount = new AtomicInteger(0); // 已发送句子的计数
            final StringBuilder fullResponse = new StringBuilder(); // 完整响应的缓冲区
            final AtomicReference<String> pendingSentence = new AtomicReference<>(null); // 暂存的句子
            final AtomicInteger tokensSinceLastEnd = new AtomicInteger(0); // 自上一个句子结束标点符号以来的token数
            final AtomicBoolean lastTokenWasEndMark = new AtomicBoolean(false); // 上一个token是否为句子结束标记

            // 创建流式响应监听器
            StreamResponseListener streamListener = new StreamResponseListener() {
                @Override
                public void onStart() {
                }

                @Override
                public void onToken(String token) {
                    // 将token添加到完整响应
                    fullResponse.append(token);
                    
                    // 将token添加到上下文缓冲区（保留最近的字符以检测数字模式）
                    contextBuffer.append(token);
                    if (contextBuffer.length() > 20) { // 保留足够的上下文
                        contextBuffer.delete(0, contextBuffer.length() - 20);
                    }

                    // 将token添加到当前句子缓冲区
                    currentSentence.append(token);
                    
                    // 检查是否是句子结束标点符号
                    boolean isEndMark = SENTENCE_END_PATTERN.matcher(token).find();
                    
                    // 如果当前token是句子结束标点，需要检查它是否是数字中的小数点
                    if (isEndMark && token.equals(".")) {
                        // 检查小数点是否在数字中
                        String context = contextBuffer.toString();
                        Matcher numberMatcher = NUMBER_PATTERN.matcher(context);
                        
                        // 如果找到数字模式（如"0.271"），则不视为句子结束标点
                        if (numberMatcher.find() && numberMatcher.end() >= context.length() - 3) {
                            isEndMark = false;
                        }
                    }
                    
                    // 如果当前token是句子结束标点，或者上一个token是句子结束标点且当前是空白字符
                    if (isEndMark || (lastTokenWasEndMark.get() && token.trim().isEmpty())) {
                        // 重置计数器
                        tokensSinceLastEnd.set(0);
                        lastTokenWasEndMark.set(isEndMark);
                        
                        // 当前句子包含句子结束标点，检查是否达到最小长度
                        String sentence = currentSentence.toString().trim();
                        if (sentence.length() >= MIN_SENTENCE_LENGTH) {
                            // 如果有暂存的句子，先发送它（isEnd = false）
                            if (pendingSentence.get() != null) {
                                sentenceHandler.accept(pendingSentence.get(), sentenceCount.get() == 0, false);
                                sentenceCount.incrementAndGet();
                            }

                            // 将当前句子标记为暂存句子
                            pendingSentence.set(sentence);

                            // 清空当前句子缓冲区
                            currentSentence.setLength(0);
                        }
                    } else {
                        // 更新上一个token的状态
                        lastTokenWasEndMark.set(false);
                        
                        // 增加自上一个句子结束标点以来的token计数
                        tokensSinceLastEnd.incrementAndGet();
                        
                        // 检查是否有停顿标点（如逗号）且已经累积了足够多的内容
                        if (PAUSE_PATTERN.matcher(token).find() && pendingSentence.get() != null && 
                            tokensSinceLastEnd.get() >= NEW_SENTENCE_TOKEN_THRESHOLD) {
                            // 如果有暂存的句子且已经累积了足够多的token，发送暂存的句子
                            sentenceHandler.accept(pendingSentence.get(), sentenceCount.get() == 0, false);
                            sentenceCount.incrementAndGet();
                            pendingSentence.set(null);
                        }
                        
                        // 如果自上一个句子结束标点后已经累积了非常多的token（表示新句子已经开始）
                        // 且有暂存的句子，则发送暂存的句子
                        if (tokensSinceLastEnd.get() >= NEW_SENTENCE_TOKEN_THRESHOLD && pendingSentence.get() != null) {
                            sentenceHandler.accept(pendingSentence.get(), sentenceCount.get() == 0, false);
                            sentenceCount.incrementAndGet();
                            pendingSentence.set(null);
                        }
                    }
                }

                @Override
                public void onComplete(String completeResponse) {
                    // 如果有暂存的句子，发送它
                    if (pendingSentence.get() != null) {
                        boolean isFirst = sentenceCount.get() == 0;
                        boolean isLast = currentSentence.length() == 0 || 
                                         !containsSubstantialContent(currentSentence.toString());
                        
                        sentenceHandler.accept(pendingSentence.get(), isFirst, isLast);
                        sentenceCount.incrementAndGet();
                        pendingSentence.set(null);
                    }

                    // 处理当前缓冲区剩余的内容（如果有）
                    if (currentSentence.length() > 0 && containsSubstantialContent(currentSentence.toString())) {
                        String sentence = currentSentence.toString().trim();
                        sentenceHandler.accept(sentence, sentenceCount.get() == 0, true);
                        sentenceCount.incrementAndGet();
                    }
                }

                @Override
                public void onError(Throwable e) {
                    logger.error("流式响应出错: {}", e.getMessage(), e);
                    // 发送错误信号
                    sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
                }
            };

            // 调用现有的流式方法
            chatStream(device, message, streamListener);

        } catch (Exception e) {
            logger.error("处理流式查询时出错: {}", e.getMessage(), e);
            // 发送错误信号
            sentenceHandler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
        }
    }
    
    /**
     * 判断文本是否包含实质性内容（不仅仅是空白字符或标点符号）
     * 
     * @param text 要检查的文本
     * @return 是否包含实质性内容
     */
    private boolean containsSubstantialContent(String text) {
        if (text == null || text.trim().length() < MIN_SENTENCE_LENGTH) {
            return false;
        }
        
        // 移除所有标点符号和空白字符后，检查是否还有内容
        String stripped = text.replaceAll("[\\p{P}\\s]", "");
        return stripped.length() >= 2; // 至少有两个非标点非空白字符
    }

    /**
     * 获取或创建LLM服务
     * 
     * @param deviceId 设备ID
     * @param configId 配置ID
     * @return LLM服务
     */
    public LlmService getLlmService(String deviceId, Integer configId) {
        // 检查设备是否已有服务且配置ID不同
        Integer currentConfigId = deviceConfigIds.get(deviceId);
        if (currentConfigId != null && !currentConfigId.equals(configId)) {
            // 配置ID变更，移除旧服务
            deviceLlmServices.remove(deviceId);
        }

        // 更新设备当前使用的configId
        deviceConfigIds.put(deviceId, configId);

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

    /**
     * 三参数消费者接口
     */
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}