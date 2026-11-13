package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides, before anything else happens in a turn, whether the user's message is coding work for the coding
 * skill. The model is asked the one question and answers one word.
 *
 * <h2>How it works</h2>
 * The request is short: the latest message, the request before it and the start of the last answer, with the question.
 * It carries no tools and not the conversation. Why: a request that repeated the agent's whole prompt (to share the model
 * server's prompt cache) read 20,000 tokens in 140 to 185 seconds whenever the last request was a coding job's, which is
 * the usual case when coding; a short prompt costs a few seconds. A follow-up like "implement it" is understood from the
 * request before it. Anything but a clear "CODING" counts as no: the agent step then runs as it always did.
 */
final class CodingTriage {

    /** The question, the last message of the request. */
    static final String QUESTION = "Before you do anything: decide how to handle the user's latest message. The latest message is "
            + "what counts: earlier requests in this conversation are history, not tasks to pick up again.\n"
            + "Answer CODING if the latest message by itself asks to build, add, change, fix, refactor or implement something in "
            + "software (a feature, a button, a bug, a new project, a library, tests, the build), also when it is about something "
            + "else than the earlier messages.\n"
            + "Answer CONTINUE only if the latest message can't be understood without the earlier ones because it points back at "
            + "them (\"implement it\", \"go ahead\", \"yes, do that\", \"do all of that\", \"replan\", \"I disagree with the plan\", "
            + "\"no, do it differently\", \"remove it again\", words like it, that, the plan, the code above) AND the work they "
            + "point at is analyzing or changing code. A message that criticizes or redirects what was just planned or done is "
            + "CONTINUE, even when it adds new wishes.\n"
            + "The test is what the work is: analyzing code or changing what is written in files (code, configuration, text) is "
            + "CODING; handling files themselves (listing, finding, opening, deleting, moving, renaming or copying them) is OTHER, "
            + "also when it is a yes to an earlier offer (\"yes, delete that file\").\n"
            + "Answer OTHER for everything else: questions and explanations (also about code), reading, listing or "
            + "searching files, notes and documents, general chat. A question about where something stands, what exists or how "
            + "something works (\"where are we with X?\", \"is X done?\", \"how does Y work?\") is OTHER, also when X is a feature: "
            + "nothing is to be built. Asking for an example, a demo or a sample that is written in the answer itself (a diagram, a "
            + "snippet, a table, a text) is OTHER too, also when it points back at earlier messages (\"create one and show it here\").\n"
            + "Reply with exactly one word, CODING, CONTINUE or OTHER. Do not call a tool.";

    private static final java.util.regex.Pattern ANSWER = java.util.regex.Pattern.compile("(?<![A-Za-z])(CODING|CONTINUE|OTHER)(?![A-Za-z])");

    /** What the model decided. */
    enum Decision {
        /** Coding work that the latest message describes by itself. */
        CODING,
        /** Coding work that the latest message only points at: the earlier messages say what it is. */
        CONTINUE,
        /** Not coding work. */
        OTHER
    }

    /**
     * The tokens the model may use for its answer. The word is one or two tokens, but a reasoning model thinks first and the thinking
     * counts: with a limit of 8 it ran out in its thinking and answered nothing, so every message was "not coding work".
     */
    static final int ANSWER_TOKENS = 300;

    private final ChatModel model;

    CodingTriage(ChatModel model) {
        this.model = model;
    }

    /**
     * @param messages the messages the agent step sends (see {@link AgentGraph#agentMessages})
     * @return whether the model answered CODING
     */
    boolean isCoding(List<ChatMessage> messages) {
        return decide(messages) != Decision.OTHER;
    }

    /**
     * @param messages the messages the agent step sends (see {@link AgentGraph#agentMessages})
     * @return what the model answered; anything but a clear CODING or CONTINUE is OTHER
     */
    Decision decide(List<ChatMessage> messages) {
        ChatResponse response = model.chat(ChatRequest.builder().messages(UserMessage.from(context(messages) + QUESTION))
                .maxOutputTokens(ANSWER_TOKENS).temperature(0.0).build());
        String answer = response.aiMessage().text();
        if (response.aiMessage().hasToolExecutionRequests() || answer == null) return Decision.OTHER;
        // A reasoning model may write its reasoning before the word ("That is a request to implement changes. So answer CODING."): the
        // last of the three words, written in capitals, is the answer
        java.util.regex.Matcher words = ANSWER.matcher(answer);
        String last = null;
        while (words.find()) last = words.group(1);
        if (last == null) last = answer.strip().toUpperCase(Locale.ROOT); // a plain "coding." in lower case
        if (last.startsWith("CODING")) return Decision.CODING;
        // CONTINUE points back at earlier messages: with none there is nothing it can point at
        if (last.startsWith("CONTINUE")) return userMessages(messages) > 1 ? Decision.CONTINUE : Decision.OTHER;
        return Decision.OTHER;
    }

    private static long userMessages(List<ChatMessage> messages) {
        return messages.stream().filter(m -> m instanceof UserMessage).count();
    }

    /** Most characters of an earlier message that the question shows. */
    static final int CLIP = 600;

    /** The latest user message, the one before it and the last answer, each cut short. */
    static String context(List<ChatMessage> messages) {
        String latest = null;
        String before = null;
        String answer = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof UserMessage u) {
                String text = u.hasSingleText() ? u.singleText() : u.contents().toString();
                if (latest == null) latest = text;
                else if (before == null) before = text;
            } else if (m instanceof AiMessage a && answer == null && latest != null && a.text() != null && !a.text().isBlank()) {
                answer = a.text();
            }
            if (before != null) break;
        }
        StringBuilder out = new StringBuilder();
        if (before != null) out.append("The request before it: ").append(clip(before)).append('\n');
        if (answer != null) out.append("The answer to that: ").append(clip(answer)).append('\n');
        return out.append("The latest message: ").append(latest == null ? "" : clip(latest)).append("\n\n").toString();
    }

    private static String clip(String text) {
        String flat = text.strip();
        return flat.length() > CLIP ? flat.substring(0, CLIP) + "…" : flat;
    }
}
