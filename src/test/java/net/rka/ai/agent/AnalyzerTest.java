package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    private static final Analysis.Offered OFFERED = new Analysis.Offered(Set.of("agent", "job_plan"), Set.of(), false);
    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("add subtract")).build();
    private final List<ChatRequest> requests = new ArrayList<>();
    private final Deque<AiMessage> answers = new ArrayDeque<>();

    private Analyzer analyzer() {
        return new Analyzer(new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(answers.poll()).build();
            }
        });
    }

    private static AiMessage route(String edge) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id("r" + edge).name("route")
                .arguments("{\"goal\":\"g\",\"edge\":\"" + edge + "\"}").build()));
    }

    @Test
    void aCallThatCannotBeUsedIsAnsweredWithAnErrorResultAndTheModelDecidesOnceMore() {
        answers.add(route("job_study"));
        answers.add(route("job_plan"));

        var analysis = analyzer().analyze(REQUEST, OFFERED);

        assertEquals(new Analysis.Edge("job_plan"), analysis.orElseThrow().next());
        assertEquals(2, requests.size());
        var second = requests.get(1).messages();
        assertTrue(second.get(second.size() - 2) instanceof AiMessage ai && ai.hasToolExecutionRequests(), "its own call comes first");
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) second.getLast();
        assertEquals("rjob_study", result.id());
        assertTrue(result.text().contains("the edge \"job_study\" is not offered") && Boolean.TRUE.equals(result.isError()), result.text());
    }

    @Test
    void twoUnusableAnswersInARowGiveNothing() {
        answers.add(AiMessage.from("CODING"));
        answers.add(AiMessage.from("still text"));

        assertTrue(analyzer().analyze(REQUEST, OFFERED).isEmpty());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).messages().getLast() instanceof UserMessage user && user.singleText().contains("not a function call"));
    }

    @Test
    void everyAnswerIsWrittenToTheAgentLog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var log = new net.rka.ai.tracing.AgentLog(net.rka.ai.tracing.AgentLog.Level.SUMMARY, dir, false);
        net.rka.ai.tracing.AgentLog.configure(log);
        try {
            answers.add(AiMessage.from("nonsense"));
            answers.add(route("job_plan"));
            analyzer().analyze(REQUEST, OFFERED);
            String text = java.nio.file.Files.readString(log.file());
            assertTrue(text.contains("analysis answer: nonsense"), text);
            assertTrue(text.contains("analysis answer: route {\"goal\":\"g\",\"edge\":\"job_plan\"}"), text);
        } finally {
            net.rka.ai.tracing.AgentLog.configure(net.rka.ai.tracing.AgentLog.OFF);
        }
    }
}
