package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The code of the edit step as a function the model calls ({@code write_code}), in place of code blocks in its text: one text per change,
 * in the order the changes were listed. No fences to find, and the server holds the answer to the schema.
 */
final class EditFunction {

    static final String NAME = "write_code";

    private static final ObjectMapper JSON = new ObjectMapper();

    private EditFunction() {
    }

    static ToolSpecification spec() {
        return ToolSpecification.builder().name(NAME)
                .description("Hand in the new text of the listed changes.")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("texts", JsonArraySchema.builder().items(JsonStringSchema.builder().build())
                                .description("one text per change, in the order listed: exactly what that change's action asks for, as plain code"
                                        + " (no Markdown fence, no explanation)").build())
                        .required("texts").build())
                .build();
    }

    /** The texts of the reply: the {@code texts} of its write_code call, or else the code blocks of its text (a model that answered the old way). */
    static List<String> texts(AiMessage reply) {
        if (reply.hasToolExecutionRequests()) {
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                if (!NAME.equals(call.name())) continue;
                try {
                    JsonNode texts = JSON.readTree(call.arguments()).get("texts");
                    List<String> out = new ArrayList<>();
                    if (texts != null && texts.isArray()) texts.forEach(t -> out.add(t.asText()));
                    else if (texts != null && texts.isTextual()) out.add(texts.asText());
                    return out.stream().filter(t -> !t.isBlank()).toList();
                } catch (Exception e) {
                    return List.of(); // malformed arguments: no code
                }
            }
        }
        return CodeBlocks.all(reply.text() == null ? "" : reply.text());
    }
}
