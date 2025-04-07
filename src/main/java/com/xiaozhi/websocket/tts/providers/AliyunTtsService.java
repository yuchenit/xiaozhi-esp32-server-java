package com.xiaozhi.websocket.tts.providers;

import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.websocket.tts.TtsService;
import com.xiaozhi.websocket.token.TokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public class AliyunTtsService implements TtsService {
    private static final Logger logger = LoggerFactory.getLogger(AliyunTtsService.class);

    private static final String PROVIDER_NAME = "aliyun";

    // 阿里云NLS服务的默认URL
    private static final String NLS_URL = "wss://nls-gateway.aliyuncs.com/ws/v1";

    // 阿里云配置
    private final SysConfig config;
    private final String voiceName;
    private final String outputPath;
    // 全局共享的NLS客户端
    private NlsClient client;

    // Token管理器
    @Autowired
    private TokenManager tokenManager;

    public AliyunTtsService(SysConfig config,
                            String voiceName, String outputPath) {
        this.config = config;
        this.voiceName = voiceName;
        this.outputPath = outputPath;
    }

    // 设置TokenManager的方法，由工厂类调用
    public void setTokenManager(TokenManager tokenManager) {
        this.tokenManager = tokenManager;
        // 初始化NLS客户端（如果尚未初始化）
        initClient();
    }

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
    public String getAudioFileName() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid + ".mp3";
    }

    @Override
    public String textToSpeech(String text) throws Exception {
        // 保留原有的一句话转语音功能
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // 创建语音合成请求
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(client, new SpeechSynthesizerListener() {
                @Override
                public void onComplete(SpeechSynthesizerResponse response) {
                    latch.countDown();
                }

                @Override
                public void onFail(SpeechSynthesizerResponse response) {
                    latch.countDown();
                }

                @Override
                public void onMessage(ByteBuffer message) {
                    byte[] buffer = new byte[message.remaining()];
                    message.get(buffer);
                    try {
                        outputStream.write(buffer);
                    } catch (IOException e) {
                        logger.error("写入音频数据失败", e);
                    }
                }
            });

            // 设置appKey
            synthesizer.setAppKey(config.getApiKey());
            // 设置语音输出格式
            synthesizer.setFormat(OutputFormatEnum.MP3);
            // 设置采样率
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            // 设置语音
            synthesizer.setVoice(voiceName);
            // 设置音量
            synthesizer.setVolume(50);
            // 设置语速
            synthesizer.setSpeechRate(0);
            // 设置语调
            synthesizer.setPitchRate(0);

            // 发送文本
            synthesizer.setText(text);
            // 开始语音合成
            synthesizer.start();

            // 等待语音合成完成
            latch.await();

            // 保存音频文件
            String audioFileName = getAudioFileName();
            String filePath = outputPath + audioFileName;

            // 确保输出目录存在
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            try (FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
                fileOutputStream.write(outputStream.toByteArray());
            }

            return filePath;
        } catch (Exception e) {
            logger.error("阿里云语音合成失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void streamTextToSpeech(String text, Consumer<byte[]> audioDataConsumer) throws Exception {
        logger.info("开始阿里云流式语音合成 - 文本长度: {}", text.length());

        // 创建合成完成信号
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // 创建语音合成请求
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(client, new SpeechSynthesizerListener() {
                private boolean firstPacket = true;

                @Override
                public void onComplete(SpeechSynthesizerResponse response) {
                    latch.countDown();
                }

                @Override
                public void onFail(SpeechSynthesizerResponse response) {
                    latch.countDown();
                }

                @Override
                public void onMessage(ByteBuffer message) {
                    if (firstPacket) {
                        firstPacket = false;
                    }

                    // 从ByteBuffer中获取字节数组
                    byte[] buffer = new byte[message.remaining()];
                    message.get(buffer);

                    // 将音频数据传递给消费者函数
                    audioDataConsumer.accept(buffer);
                }
            });

            // 设置appKey
            synthesizer.setAppKey(config.getApiKey());
            // 设置PCM格式输出，便于直接处理
            synthesizer.setFormat(OutputFormatEnum.PCM);
            // 设置采样率
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            // 设置语音
            synthesizer.setVoice(voiceName);
            // 设置音量
            synthesizer.setVolume(100);
            // 设置语速
            synthesizer.setSpeechRate(0);
            // 设置语调
            synthesizer.setPitchRate(0);

            // 发送文本
            synthesizer.setText(text);

            // 开始语音合成
            long startTime = System.currentTimeMillis();
            synthesizer.start();
            logger.info("语音合成请求已发送，耗时: {} ms", System.currentTimeMillis() - startTime);

            // 我们需要等待合成完成后再关闭synthesizer
            latch.await();

            // 合成完成后关闭synthesizer
            synthesizer.close();

        } catch (Exception e) {
            logger.error("阿里云流式语音合成失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public CompletableFuture<Void> streamTextToSpeechAsync(String text, Consumer<byte[]> audioDataConsumer) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        // 在单独的线程中执行，避免阻塞
        CompletableFuture.runAsync(() -> {
            logger.info("开始阿里云流式语音合成 - 文本长度: {}", text.length());

            SpeechSynthesizer synthesizer = null;
            try {
                // 确保有有效的客户端
                if (client == null || tokenManager.getValidToken(config) == null) {
                    initClient();
                    if (client == null) {
                        throw new RuntimeException("无法初始化阿里云NLS客户端");
                    }
                }

                // 创建语音合成请求
                synthesizer = new SpeechSynthesizer(client, new SpeechSynthesizerListener() {
                    private boolean firstPacket = true;
                    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    private static final int BUFFER_SIZE = 4096; // 4KB 缓冲区

                    @Override
                    public void onComplete(SpeechSynthesizerResponse response) {

                        // 发送最后剩余的数据
                        if (buffer.size() > 0) {
                            audioDataConsumer.accept(buffer.toByteArray());
                        }

                        // 合成完成，完成Future
                        future.complete(null);
                    }

                    @Override
                    public void onFail(SpeechSynthesizerResponse response) {
                        logger.error("流式语音合成失败 - TaskId: {}, Status: {}, StatusText: {}",
                                response.getTaskId(), response.getStatus(), response.getStatusText());
                        // 合成失败，异常完成Future
                        future.completeExceptionally(new RuntimeException(
                                "阿里云TTS失败: " + response.getStatusText()));
                    }

                    @Override
                    public void onMessage(ByteBuffer message) {
                        if (firstPacket) {
                            logger.info("收到首个音频数据包，延迟: {} ms", System.currentTimeMillis());
                            firstPacket = false;
                        }

                        // 从ByteBuffer中获取字节数组
                        byte[] data = new byte[message.remaining()];
                        message.get(data);

                        try {
                            // 将数据添加到缓冲区
                            buffer.write(data);

                            // 当缓冲区达到一定大小时，发送数据并清空缓冲区
                            if (buffer.size() >= BUFFER_SIZE) {
                                audioDataConsumer.accept(buffer.toByteArray());
                                buffer.reset();
                            }
                        } catch (IOException e) {
                            logger.error("处理音频数据时发生错误", e);
                        }
                    }
                });

                // 设置appKey
                synthesizer.setAppKey(config.getApiKey());
                // 设置PCM格式输出，便于直接处理
                synthesizer.setFormat(OutputFormatEnum.PCM);
                // 设置采样率
                synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
                // 设置语音
                synthesizer.setVoice(voiceName);
                // 设置音量
                synthesizer.setVolume(100);
                // 设置语速
                synthesizer.setSpeechRate(0);
                // 设置语调
                synthesizer.setPitchRate(0);

                // 发送文本
                synthesizer.setText(text);

                // 开始语音合成
                long startTime = System.currentTimeMillis();
                synthesizer.start();
                logger.info("语音合成请求已发送，耗时: {} ms", System.currentTimeMillis() - startTime);

            } catch (Exception e) {
                logger.error("阿里云流式语音合成失败: {}", e.getMessage(), e);
                future.completeExceptionally(e);

                // 关闭synthesizer
                if (synthesizer != null) {
                    try {
                        synthesizer.close();
                    } catch (Exception closeEx) {
                        logger.error("关闭synthesizer失败", closeEx);
                    }
                }
            }
        });

        return future;
    }

    // 关闭资源
    public void shutdown() {
        if (client != null) {
            client.shutdown();
            client = null;
            logger.info("NLS客户端已关闭");
        }
    }
}