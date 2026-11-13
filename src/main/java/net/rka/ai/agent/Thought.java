package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A model reply split into its thinking and its words, for showing them apart (window and CLI).
 * <p>
 * Reasoning models send their thinking in one of two ways: as a separate field, which LangChain4j
 * puts into {@link AiMessage#thinking()} (the model is built with {@code returnThinking(true)}), or
 * inline at the start of the text as {@code <think>…</think>} (Qwen3 and DeepSeek-R1 style, when the
 * server doesn't split it off). Both end up in {@link #thinking()}. Models without thinking, like a
 * Qwen3 Instruct model, simply have none.
 *
 * @param thinking what the model considered before answering; "" if none
 * @param text     what it said, without the thinking; "" if none
 */
public record Thought(String thinking, String text) {

    private static final Pattern INLINE = Pattern.compile("^\\s*<think>(.*?)(?:</think>|$)(.*)", Pattern.DOTALL);

    public static Thought of(AiMessage ai) {
        String thinking = ai.thinking() == null ? "" : ai.thinking().strip();
        String text = ai.text() == null ? "" : ai.text();
        Matcher m = INLINE.matcher(text);
        if (m.matches()) {
            thinking = (thinking + "\n" + m.group(1)).strip();
            text = m.group(2);
        }
        return new Thought(thinking, text.strip());
    }
}
