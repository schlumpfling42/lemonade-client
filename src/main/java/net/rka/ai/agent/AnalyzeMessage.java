package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import net.rka.ai.graph.annotations.Offers;

import java.util.ArrayList;
import java.util.List;

/**
 * The request that asks the model what to do with the user's latest message, in the format the model is made for: a short constant system
 * message, the conversation as it happened (user, assistant and tool messages), the functions {@code route} and {@code ask_user} next
 * to the read tools of the registry (their full definitions), and {@code tool_choice: required}, so the answer is one call of one of them.
 * The answer is read by {@link Analysis#fromReply}.
 *
 * <p>The system message and the function list are constant, and the conversation comes after them, so the model server reuses what it
 * read of the start between turns (it does not read them again when only the last message changes). The edges that {@code route} may take
 * come from the {@link Offers} annotation of the node: each one is described in the schema of the parameter {@code edge}.
 */
final class AnalyzeMessage {

    /** Constant: who the model is here and what each function is for. */
    static final String SYSTEM = "You decide what happens with the user's latest message. Answer with one function call:\n"
            + "route: go on. Write the goal as one sentence that stands alone, every thing it refers to written out (not \"that\", \"it\", \"the plan\").\n"
            + "ask_user: only when the request is so unclear that a short question settles it, even with the conversation read. A message about what was just said or done (why, what, did you) is a question about the conversation: route it to agent.\n"
            + "a tool: only when you must look at the project before you can choose.";

    private AnalyzeMessage() {
    }

    /**
     * @param edges        the edges offered from the node (only those that exist now)
     * @param tools        the read tools offered from the node, as the registry describes them
     * @param ask          whether the model may ask the user
     * @param conversation the messages of the conversation, the user's latest message (or the result of the analysis' own tool call) last
     */
    static ChatRequest compose(List<Offers.Edge> edges, List<ToolSpecification> tools, boolean ask, List<ChatMessage> conversation) {
        List<ToolSpecification> functions = new ArrayList<>();
        functions.add(route(edges));
        if (ask) functions.add(askUser());
        functions.addAll(tools);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM));
        for (ChatMessage message : conversation) {
            if (!(message instanceof SystemMessage)) messages.add(message); // the agent's own system message is for the agent
        }
        return ChatRequest.builder().messages(messages).toolSpecifications(functions).toolChoice(ToolChoice.REQUIRED)
                .maxOutputTokens(Analyzer.ANSWER_TOKENS).temperature(0.0).build();
    }

    private static ToolSpecification route(List<Offers.Edge> edges) {
        StringBuilder where = new StringBuilder("where to go next, one of:");
        for (Offers.Edge edge : edges) {
            where.append("\n").append(edge.name()).append(": ").append(edge.does()).append(". Use when: ").append(edge.useWhen());
        }
        return ToolSpecification.builder().name(Analysis.ROUTE)
                .description("Go on to a node of the graph; this step ends.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("goal", "what the user wants: one sentence that stands alone, everything it refers to written out")
                        .addEnumProperty("edge", edges.stream().map(Offers.Edge::name).toList(), where.toString())
                        .required("goal", "edge").build())
                .build();
    }

    private static ToolSpecification askUser() {
        return ToolSpecification.builder().name(Analysis.ASK_USER)
                .description("Ask the user a question; their answer comes back to you.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("question", "the question")
                        .addProperty("options", JsonArraySchema.builder().items(JsonStringSchema.builder().build())
                                .description("the answers the user can choose from; none means a free answer").build())
                        .required("question").build())
                .build();
    }
}
