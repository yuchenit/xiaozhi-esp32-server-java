package com.xiaozhi.websocket.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.xiaozhi.websocket.audio.processor.OpusProcessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.SESSION_ID;

@Service
public class AudioService {

    private static final Logger logger = LoggerFactory.getLogger(AudioService.class);

    // 播放帧持续时间（毫秒）
    private static final int FRAME_DURATION_MS = 60;

    // 默认音频采样率和通道数
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_CHANNELS = 1;

    // 为每个会话维护一个音频发送队列
    private final ConcurrentHashMap<String, BlockingQueue<ProcessedAudioTask>>
            audioQueues = new ConcurrentHashMap<>();

    // 跟踪每个会话的发送状态
    private final ConcurrentHashMap<String, AtomicBoolean> isProcessingMap = new ConcurrentHashMap<>();

    @Resource
    private MessageService messageService;

    @Autowired
    private OpusProcessor opusProcessor;

    @Autowired
    @Qualifier("baseThreadPool")
    private ExecutorService baseThreadPool;

    @Autowired
    @Qualifier("audioCleanupExecutor")
    private ExecutorService cleanupExecutor;

    /**
     * 已处理的音频任务类，包含已转码的音频数据
     */
    private static class ProcessedAudioTask {
        private final List<byte[]> opusFrames;
        private final String text;
        private final boolean isFirstText;
        private final boolean isLastText;
        private final String originalFilePath; // 保留原始文件路径，用于后续删除

        public ProcessedAudioTask(List<byte[]> opusFrames, String text, boolean isFirstText, boolean isLastText,
                String originalFilePath) {
            this.opusFrames = opusFrames;
            this.text = text;
            this.isFirstText = isFirstText;
            this.isLastText = isLastText;
            this.originalFilePath = originalFilePath;
        }

        public List<byte[]> getOpusFrames() {
            return opusFrames;
        }

        public String getText() {
            return text;
        }

        public boolean isFirstText() {
            return isFirstText;
        }

        public boolean isLastText() {
            return isLastText;
        }

        public String getOriginalFilePath() {
            return originalFilePath;
        }
    }

    /**
     * 音频处理结果类
     */
    public static class AudioProcessResult {
        private final List<byte[]> opusFrames;
        private final long durationMs;

        public AudioProcessResult(List<byte[]> opusFrames, long durationMs) {
            this.opusFrames = opusFrames;
            this.durationMs = durationMs;
        }

        public List<byte[]> getOpusFrames() {
            return opusFrames;
        }

        public long getDurationMs() {
            return durationMs;
        }
    }

    /**
     * 初始化音频队列
     */
    public void initializeSession(String sessionId) {
        // 初始化音频队列,不存在时,新建语音队列,已存在时不变
//        logger.info("初始化音频队列和处理状态, sessionId = {}", sessionId);
        audioQueues.putIfAbsent(sessionId, new LinkedBlockingQueue<>());
        isProcessingMap.putIfAbsent(sessionId, new AtomicBoolean(false));
    }

    /**
     * 清理会话的音频处理状态
     */
    public void cleanupSession(String sessionId) {
        // 清理音频队列
        logger.info("清理会话的音频处理状态,sessionId={}",sessionId);
        // 增加防御性检查
        if (sessionId == null) return;
        // 改为原子性操作
        BlockingQueue<ProcessedAudioTask> queue = audioQueues.remove(sessionId);
        isProcessingMap.remove(sessionId);

        // 异步清理文件
        if (queue != null) {
            cleanupExecutor.execute(() ->
                    queue.forEach(task ->
                            cleanupTaskResources(task.getOriginalFilePath())
                    )
            );
        }
    }

    /**
     * 处理音频文件，提取PCM数据并转换为Opus格式
     * 
     * @param audioFilePath 音频文件路径
     * @param sampleRate    采样率
     * @param channels      通道数
     * @return 处理结果，包含opus数据和持续时间
     */
    public AudioProcessResult processAudioFile(String audioFilePath, int sampleRate, int channels) throws Exception {
//        logger.info("预处理音频文件，提取PCM数据并转换为Opus格式");
        // 从MP3获取PCM数据
        byte[] pcmData = extractPcmFromAudio(audioFilePath,"s16le",sampleRate);
        long durationMs = calculateDuration(pcmData, sampleRate, channels);

        // 转换为Opus格式
        List<byte[]> opusFrames = opusProcessor.convertPcmToOpus(pcmData,
                sampleRate, channels, FRAME_DURATION_MS);

        return new AudioProcessResult(opusFrames, durationMs);
    }

    /**
     * 从音频文件提取PCM数据
     */
    private byte[] extractPcmFromAudio(String audioFilePath,String outputFormat, int sampleRate) throws Exception {
        // 创建临时PCM文件
        File tempPcmFile = null;
        try {
            // 创建临时PCM文件
            tempPcmFile = File.createTempFile("temp_pcm_extract_", "." + outputFormat);
            String tempPcmPath = tempPcmFile.getAbsolutePath();
            // 使用FFmpeg直接将MP3转换为PCM
            String[] command = {
                    "ffmpeg",
                    "-i", audioFilePath,
                    "-f", outputFormat, // 16位有符号小端格式
                    "-ar", String.valueOf(sampleRate), // 采样率
                    "-acodec", "pcm_"+outputFormat,
                    "-y",
                    tempPcmPath
            };
            // 执行FFmpeg命令
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("FFmpeg提取PCM数据失败，退出码: {}", exitCode);
                throw new RuntimeException("PCM数据提取失败");
            }

            // 读取PCM文件内容
            return FileUtil.readBytes(tempPcmFile);
        }catch (Exception e){
            logger.error("提取PCM数据时发生错误: {}", e.getMessage(), e);
            throw e;
        }finally {
            // 删除临时文件
            if (tempPcmFile != null) {
                Files.deleteIfExists(Paths.get(tempPcmFile.getAbsolutePath()));
            }
        }
    }

    /**
     * 计算音频持续时间（毫秒）
     */
    private long calculateDuration(byte[] pcmData, int sampleRate, int channels) {
        // PCM 16位数据，每个样本2字节
        int bytesPerSample = 2;
        int totalSamples = pcmData.length / (bytesPerSample * channels);
        return (long) (totalSamples * 1000.0 / sampleRate);
    }

    /**
     * 简化版本的发送音频方法
     */
    public void sendAudio(Channel channel, String audioFilePath, String text) {
        sendAudio(channel, audioFilePath, text, true, true);
    }

    /**
     * 将音频添加到发送队列，并开始处理队列（如果尚未开始）
     * 此方法会先异步预处理音频，然后再添加到队列
     * 
     * @param channel       Netty Channel
     * @param audioFilePath 音频文件路径
     * @param text          文本内容
     * @param isFirstText   是否是第一段文本
     * @param isLastText    是否是最后一段文本
     */
    public void sendAudio(Channel channel, String audioFilePath, String text, boolean isFirstText,
            boolean isLastText) {
        if (channel == null || !channel.isActive()) {
            logger.warn("通道不可用,终止发送,清理当前任务资源");
            cleanupTaskResources(audioFilePath);
            return;
        }
        String sessionId = channel.attr(SESSION_ID).get();
        // 确保会话已初始化
        initializeSession(sessionId);
        // 异步预处理音频
        baseThreadPool.submit(() -> {
            try {
                // 预处理音频文件
                AudioProcessResult result = processAudioFile(audioFilePath,
                        DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);

                // 创建已处理的音频任务
                ProcessedAudioTask task = new ProcessedAudioTask(
                        result.getOpusFrames(),
                        text,
                        isFirstText,
                        isLastText,
                        audioFilePath);

                // 添加到发送队列
                audioQueues.get(sessionId).add(task);
                if (isFirstText) {
                    sendStart(channel);
                }
                // 如果当前没有处理任务，开始处理,确保从第一个句子开始
                if (isProcessingMap.get(sessionId).compareAndSet(false, true)) {
                    processNextAudio(channel);
                }
            } catch (Exception e) {
                logger.error("音频预处理失败,终止发送,清理当前任务资源: {}", e.getMessage(), e);
                // 处理失败时，尝试清理会话
                cleanupSession(sessionId);
            }
        });
    }

    /**
     * 处理队列中的下一个音频任务（优化版）
     *
     * 优化点：
     * 1. 使用高精度定时器替代Thread.sleep
     * 2. 增加背压控制机制
     * 3. 优化日志级别和内容
     * 4. 完善资源清理
     * 5. 添加流量控制
     */
    private void processNextAudio(Channel channel) {
        final String sessionId = channel.attr(SESSION_ID).get();
        // 通道状态检查
        if (!channel.isActive()) {
            logger.warn("通道不可用,终止发送,清理当前任务资源 - ChannelActive: {}", channel.isActive());
            cleanupSession(sessionId);
            return;
        }
        final BlockingQueue<ProcessedAudioTask> queue = audioQueues.get(sessionId);
        if (queue == null) {
            logger.error("音频队列未初始化 - SessionId: {}", sessionId);
            return;
        }
        // 非阻塞获取任务
        ProcessedAudioTask task = queue.poll();
        if (task == null) {
            // 队列为空，设置处理状态为false
            isProcessingMap.get(sessionId).set(false);
            return;
        }

        baseThreadPool.execute(() -> {
            try {
//                logger.debug("开始发送文本消息 - SessionId: {}, Text: {}", sessionId, task.getText());
                sendSentenceStart(channel, task.getText());

                // 高精度帧发送控制
                sendAudioFramesWithPrecision(channel, task);

                // 处理结束逻辑
                if (task.isLastText() && channel.isActive()) {
                    logger.debug("发送会话结束信号 - SessionId: {}", sessionId);
                    sendStop(channel);
                    return;
                }

                processNextAudio(channel);

            } catch (Exception e) {
                logger.error("音频任务处理异常 - SessionId: {}, Error: {}", sessionId, e.getMessage(), e);
                processNextAudio(channel);
            } finally {
                // 清理当前队列资源
                cleanupTaskResources(task.getOriginalFilePath());
            }
        });
    }

    /**
     * 高精度音频帧发送（核心优化）
     */
    private void sendAudioFramesWithPrecision(Channel channel, ProcessedAudioTask task) {
        final long frameIntervalNs = TimeUnit.MILLISECONDS.toNanos(FRAME_DURATION_MS);
        final long startTime = System.nanoTime();

        List<byte[]> opusFrames = task.getOpusFrames();

        for (int i = 0; i < opusFrames.size(); i++) {
            // 通道状态检查
            if (!channel.isActive() || !channel.isWritable()) {
                logger.warn("通道不可用，中断发送 - ChannelActive: {}, Writable: {}",
                        channel.isActive(), channel.isWritable());
                break;
            }

            // 高精度定时控制
            long expectedTime = startTime + (i * frameIntervalNs);
            long currentTime = System.nanoTime();
            long delayNs = expectedTime - currentTime;

            if (delayNs > 0) {
                LockSupport.parkNanos(delayNs); // 比Thread.sleep更精确
            } else if (delayNs < -frameIntervalNs * 2) {
                logger.warn("严重延迟，跳过帧补偿 - DelayMs: {}",
                        TimeUnit.NANOSECONDS.toMillis(-delayNs));
                continue; // 超过两帧延迟则跳过
            }

            // 发送帧数据
            ByteBuf frameBuf = Unpooled.wrappedBuffer(opusFrames.get(i));
            BinaryWebSocketFrame webSocketFrame = new BinaryWebSocketFrame(frameBuf);
            channel.writeAndFlush(webSocketFrame);

            // 每10帧检查一次背压
            /*if (i % 10 == 0 && channel.bytesBeforeUnwritable() < frameBuf.readableBytes() * 5L) {
                logger.debug("背压控制 - 暂停发送等待缓冲区");
                channel.flush();
                while (!channel.isWritable()) {
                    LockSupport.parkNanos(frameIntervalNs);
                }
            }*/
        }
    }

    /**
     * 删除音频文件及其相关文件（如同名的VTT文件）
     * @param audioPath 音频文件路径
     */
    private void cleanupTaskResources(String audioPath) {
        if (StrUtil.isEmpty(audioPath)) {
            return;
        }
        logger.info("删除音频文件及其相关文件,audioPath = {}",audioPath);
        // 删除原始音频文件
        try {
            Files.deleteIfExists(Paths.get(audioPath));
        } catch (IOException e) {
            logger.warn("音频文件删除失败 - Path: {}, Error: {}",audioPath, e.getMessage());
        }

        try {
            Files.deleteIfExists(Paths.get(audioPath + ".vtt"));
        } catch (IOException e) {
            logger.warn("VTT文件删除失败 - Path: {}, Error: {}",audioPath + ".vtt", e.getMessage());
        }
    }

    // 发送TTS句子开始指令（包含文本）
    public void sendSentenceStart(Channel channel, String text) {
        messageService.sendMessage(channel, "tts", "sentence_start", text);
    }

    // 发送TTS句子结束指令
    public void sendSentenceEnd(Channel channel, String text) {
        messageService.sendMessage(channel, "tts", "sentence_end", text);
    }

    // 发送TTS开始指令
    public void sendStart(Channel channel) {
        messageService.sendMessage(channel, "tts", "start");
    }

    /**
     * 发送TTS停止指令，同时清空音频队列并中断当前发送
     */
    public void sendStop(Channel channel) {
        // 发送停止指令
        messageService.sendMessage(channel, "tts", "stop");

        // 清空音频队列
        if (channel == null) {
            logger.warn("尝试发送停止指令到空的Channel");
            return;
        }
        String sessionId = channel.attr(SESSION_ID).get();

        BlockingQueue<ProcessedAudioTask> queue = audioQueues.get(sessionId);
        if (queue != null) {
            // 异步删除文件
            cleanupExecutor.submit(() -> {
                queue.forEach(task -> {
                    if (ObjectUtil.isNotNull(task) && StrUtil.isNotEmpty(task.getOriginalFilePath())) {
                        cleanupTaskResources(task.getOriginalFilePath());
                    }
                });
            });

            // 清空队列
            queue.clear();
        }

        audioQueues.remove(sessionId);
        isProcessingMap.remove(sessionId);
    }
}