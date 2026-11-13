package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import net.rka.ai.graph.annotations.Offers;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request that asks the model what to do: functions, the conversation in its own roles, and a required call. */
class AnalyzeMessageTest {

    private static Offers.Edge edge(String name, String does, String useWhen) {
        return new Offers.Edge() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String does() {
                return does;
            }

            @Override
            public String useWhen() {
                return useWhen;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return Offers.Edge.class;
            }
        };
    }

    private static final List<Offers.Edge> EDGES = List.of(edge("agent", "answers in text", "a question"), edge("job_plan", "coding job", "a small change"));

    private static ToolSpecification spec(ChatRequest request, String name) {
        return request.parameters().toolSpecifications().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theRequestIsAConstantSystemMessageThenTheConversationInItsOwnRolesAndACallIsRequired() {
        List<ChatMessage> conversation = List.of(SystemMessage.from("the agent system prompt"), UserMessage.from("first question"), AiMessage.from("first answer"),
                UserMessage.from("add subtract"));

        ChatRequest request = AnalyzeMessage.compose(EDGES, List.of(), false, conversation);

        assertEquals(List.of(SystemMessage.from(AnalyzeMessage.SYSTEM), UserMessage.from("first question"), AiMessage.from("first answer"), UserMessage.from("add subtract")),
                request.messages(), "the agent's own system prompt is left out; the rest is the conversation as it is");
        assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice());
    }

    @Test
    void theEdgesAreTheValuesOfTheRouteFunctionEachDescribed() {
        ChatRequest request = AnalyzeMessage.compose(EDGES, List.of(), false, List.of(UserMessage.from("hi")));

        String schema = spec(request, "route").parameters().toString();
        assertTrue(schema.contains("agent") && schema.contains("job_plan"), schema);
        assertTrue(schema.contains("agent: answers in text. Use when: a question") && schema.contains("job_plan: coding job. Use when: a small change"), schema);
        assertTrue(schema.contains("required = [goal, edge]"), schema);
    }

    @Test
    void theToolsComeWithTheirFullDefinitionAndAskUserOnlyWhenTheNodeMayAsk() {
        ToolSpecification read = ToolSpecification.builder().name("readSection").description("Reads one section of a file (e.g. imports). More text.")
                .parameters(JsonObjectSchema.builder().addStringProperty("path", "the file").addStringProperty("section", "the section id").required("path").build()).build();

        ChatRequest without = AnalyzeMessage.compose(EDGES, List.of(), false, List.of(UserMessage.from("hi")));
        ChatRequest with = AnalyzeMessage.compose(EDGES, List.of(read), true, List.of(UserMessage.from("hi")));

        assertEquals(List.of("route"), without.parameters().toolSpecifications().stream().map(ToolSpecification::name).toList());
        assertEquals(List.of("route", "ask_user", "readSection"), with.parameters().toolSpecifications().stream().map(ToolSpecification::name).toList());
        assertEquals(read, spec(with, "readSection"), "the definition is the registry's, whole: description, parameter names, types, required");
    }

    @Test
    void theAnalysisOwnToolCallsAndTheirResultsAreInTheConversationAsTheyAre() {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("c1").name("readFile").arguments("{}").build();
        List<ChatMessage> conversation = List.of(UserMessage.from("q"), AiMessage.from(List.of(call)), ToolExecutionResultMessage.from(call, "text of the file"));

        ChatRequest request = AnalyzeMessage.compose(EDGES, List.of(), false, conversation);

        assertEquals(4, request.messages().size());
        assertTrue(request.messages().get(2) instanceof AiMessage ai && ai.hasToolExecutionRequests());
        assertTrue(request.messages().get(3) instanceof ToolExecutionResultMessage result && result.text().equals("text of the file"));
    }
}
