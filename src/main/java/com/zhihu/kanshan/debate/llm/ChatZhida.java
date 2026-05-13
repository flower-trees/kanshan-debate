package com.zhihu.kanshan.debate.llm;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Zhida LLM node for j-langchain chains.
 * Overrides getConsumer() to fast-fail on API errors (e.g. 429) instead of
 * blocking 60 s waiting for a terminal signal that the base class never sends.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChatZhida extends BaseChatModel {

    protected ChatZhida(Builder builder) {
        this.model = builder.model;
    }

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setModel(model);
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return ZhidaActuator.class;
    }

    /**
     * On ERROR code, immediately append a STOP-flagged chunk so the iterator
     * terminates right away rather than waiting for the 60-second poll timeout.
     */
    @Override
    protected Consumer<AiChatOutput> getConsumer(AIMessageChunk aiMessageChunk) {
        Consumer<AiChatOutput> parent = super.getConsumer(aiMessageChunk);
        return aiChatOutput -> {
            if (AiChatCode.ERROR.getCode().equals(aiChatOutput.getCode())) {
                AIMessageChunk stopChunk = AIMessageChunk.builder()
                    .finishReason(FinishReasonType.STOP.getCode())
                    .build();
                aiMessageChunk.add(stopChunk);
                try {
                    aiMessageChunk.getIterator().append(stopChunk);
                } catch (TimeoutException e) {
                    throw new RuntimeException(e);
                }
                return;
            }
            parent.accept(aiChatOutput);
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model = "zhida-thinking-1p5";

        public Builder model(String model) { this.model = model; return this; }
        public ChatZhida build() { return new ChatZhida(this); }
    }
}
