package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * The plan as a function the model calls ({@code submit_plan}), in place of a JSON object it writes in its text: the server holds the
 * model to the schema (names, types, the actions as an enum), and the arguments are the plan JSON that {@link CodingPlan#parse} reads.
 */
final class PlanFunction {

    static final String NAME = "submit_plan";

    private PlanFunction() {
    }

    static ToolSpecification spec() {
        JsonObjectSchema read = JsonObjectSchema.builder()
                .addStringProperty("file", "relative path")
                .addStringProperty("section", "a section id from the outline")
                .required("file", "section").build();
        JsonObjectSchema command = JsonObjectSchema.builder()
                .addStringProperty("command", "the exact command line: one command, no && or ||")
                .addStringProperty("workingDirectory", "relative directory; empty for the trusted folder")
                .required("command").build();
        JsonObjectSchema change = JsonObjectSchema.builder()
                .addStringProperty("file", "relative path")
                .addStringProperty("section", "the section id only, as in the last column of the outline (for example Order.print()), never code; empty for create")
                .addEnumProperty("action", List.of("patch", "replace", "add", "before", "after", "start", "end", "delete", "create"),
                        "what to do with the section; patch needs the field patch (the hunks), the other actions have none")
                .addStringProperty("anchor", "only for before / after inside a method: one line of it, copied exactly")
                .addStringProperty("patch", "the hunks when the action is patch, else an empty string. Lines that stay start with a blank"
                        + " (copied exactly from the code), lines that go with -, lines that are added with +; start another hunk with @@")
                .addStringProperty("intent", "one sentence: what this change achieves")
                .required("file", "section", "action", "patch", "intent").build();
        return ToolSpecification.builder().name(NAME)
                .description("Hand in the plan of the job: the ordered changes, or only the sections to read first.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("summary", "what the job does, in one or two sentences")
                        .addProperty("read", array(read, "sections you need to see before you can plan; then no changes"))
                        .addProperty("setup", array(command, "commands that run before the changes"))
                        .addProperty("changes", array(change, "the ordered changes"))
                        .addProperty("verify", array(command, "build or test commands that run after the last change; none for the project's own"))
                        .required("summary").build())
                .build();
    }

    private static JsonArraySchema array(JsonObjectSchema items, String description) {
        return JsonArraySchema.builder().items(items).description(description).build();
    }

    /** The plan text of a reply: the arguments of its submit_plan call, or else its text (a model that answered the old way). */
    static String planText(AiMessage reply) {
        if (reply.hasToolExecutionRequests()) {
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                if (NAME.equals(call.name())) return call.arguments();
            }
        }
        return reply.text() == null ? "" : reply.text();
    }
}
