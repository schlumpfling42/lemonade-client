package net.rka.ai.agent;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.Map;
import java.util.Set;

/**
 * How hard the model thinks for one request ({@code chat_template_kwargs.reasoning_effort} of the model server). The agent runs at a
 * low effort by default (short thinking is most of the speed of a local model); the steps that decide where a change belongs, and
 * whether it does what was asked, ask for more.
 */
public final class Effort {

    public static final Set<String> LEVELS = Set.of("low", "medium", "high");

    private Effort() {
    }

    /** The request parameters that set the effort for one request. */
    public static OpenAiChatRequestParameters parameters(String level) {
        return OpenAiChatRequestParameters.builder()
                .customParameters(Map.of("chat_template_kwargs", Map.of("reasoning_effort", level)))
                .build();
    }
}
