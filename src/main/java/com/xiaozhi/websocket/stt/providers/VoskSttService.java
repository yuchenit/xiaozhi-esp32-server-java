package com.xiaozhi.websocket.stt.providers;

import cn.hutool.extra.spring.SpringUtil;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.websocket.stt.SttService;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class VoskSttService implements SttService {
    
    private static final String PROVIDER_NAME = "vosk";
    private CompletableFuture<Boolean> initializationFuture;
    private volatile Model model;
    private volatile boolean initialized = false;

    @Value("${stt.vosk.model-path:models/vosk-model}")
    private String modelPath;

    @Autowired
    @Qualifier("baseThreadPool")
    private ExecutorService baseThreadPool;

    /**
     * 异步初始化模型
     */
    public CompletableFuture<Boolean> asyncInitialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.warn("Vosk model is loading, please wait...");
                // 禁用Vosk日志输出
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                // 加载模型
                model = new Model(modelPath);
                initialized = true;
                log.info("Vosk model loading ok，path: {}", modelPath);
                return true;
            } catch (IOException e) {
                log.error("Vosk model loading failed: {}", e.getMessage(), e);
                initialized = true;
                return false;
            }
        }, baseThreadPool);
    }

    /**
     * 确保模型加载完成（阻塞直到加载成功或超时）
     */
    public void ensureInitialized(){
        if (!initialized) {
            if (initializationFuture == null) {
                initializationFuture = asyncInitialize();
            }
            // 等待异步任务完成，超时时间为30秒
            boolean result;
            try {
                result = initializationFuture.get(30, TimeUnit.SECONDS);
                if (!result) {
                    log.error("Vosk model loading failed");
                    throw new ServiceNotInitializedException("Vosk model loading failed");
                }
            } catch (TimeoutException e) {
                log.error("Vosk model loading timeout 30s");
                throw new ServiceNotInitializedException("Vosk model loading timeout 30s");
            } catch (ExecutionException e) {
                log.error("Vosk model loading failed");
                throw new ServiceNotInitializedException("Vosk model loading failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Vosk model loading interrupted");
                throw new ServiceNotInitializedException("Vosk model loading interrupted");
            }
        }
    }


    @PostConstruct
    public void init() {
        // 触发异步初始化
        initializationFuture = asyncInitialize();
    }

    @PreDestroy
    public void cleanup() {
        if (model != null) {
            model.close();
            log.debug("Vosk model resources released");
        }
        initialized = false;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String recognition(byte[] audioData) {
        ensureInitialized(); // 使用前确保模型就绪
        Objects.requireNonNull(audioData, "Audio data must not be null");
        
        if (audioData.length == 0) {
            log.warn("Received empty audio data");
            return null;
        }
        baseThreadPool.execute(()->{
            // 将原始音频数据转换为MP3格式并保存
            AudioUtils.saveAsMp3File(audioData);
        });

        try (Recognizer recognizer = new Recognizer(model, 16000)) {
            return processAudio(recognizer, audioData);
        } catch (IOException e) {
            throw new AudioProcessingException("Audio processing failed", e);
        }
    }

    private String processAudio(Recognizer recognizer, byte[] audioData) throws IOException {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(audioData)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = stream.read(buffer)) != -1) {
                if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                    return recognizer.getResult();
                }
            }
            return recognizer.getFinalResult();
        }
    }

    public void validateInitialized() {
        if (!initialized) {
            throw new ServiceNotInitializedException("Vosk service is not initialized");
        }
    }

    // Custom Exceptions
    public static class AudioProcessingException extends RuntimeException {
        public AudioProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ServiceNotInitializedException extends RuntimeException {
        public ServiceNotInitializedException(String message) {
            super(message);
        }
    }
}