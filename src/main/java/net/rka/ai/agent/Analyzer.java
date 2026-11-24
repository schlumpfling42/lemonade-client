package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.rka.ai.tracing.AgentLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Asks the model what to do with the user's latest message ({@link AnalyzeMessage}) and reads its answer, a function call
 * ({@link Analysis#fromReply}). A call that cannot be used is answered like any tool call that failed: with an error as the result of that
 * call, and the model decides again, once, seeing its own try. After that there is none and the caller falls back.
 */
final class Analyzer {

    /** Tokens for the answer: a function call is a few lines, but a reasoning model thinks first and the thinking counts (an answer cut off before it said anything is asked once more with four times as much, see ContextFittingChatModel). */
    static final int ANSWER_TOKENS = 2_000;

    private final ChatModel model;

    Analyzer(ChatModel model) {
        this.model = model;
    }

    /**
     * @param request the whole request ({@link AnalyzeMessage#compose})
     * @param offered what the model may name
     * @return the analysis, or empty when two answers in a row could not be used
     */
    Optional<Analysis> analyze(ChatRequest request, Analysis.Offered offered) {
        ChatRequest current = request;
        for (int attempt = 1; attempt <= 2; attempt++) {
            AiMessage reply = model.chat(current).aiMessage();
            AgentLog.current().line("⇢ analysis answer: " + describe(reply));
            try {
                return Optional.of(Analysis.fromReply(reply, offered));
            } catch (IllegalArgumentException e) {
                AgentLog.current().line("✗ analysis not usable (attempt " + attempt + "): " + e.getMessage());
                List<ChatMessage> messages = new ArrayList<>(request.messages());
                if (reply != null && reply.hasToolExecutionRequests()) {
                    messages.add(reply);
                    for (var call : reply.toolExecutionRequests()) {
                        messages.add(ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                                .text("Error: " + e.getMessage()).isError(true).build());
                    }
                } else {
                    if (reply != null && reply.text() != null && !reply.text().isBlank()) messages.add(reply);
                    messages.add(UserMessage.from("That was not a function call: " + e.getMessage() + "."));
                }
                current = request.toBuilder().messages(messages).build();
            }
        }
        return Optional.empty();
    }

    private static String describe(AiMessage reply) {
        if (reply == null) return "(none)";
        if (!reply.hasToolExecutionRequests()) return AgentLog.oneLine(String.valueOf(reply.text()), 2000);
        StringBuilder out = new StringBuilder();
        for (var call : reply.toolExecutionRequests()) out.append(out.isEmpty() ? "" : " + ").append(call.name()).append(' ').append(call.arguments());
        return AgentLog.oneLine(out.toString(), 2000);
    }
}
