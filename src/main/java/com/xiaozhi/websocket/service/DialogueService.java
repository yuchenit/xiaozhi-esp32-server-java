package com.xiaozhi.websocket.service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.websocket.llm.LlmManager;
import com.xiaozhi.websocket.service.VadService.VadStatus;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.factory.SttServiceFactory;
import com.xiaozhi.websocket.tts.factory.TtsServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.text.DecimalFormat;

/**
 * 对话处理服务
 * 负责处理语音识别和对话生成的业务逻辑
 */
@Service
public class DialogueService {
    private static final Logger logger = LoggerFactory.getLogger(DialogueService.class);
    
    // 用于格式化浮点数，保留2位小数
    private static final DecimalFormat df = new DecimalFormat("0.00");

    @Autowired
    private LlmManager llmManager;

    @Autowired
    private AudioService audioService;

    @Autowired
    private TtsServiceFactory ttsService;

    @Autowired
    private SttServiceFactory sttServiceFactory;

    @Autowired
    private MessageService messageService;

    @Autowired
    private VadService vadService;

    @Autowired
    private SessionManager sessionManager;
    
    // 添加一个每个会话的TTS请求限制器
    private final Map<String, Semaphore> sessionTtsLimiters = new ConcurrentHashMap<>();
    
    // 添加一个每个会话的句子序列号计数器
    private final Map<String, AtomicInteger> sessionSentenceCounters = new ConcurrentHashMap<>();
    
    // 添加会话的语音识别开始时间记录
    private final Map<String, Long> sessionSttStartTimes = new ConcurrentHashMap<>();
    
    // 添加会话的模型回复开始时间记录
    private final Map<String, Long> sessionLlmStartTimes = new ConcurrentHashMap<>();
    
    // 添加会话的TTS开始时间记录
    private final Map<String, Map<Integer, Long>> sessionTtsStartTimes = new ConcurrentHashMap<>();
    
    // 添加会话的完整回复内容
    private final Map<String, StringBuilder> sessionFullResponses = new ConcurrentHashMap<>();

    private final Semaphore ttsRequestLimit = new Semaphore(1); // 限制同时进行的TTS请求数量，保证TTS请求有序执行

    /**
     * 处理音频数据
     * 
     * @param session  WebSocket会话
     * @param opusData Opus格式的音频数据
     * @return 处理结果
     */
    public Mono<Void> processAudioData(WebSocketSession session, byte[] opusData) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);

        // 如果设备未注册或不在监听状态，忽略音频数据
        if (device == null || !sessionManager.isListening(sessionId)) {
            return Mono.empty();
        }

        SysConfig sttConfig = null;
        SysConfig ttsConfig = null;

        if (device.getSttId() != null) {
            sttConfig = sessionManager.getCachedConfig(device.getSttId());
        }

        if (device.getTtsId() != null) {
            ttsConfig = sessionManager.getCachedConfig(device.getTtsId());
        }

        // 创建最终变量以在lambda中使用
        final SysConfig finalSttConfig = sttConfig;
        final SysConfig finalTtsConfig = ttsConfig;

        return Mono.fromCallable(() -> {
            // 使用VAD处理音频数据
            return vadService.processAudio(sessionId, opusData);
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(vadResult -> {
                    // 如果VAD处理出错，直接返回
                    if (vadResult.getStatus() == VadStatus.ERROR || vadResult.getProcessedData() == null) {
                        return Mono.empty();
                    }

                    // 根据VAD状态处理
                    switch (vadResult.getStatus()) {
                        case SPEECH_START:
                            // 检测到语音开始，记录开始时间
                            sessionSttStartTimes.put(sessionId, System.currentTimeMillis());
                            logger.info("语音识别开始 - SessionId: {}", sessionId);
                            
                            // 初始化流式识别
                            return initializeStreamingRecognition(session, sessionId, finalSttConfig, finalTtsConfig,
                                    device,
                                    vadResult.getProcessedData());

                        case SPEECH_CONTINUE:
                            // 语音继续，发送数据到流式识别
                            if (sessionManager.isStreaming(sessionId)) {
                                Sinks.Many<byte[]> audioSink = sessionManager.getAudioSink(sessionId);
                                if (audioSink != null) {
                                    audioSink.tryEmitNext(vadResult.getProcessedData());
                                }
                            }
                            return Mono.empty();

                        case SPEECH_END:
                            // 语音结束，完成流式识别
                            if (sessionManager.isStreaming(sessionId)) {
                                Sinks.Many<byte[]> audioSink = sessionManager.getAudioSink(sessionId);
                                if (audioSink != null) {
                                    audioSink.tryEmitComplete();
                                    sessionManager.setStreamingState(sessionId, false);
                                }
                            }
                            return Mono.empty();

                        default:
                            return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    logger.error("处理音频数据失败: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * 初始化流式语音识别
     */
    private Mono<Void> initializeStreamingRecognition(
            WebSocketSession session,
            String sessionId,
            SysConfig sttConfig,
            SysConfig ttsConfig,
            SysDevice device,
            byte[] initialAudio) {

        // 如果已经在进行流式识别，先清理旧的资源
        sessionManager.closeAudioSink(sessionId);

        // 创建新的音频数据接收器
        Sinks.Many<byte[]> audioSink = sessionManager.createAudioSink(sessionId);
        sessionManager.setStreamingState(sessionId, true);

        // 获取对应的STT服务
        SttService sttService = sttServiceFactory.getSttService(sttConfig);

        if (sttService == null) {
            logger.error("无法获取STT服务 - Provider: {}", sttConfig != null ? sttConfig.getProvider() : "null");
            return Mono.empty();
        }

        // 发送初始音频数据
        if (initialAudio != null && initialAudio.length > 0) {
            audioSink.tryEmitNext(initialAudio);
        }

        // 创建最终变量以在lambda中使用
        final SysConfig finalTtsConfig = ttsConfig;

        // 启动流式识别
        sttService.streamRecognition(audioSink.asFlux())
                .doOnNext(text -> {
                    // 发送中间识别结果
                    if (StringUtils.hasText(text)) {
                        messageService.sendMessage(session, "stt", "interim", text).subscribe();
                    }
                })
                .defaultIfEmpty("")
                .last() // 获取最终结果
                .flatMap(finalText -> {
                    if (!StringUtils.hasText(finalText)) {
                        return Mono.empty();
                    }

                    // 记录语音识别完成时间并计算用时
                    long sttEndTime = System.currentTimeMillis();
                    Long sttStartTime = sessionSttStartTimes.get(sessionId);
                    if (sttStartTime != null) {
                        double sttDuration = (sttEndTime - sttStartTime) / 1000.0;
                        logger.info("语音识别完成 - SessionId: {}, 用时: {}秒, 识别结果: \"{}\"", 
                                sessionId, df.format(sttDuration), finalText);
                    }
                    
                    // 记录模型回复开始时间
                    sessionLlmStartTimes.put(sessionId, System.currentTimeMillis());
                    
                    // 初始化TTS时间记录Map
                    sessionTtsStartTimes.putIfAbsent(sessionId, new ConcurrentHashMap<>());
                    
                    // 初始化完整回复内容
                    sessionFullResponses.put(sessionId, new StringBuilder());

                    // 设置会话为非监听状态，防止处理自己的声音
                    sessionManager.setListeningState(sessionId, false);

                    // 确保为此会话创建TTS限制器
                    sessionTtsLimiters.putIfAbsent(sessionId, ttsRequestLimit); // 限制为1个并发，确保顺序处理
                    sessionSentenceCounters.putIfAbsent(sessionId, new AtomicInteger(0));

                    // 发送最终识别结果
                    return messageService.sendMessage(session, "stt", "final", finalText)
                            .then(Mono.fromRunnable(() -> {
                                // 使用句子切分处理流式响应
                                llmManager.chatStreamBySentence(device, finalText,
                                        (sentence, isStart, isEnd) -> {
                                            // 获取句子序列号
                                            int sentenceNumber = sessionSentenceCounters.get(sessionId).incrementAndGet();
                                            
                                            // 累加完整回复内容
                                            sessionFullResponses.get(sessionId).append(sentence);
                                            
                                            // 如果是第一个句子，记录模型回复完成时间
                                            if (isStart) {
                                                Long llmStartTime = sessionLlmStartTimes.get(sessionId);
                                                if (llmStartTime != null) {
                                                    double llmDuration = (System.currentTimeMillis() - llmStartTime) / 1000.0;
                                                    logger.info("模型回复完成 - SessionId: {}, 用时: {}秒, 首句内容: \"{}\"", 
                                                            sessionId, df.format(llmDuration), sentence);
                                                }
                                            }
                                            
                                            // 记录TTS开始时间
                                            sessionTtsStartTimes.get(sessionId).put(sentenceNumber, System.currentTimeMillis());
                                            
                                            // 使用TTS限制器控制并发
                                            processSentenceWithRateLimit(
                                                session, 
                                                sessionId, 
                                                sentence, 
                                                isStart, 
                                                isEnd, 
                                                finalTtsConfig, 
                                                device.getVoiceName(), 
                                                sentenceNumber
                                            );
                                        });
                            }));
                })
                .onErrorResume(error -> {
                    logger.error("流式识别错误: {}", error.getMessage(), error);
                    return Mono.empty();
                })
                .subscribe();

        return Mono.empty();
    }
    
    /**
     * 使用限流器处理句子的TTS转换
     */
    private void processSentenceWithRateLimit(
            WebSocketSession session, 
            String sessionId, 
            String sentence, 
            boolean isStart, 
            boolean isEnd, 
            SysConfig ttsConfig, 
            String voiceName,
            int sentenceNumber) {
        
        Semaphore limiter = sessionTtsLimiters.get(sessionId);
        
        // 尝试获取许可
        Mono.fromCallable(() -> {
            // 阻塞获取许可，确保按顺序处理
            limiter.acquire();
            return true;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(acquired -> 
            // 使用非流式TTS处理
            Mono.fromCallable(() -> 
                ttsService.getTtsService(ttsConfig, voiceName).textToSpeech(sentence)
            )
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(audioPath -> {
                // 记录TTS完成时间并计算用时
                Long ttsStartTime = sessionTtsStartTimes.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(sentenceNumber);
                if (ttsStartTime != null) {
                    double ttsDuration = (System.currentTimeMillis() - ttsStartTime) / 1000.0;
                    logger.info("语音生成完成 - SessionId: {}, 句子序号: {}, 用时: {}秒, 内容: \"{}\"", 
                            sessionId, sentenceNumber, df.format(ttsDuration), sentence);
                }
                
                // 如果是最后一个句子，记录完整回复
                if (isEnd) {
                    StringBuilder fullResponse = sessionFullResponses.get(sessionId);
                    if (fullResponse != null) {
                        logger.info("对话完成 - SessionId: {}, 完整回复: \"{}\"", sessionId, fullResponse.toString());
                    }
                }
                
                return audioService.sendAudioMessage(session, audioPath, sentence, isStart, isEnd)
                    .doOnError(e -> logger.error("发送音频消息失败: {}", e.getMessage(), e));
            })
        )
        .doFinally(signalType -> {
            // 释放许可
            limiter.release();
        })
        .onErrorResume(e -> {
            logger.error("处理句子 #{} 失败: {}", sentenceNumber, e.getMessage(), e);
            // 确保释放许可
            limiter.release();
            return Mono.empty();
        })
        .subscribe();
    }

    /**
     * 处理语音唤醒
     * 
     * @param session WebSocket会话
     * @param text    唤醒词文本
     * @return 处理结果
     */
    public Mono<Void> handleWakeWord(WebSocketSession session, String text) {
        String sessionId = session.getId();
        SysDevice device = sessionManager.getDeviceConfig(sessionId);

        if (device == null) {
            return Mono.empty();
        }

        // 获取配置并创建final变量以在lambda中使用
        final SysConfig ttsConfig = device.getTtsId() != null ? sessionManager.getCachedConfig(device.getTtsId())
                : null;

        logger.info("检测到唤醒词: \"{}\"", text);
        
        // 记录模型回复开始时间
        sessionLlmStartTimes.put(sessionId, System.currentTimeMillis());
        
        // 初始化TTS时间记录Map
        sessionTtsStartTimes.putIfAbsent(sessionId, new ConcurrentHashMap<>());
        
        // 初始化完整回复内容
        sessionFullResponses.put(sessionId, new StringBuilder());

        // 设置为非监听状态，防止处理自己的声音
        sessionManager.setListeningState(sessionId, false);

        // 确保为此会话创建TTS限制器
        sessionTtsLimiters.putIfAbsent(sessionId, ttsRequestLimit); // 限制为1个并发，确保顺序处理
        sessionSentenceCounters.putIfAbsent(sessionId, new AtomicInteger(0));

        // 发送识别结果
        messageService.sendMessage(session, "stt", "start", text).subscribe();

        // 使用句子切分处理流式响应
        return Mono.fromRunnable(() -> {
            // 使用句子切分处理流式响应
            llmManager.chatStreamBySentence(device, text,
                    (sentence, isStart, isEnd) -> {
                        // 获取句子序列号
                        int sentenceNumber = sessionSentenceCounters.get(sessionId).incrementAndGet();
                        
                        // 累加完整回复内容
                        sessionFullResponses.get(sessionId).append(sentence);
                        
                        // 如果是第一个句子，记录模型回复完成时间
                        if (isStart) {
                            Long llmStartTime = sessionLlmStartTimes.get(sessionId);
                            if (llmStartTime != null) {
                                double llmDuration = (System.currentTimeMillis() - llmStartTime) / 1000.0;
                                logger.info("模型回复完成 - SessionId: {}, 用时: {}秒, 首句内容: \"{}\"", 
                                        sessionId, df.format(llmDuration), sentence);
                            }
                        }
                        
                        // 记录TTS开始时间
                        sessionTtsStartTimes.get(sessionId).put(sentenceNumber, System.currentTimeMillis());
                        
                        // 使用TTS限制器控制并发
                        processSentenceWithRateLimit(
                            session, 
                            sessionId, 
                            sentence, 
                            isStart, 
                            isEnd, 
                            ttsConfig, 
                            device.getVoiceName(),
                            sentenceNumber
                        );
                    });
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 中止当前对话
     * 
     * @param session WebSocket会话
     * @param reason  中止原因
     * @return 处理结果
     */
    public Mono<Void> abortDialogue(WebSocketSession session, String reason) {
        String sessionId = session.getId();
        logger.info("中止对话 - SessionId: {}, Reason: {}", sessionId, reason);

        // 关闭音频流
        sessionManager.closeAudioSink(sessionId);
        sessionManager.setStreamingState(sessionId, false);

        // 终止语音发送
        return audioService.sendStop(session);
    }
    
    /**
     * 清理会话资源
     * 
     * @param sessionId 会话ID
     */
    public void cleanupSession(String sessionId) {
        // 移除会话的TTS限制器和计数器
        sessionTtsLimiters.remove(sessionId);
        sessionSentenceCounters.remove(sessionId);
        
        // 移除时间记录
        sessionSttStartTimes.remove(sessionId);
        sessionLlmStartTimes.remove(sessionId);
        sessionTtsStartTimes.remove(sessionId);
        sessionFullResponses.remove(sessionId);
    }
}