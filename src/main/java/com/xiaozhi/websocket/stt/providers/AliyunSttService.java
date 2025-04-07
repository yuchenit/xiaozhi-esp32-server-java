package com.xiaozhi.websocket.stt.providers;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.token.TokenManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ObjectUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class AliyunSttService implements SttService {
    private static final Logger logger = LoggerFactory.getLogger(AliyunSttService.class);
    private static final String PROVIDER_NAME = "aliyun";

    // 阿里云NLS服务的默认URL
    private static final String NLS_URL = "wss://nls-gateway.aliyuncs.com/ws/v1";

    // 阿里云配置
    private final SysConfig config;

    // 全局共享的NLS客户端
    private NlsClient client;

    // Token管理器
    @Autowired
    private TokenManager tokenManager;

    // 存储当前活跃的识别会话
    private final ConcurrentHashMap<String, SpeechTranscriber> activeTranscribers = new ConcurrentHashMap<>();

    public AliyunSttService(SysConfig config) {
        this.config = config;
    }

    // 设置TokenManager的方法，由工厂类调用
    public void setTokenManager(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
        // 初始化NLS客户端（如果尚未初始化）
        initClient();
    }

    /**
     * 初始化NLS客户端
     */
    private void initClient() {
        try {
            // 获取有效Token
            String accessToken = tokenManager.getValidToken(config);
            if (accessToken != null) {
                // 创建NLS客户端实例
                client = new NlsClient(NLS_URL, accessToken);
            }
        } catch (Exception e) {
            logger.error("初始化阿里云NLS客户端失败", e);
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String recognition(byte[] audioData) {
        // 单次识别暂未实现，可以根据需要添加
        logger.warn("阿里云单次识别未实现，请使用流式识别");
        return null;
    }

    @Override
    public Flux<String> streamRecognition(Flux<byte[]> audioStream) {
        // 检查配置是否已设置
        if (ObjectUtils.isEmpty(config)) {
            logger.error("阿里云语音识别配置未设置，无法进行识别");
            return Flux.error(new IllegalStateException("阿里云语音识别配置未设置"));
        }

        // 检查客户端是否已初始化
        if (client == null) {
            // 尝试重新初始化客户端
            initClient();
            if (client == null) {
                logger.error("阿里云NLS客户端初始化失败，无法进行识别");
                return Flux.error(new IllegalStateException("阿里云NLS客户端初始化失败"));
            }
        }

        // 创建结果接收器
        Sinks.Many<String> resultSink = Sinks.many().multicast().onBackpressureBuffer();

        // 生成唯一的任务ID
        String taskId = UUID.randomUUID().toString();

        try {
            // 创建转写器并设置监听器
            SpeechTranscriber transcriber = new SpeechTranscriber(client, createStreamingListener(taskId, resultSink));

            // 设置参数
            transcriber.setAppKey(config.getApiKey());
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            transcriber.setEnablePunctuation(true); // 启用标点
            transcriber.setEnableITN(true); // 启用反文本规范化

            // 关键：启用中间结果
            transcriber.setEnableIntermediateResult(true);

            // 存储到活跃转写器映射中
            activeTranscribers.put(taskId, transcriber);

            // 启动识别
            transcriber.start();

            // 标记是否已经发送了停止信号
            AtomicBoolean stopSent = new AtomicBoolean(false);

            // 订阅音频流并发送数据
            audioStream.subscribe(
                    data -> {
                        try {
                            if (activeTranscribers.containsKey(taskId)) {
                                transcriber.send(data);
                            }
                        } catch (Exception e) {
                            logger.error("发送音频数据时发生错误 - TaskId: {}", taskId, e);
                            resultSink.tryEmitError(e);
                        }
                    },
                    error -> {
                        logger.error("音频流错误 - TaskId: {}", taskId, error);
                        resultSink.tryEmitError(error);
                        if (activeTranscribers.containsKey(taskId)) {
                            try {
                                transcriber.stop();
                                transcriber.close();
                            } catch (Exception e) {
                                logger.error("停止转写器时发生错误 - TaskId: {}", taskId, e);
                            } finally {
                                activeTranscribers.remove(taskId);
                            }
                        }
                    },
                    () -> {
                        if (activeTranscribers.containsKey(taskId) && !stopSent.getAndSet(true)) {
                            try {
                                logger.info("音频流结束，停止识别 - TaskId: {}", taskId);
                                // 停止识别
                                transcriber.stop();
                            } catch (Exception e) {
                                logger.error("停止转写器时发生错误 - TaskId: {}", taskId, e);
                                resultSink.tryEmitError(e);
                            }
                        }
                    });

        } catch (Exception e) {
            logger.error("创建语音识别会话时发生错误", e);
            return Flux.error(e);
        }

        return resultSink.asFlux();
    }

    /**
     * 为流式识别创建监听器
     */
    private SpeechTranscriberListener createStreamingListener(String taskId, Sinks.Many<String> resultSink) {
        return new SpeechTranscriberListener() {
            private final StringBuilder partialResult = new StringBuilder();

            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                // 中间结果
                String text = response.getTransSentenceText();

                if (text != null && !text.isEmpty()) {
                    // 发送中间结果
                    resultSink.tryEmitNext(text);
                    partialResult.setLength(0);
                    partialResult.append(text);
                }
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                // 一句话结束
                String text = response.getTransSentenceText();

                if (text != null && !text.isEmpty()) {
                    // 如果结果与上一个中间结果不同，则发送结果
                    if (!text.equals(partialResult.toString())) {
                        resultSink.tryEmitNext(text);
                        partialResult.setLength(0);
                        partialResult.append(text);
                    }
                }
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {

                // 从活跃转写器中移除
                activeTranscribers.remove(taskId);
                resultSink.tryEmitComplete();
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                // 从活跃转写器中移除
                activeTranscribers.remove(taskId);
            }
        };
    }

    // 在服务关闭时释放资源
    public void shutdown() {
        // 关闭所有活跃的转写器
        activeTranscribers.forEach((id, transcriber) -> {
            try {
                transcriber.stop();
                transcriber.close();
            } catch (Exception e) {
                logger.error("关闭转写器时发生错误 - TaskId: {}", id, e);
            }
        });
        activeTranscribers.clear();

        // 关闭NLS客户端
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }
}