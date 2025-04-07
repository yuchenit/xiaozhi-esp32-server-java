package com.xiaozhi.websocket.service;

import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.utils.DateUtils;
import com.xiaozhi.websocket.stt.SttService;
import com.xiaozhi.websocket.stt.factory.SttServiceFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SpeechToTextService {

  private static final Logger logger = LoggerFactory.getLogger(SpeechToTextService.class);

  @Autowired
  private SttServiceFactory sttServiceFactory;

  /**
   * 处理音频数据并转换为文本
   *
   * @param audioData 音频字节数组
   * @param sttConfig STT服务配置实体
   * @return 识别的文本结果
   */
  public String recognition(byte[] audioData, SysConfig sttConfig) {
    if (audioData == null || audioData.length == 0) {
      logger.warn("音频数据为空！");
      return null;
    }

    // 根据配置获取对应的STT服务
    SttService sttService = sttServiceFactory.getSttService(sttConfig);
    if (sttService == null) {
      logger.error("无法获取ID为{}的STT服务", sttConfig.getProvider());
      return null;
    }

    long startTime = System.currentTimeMillis(); // 记录开始时间
    String result = sttService.recognition(audioData);
    long endTime = System.currentTimeMillis();
    double duration = DateUtils.deltaTime(startTime, endTime);
    logger.info("{} 语音识别完成，耗时：{} 秒", sttConfig.getProvider(), duration);
    return result;
  }

  /**
   * 使用默认STT服务处理音频
   * 如果Vosk初始化失败，会自动降级到其他可用服务
   *
   * @param audioData 音频字节数组
   * @return 识别的文本结果
   */
  public String recognition(byte[] audioData) {
    if (audioData == null || audioData.length == 0) {
      logger.warn("音频数据为空！");
      return null;
    }

    // 获取STT服务
    SttService sttService = sttServiceFactory.getSttService(null);
    if (sttService == null) {
      logger.error("无法获取STT服务");
      return null;
    }

    logger.info("使用STT服务: {}", sttService.getProviderName());
    long startTime = System.currentTimeMillis(); // 记录开始时间
    String result = sttService.recognition(audioData);
    long endTime = System.currentTimeMillis();
    double duration = DateUtils.deltaTime(startTime, endTime);
    logger.info("语音识别完成，耗时：{} 秒", duration);
    return result;
  }

}
