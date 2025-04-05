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

        private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("[,，.。!！?？;；:：]");

        // 最大句子长度限制（字符数）
        private static final int MAX_SENTENCE_LENGTH = 80;

        // 最小句子长度（字符数）
        private static final int MIN_SENTENCE_LENGTH = 10;

        public SentenceProcessor(TriConsumer<String, Boolean, Boolean> handler) {
            this.handler = handler;
        }

        void processToken(String token) {
            currentSentence.append(token);
            
            // 判断句子结束
            Matcher matcher = SENTENCE_END_PATTERN.matcher(currentSentence);
            if (matcher.find() && currentSentence.length() >= MIN_SENTENCE_LENGTH) {
                pendingSentence = currentSentence.toString().trim();
                currentSentence.setLength(0);
                dispatchPending();
            }
        }

        void dispatchPending() {
            if (pendingSentence != null) {
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

