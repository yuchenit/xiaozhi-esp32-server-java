package com.xiaozhi.utils;

import cn.hutool.core.io.FileUtil;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;
import org.slf4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

public class AudioUtils {
    public static final String AUDIO_PATH = "audio/";
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(AudioUtils.class);

    // 添加静态初始化块加载本地库
    static {
        Loader.load(org.bytedeco.ffmpeg.global.avutil.class);
    }

    /**
     * 将原始音频数据保存为MP3文件
     *
     * @param audio 音频数据
     * @return 文件名
     *
     */
    public static String saveAsMp3File(byte[] audio) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String fileName = uuid + ".mp3";
        String filePath = AUDIO_PATH + fileName;
        int sampleRate = 16000; // 采样率
        int channels = 1; // 单声道

        try(FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(filePath, channels)) {
            // 创建内存中的帧记录器，直接输出到MP3文件
            recorder.setAudioChannels(channels);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
            recorder.setAudioQuality(0); // 高质量
            recorder.setAudioBitrate(128000); // 128kbps比特率
            recorder.setSampleFormat(avutil.AV_SAMPLE_FMT_S16); // 16位PCM
            recorder.start();

            // 创建音频帧
            Frame audioFrame = new Frame();

            // 将PCM数据转换为短整型数组
            short[] samples = new short[audio.length / 2];
            ByteArrayInputStream bais = new ByteArrayInputStream(audio);
            for (int i = 0; i < samples.length; i++) {
                // 读取两个字节，组合成一个short (小端序)
                int low = bais.read() & 0xff;
                int high = bais.read() & 0xff;
                samples[i] = (short) (low | (high << 8));
            }

            // 创建ShortBuffer并填充数据
            java.nio.ShortBuffer shortBuffer = java.nio.ShortBuffer.wrap(samples);

            // 设置音频帧数据
            audioFrame.samples = new java.nio.Buffer[] { shortBuffer };
            audioFrame.sampleRate = sampleRate;
            audioFrame.audioChannels = channels;

            // 记录音频帧
            recorder.record(audioFrame);

            // 关闭记录器
            recorder.stop();
            recorder.release();
            recorder.close();
            return fileName;
        } catch (FrameRecorder.Exception e) {
            logger.error("编码MP3时发生错误", e);
        }
        return null;
    }

    /**
     * 将原始音频数据保存为WAV文件
     *
     * @param audioData 音频数据
     * @return 文件名
     *
     */
    public static String saveAsWavFile(byte[] audioData) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String fileName = uuid + ".mp3";
        String filePath = AUDIO_PATH + fileName;
        // WAV文件参数
        int sampleRate = 16000; // 与Recognizer使用的采样率保持一致
        int channels = 1; // 单声道
        int bitsPerSample = 16; // 16位采样

        try (FileOutputStream fos = new FileOutputStream(filePath);
                DataOutputStream dos = new DataOutputStream(fos)) {

            // 写入WAV文件头
            // RIFF头
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + audioData.length)); // 文件长度
            dos.writeBytes("WAVE");

            // fmt子块
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16)); // 子块大小
            dos.writeShort(Short.reverseBytes((short) 1)); // 音频格式 (1 = PCM)
            dos.writeShort(Short.reverseBytes((short) channels)); // 通道数
            dos.writeInt(Integer.reverseBytes(sampleRate)); // 采样率
            dos.writeInt(Integer.reverseBytes(sampleRate * channels * bitsPerSample / 8)); // 字节率
            dos.writeShort(Short.reverseBytes((short) (channels * bitsPerSample / 8))); // 块对齐
            dos.writeShort(Short.reverseBytes((short) bitsPerSample)); // 每个样本的位数

            // data子块
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(audioData.length)); // 数据大小

            // 写入音频数据
            dos.write(audioData);
            return fileName;
        } catch (FrameRecorder.Exception e) {
            logger.error("编码MP3时发生错误", e);
        } catch (IOException e) {
            logger.error("写入WAV文件时发生错误", e);
        }
        return null;
    }

    /**
     * 从音频文件提取PCM数据
     * @param audioFilePath 音频文件路径： audio/2d073bd2a0d5471789956469411a8dd3.mp3
     * @param outputFormat   输出格式，默认 "s16le"
     * @param sampleRate    采样率,默认 16000
     * @param channels      通道数，默认 1
     */
    public static byte[] extractPcmFromAudioCommand(String audioFilePath, String outputFormat, int sampleRate, int channels) throws Exception {
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
     * 从音频文件中提取PCM数据（自动资源管理版）
     * 使用FFmpegFrameGrabber代替ffmpeg命令行工具
     *
     * @param audioFilePath 音频文件路径： audio/2d073bd2a0d5471789956469411a8dd3.mp3
     * @param outputFormat   输出格式，默认 "s16le"
     * @param sampleRate    采样率,默认 16000
     * @param channels      通道数，默认 1
     */
    public static byte[] extractPcmFromAudioFfmpeg(String audioFilePath, String outputFormat, int sampleRate, int channels) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioFilePath);
             ByteArrayOutputStream byteStream = new ByteArrayOutputStream()){

            // 基本配置
            grabber.setAudioChannels(channels);
            grabber.setSampleRate(sampleRate);
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            // 16位有符号小端格式
            grabber.setAudioOption("acodec", "pcm_" + outputFormat);
            // 启动grabber
            grabber.start();

            ByteBuffer byteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            Frame audioFrame;

            while ((audioFrame = grabber.grabFrame(true, false, true, false)) != null) {
                if (audioFrame.samples == null) continue;

                for (Object buffer : audioFrame.samples) {
                    ShortBuffer shortBuffer = (ShortBuffer) buffer;
                    shortBuffer.rewind();

                    // 直接写入ByteBuffer，减少内存分配
                    byteBuffer.clear();
                    while (shortBuffer.hasRemaining()) {
                        if (!byteBuffer.hasRemaining()) {
                            byteStream.write(byteBuffer.array(), 0, byteBuffer.position());
                            byteBuffer.clear();
                        }
                        byteBuffer.putShort(shortBuffer.get());
                    }
                    byteStream.write(byteBuffer.array(), 0, byteBuffer.position());
                }
            }
            return byteStream.toByteArray();
        }
    }

    /**
     * 从音频文件中提取PCM数据（自动资源管理版）
     * 使用FFmpegFrameGrabber代替ffmpeg命令行工具
     *
     * @param audioFilePath 音频文件路径： audio/2d073bd2a0d5471789956469411a8dd3.mp3
     * @param outputFormat   输出格式，默认 "s16le"
     * @param sampleRate    采样率,默认 16000
     * @param channels      通道数，默认 1
     */
    public static void convertAndSaveAudio(String audioFilePath, String outputFormat, int sampleRate, int channels){
        // 创建临时文件路径
        String tempFilePath = audioFilePath + ".tmp";
        // 使用FFmpegFrameGrabber读取原始音频
        try(FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audioFilePath);
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(tempFilePath, channels)){
            // 创建FFmpegFrameRecorder用于写入转换后的音频
            recorder.setAudioChannels(channels);
            recorder.setSampleRate(sampleRate);
            recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MP3);
            recorder.setAudioQuality(2);
            recorder.setFormat("mp3");
            recorder.start();
            // 开始读取音频
            grabber.start();
            // 转换并写入音频帧
            Frame frame;
            while ((frame = grabber.grab()) != null) {
                if (frame.samples != null) {
                    recorder.record(frame);
                }
            }
        }catch (Exception e) {
            logger.error("FFmpeg转换音频失败，e: {}", e.getMessage());
            throw new RuntimeException("音频转换失败");
        }
    }

}
