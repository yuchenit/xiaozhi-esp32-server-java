package com.xiaozhi.websocket.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.websocket.llm.LlmManager;
import com.xiaozhi.websocket.service.AudioService;
import com.xiaozhi.websocket.service.MessageService;
import com.xiaozhi.websocket.service.SpeechToTextService;
import com.xiaozhi.websocket.service.TextToSpeechService;
import com.xiaozhi.websocket.service.VadService;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import static com.xiaozhi.websocket.handler.WebSocketHandshakeHandler.SESSION_ID;

/**
 * WebSocket 二进制消息处理器
 * 处理所有二进制类型的 WebSocket 消息，主要是音频数据
 */
@Component
public class BinaryWebSocketFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

  private static final Logger logger = LoggerFactory.getLogger(BinaryWebSocketFrameHandler.class);

  @Autowired
  private VadService vadService;

  @Autowired
  private AudioService audioService;

  @Autowired
  private MessageService messageService;

  @Autowired
  private SpeechToTextService speechToTextService;

  @Autowired
  private TextToSpeechService textToSpeechService;

  @Autowired
  private LlmManager llmManager;

  @Autowired
  private SysConfigService configService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) throws Exception {
    String sessionId = ctx.channel().attr(SESSION_ID).get();

    // 检查会话是否处于监听状态，如果不是则忽略音频数据
    if (!TextWebSocketFrameHandler.isListening(sessionId)) {
      return;
    }

    SysDevice device = TextWebSocketFrameHandler.getDeviceConfig(sessionId);
    if (device == null) {
      logger.warn("收到二进制消息但设备未初始化 - SessionId: {}", sessionId);
      return;
    }

    // 获取设备配置
    SysConfig sttConfig = null;
    SysConfig ttsConfig = null;

    if (device.getSttId() != null) {
      sttConfig = configService.selectConfigById(device.getSttId());
    }

    if (device.getTtsId() != null) {
      ttsConfig = configService.selectConfigById(device.getTtsId());
    }

    ByteBuf content = frame.content();
    byte[] opusData = new byte[content.readableBytes()];
    content.readBytes(opusData);

    try {
      // 处理音频数据
      processAudioData(ctx, sessionId, device, sttConfig, ttsConfig, opusData);
    } catch (Exception e) {
      logger.error("处理二进制消息失败", e);
    }
    // 继续处理请求
    ctx.fireChannelRead(frame);
  }

  /**
   * 处理音频数据
   */
  private void processAudioData(ChannelHandlerContext ctx, String sessionId, SysDevice device,
      SysConfig sttConfig, SysConfig ttsConfig, byte[] opusData) throws Exception {
    // 将所有音频处理逻辑委托给AudioService
    byte[] completeAudio = vadService.processIncomingAudio(sessionId, opusData);

    if (completeAudio != null) {
      logger.info("检测到语音结束 - SessionId: {}, 音频大小: {} 字节", sessionId, completeAudio.length);
      String result;
      // 调用 SpeechToTextService 进行语音识别
      if (!ObjectUtils.isEmpty(sttConfig)) {
        result = speechToTextService.recognition(completeAudio, sttConfig);
      } else {
        String jsonResult = speechToTextService.recognition(completeAudio);
        JsonNode resultNode = objectMapper.readTree(jsonResult);
        result = resultNode.path("text").asText("");
      }

      if (StringUtils.hasText(result)) {
        logger.info("语音识别结果 - SessionId: {}, 内容: {}", sessionId, result);
        // 设置会话为非监听状态，防止处理自己的声音
        // 使用TextWebSocketFrameHandler中的静态方法来更新状态
        // 这里假设TextWebSocketFrameHandler提供了一个静态方法来设置监听状态
        // 实际上应该通过共享状态或事件机制来实现
        messageService.sendMessage(ctx.channel(), "stt", "start", result);

        // 使用句子切分处理流式响应
        llmManager.chatStreamBySentence(device, result, (sentence, isStart, isEnd) -> {
          try {
            String audioPath = textToSpeechService.textToSpeech(sentence, ttsConfig,
                device.getVoiceName());
            audioService.sendAudio(ctx.channel(), audioPath, sentence, isStart, isEnd);
          } catch (Exception e) {
            logger.error("处理句子失败: {}", e.getMessage(), e);
          }
        });
      }
    }
  }
}
