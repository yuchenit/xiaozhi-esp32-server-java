package com.xiaozhi.websocket.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SentenceProcessor {

    private final StringBuilder currentSentence = new StringBuilder();
    private final AtomicInteger sentenceCount = new AtomicInteger(0);
    private String pendingSentence;
    private final TriConsumer<String, Boolean, Boolean> handler;

    // 匹配可以作为分割点的标点（包括句子结束标点和适当的分割标点）
    private static final Pattern SPLIT_POINT_PATTERN = 
        Pattern.compile("[，,。！？.!?;；:：]|(\\.\\s)|(\\?\\s)|(!\\s)");

    // 最小句子长度（避免过短的句子）
    private static final int MIN_SENTENCE_LENGTH = 5;

    public SentenceProcessor(TriConsumer<String, Boolean, Boolean> handler) {
        this.handler = handler;
    }

    void processToken(String token) {
        currentSentence.append(token);
        int lastSplitPos = findLastSplitPoint();
        
        // 如果有合适的分割点且满足最小长度要求，则分割
        if (lastSplitPos > 0 && currentSentence.length() >= MIN_SENTENCE_LENGTH) {
            String completeSentence = currentSentence.substring(0, lastSplitPos + 1).trim();
            String remaining = currentSentence.substring(lastSplitPos + 1).trim();
            
            pendingSentence = completeSentence;
            currentSentence.setLength(0);
            currentSentence.append(remaining);
            
            dispatchPending();
        }
    }

    // 查找最后一个合适的分割点
    private int findLastSplitPoint() {
        Matcher matcher = SPLIT_POINT_PATTERN.matcher(currentSentence);
        int lastPos = -1;
        
        while (matcher.find()) {
            // 确保分割点不在字符串的开头
            if (matcher.start() > 0) {
                lastPos = matcher.start();
            }
        }
        
        // 确保分割后的句子长度合理
        if (lastPos > 0 && lastPos >= MIN_SENTENCE_LENGTH - 1) {
            return lastPos;
        }
        return -1;
    }

    void dispatchPending() {
        if (pendingSentence != null && !pendingSentence.isEmpty()) {
            boolean isFirst = sentenceCount.get() == 0;
            handler.accept(pendingSentence, isFirst, false);
            sentenceCount.incrementAndGet();
            pendingSentence = null;
        }
    }

    void finalizeProcessing() {
        dispatchPending(); // 处理剩余pending
        
        if (currentSentence.length() > 0) {
            String finalSentence = currentSentence.toString().trim();
            boolean isFirst = sentenceCount.get() == 0;
            handler.accept(finalSentence, isFirst, true);
            sentenceCount.incrementAndGet();
        } else if (sentenceCount.get() == 0) {
            handler.accept("", true, true);
        }
    }

    void handleError(Throwable e) {
        log.error("Stream processing error", e);
        handler.accept("抱歉，我在处理您的请求时遇到了问题。", true, true);
    }
}