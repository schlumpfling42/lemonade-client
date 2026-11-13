package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The answer of the model is a function call: route, ask_user or a read tool. */
class AnalysisTest {

    private static final Analysis.Offered OFFERED = new Analysis.Offered(Set.of("agent", "job_plan"), Set.of("readSection"), true);

    private static AiMessage call(String name, String arguments) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id("c1").name(name).arguments(arguments).build()));
    }

    @Test
    void aRouteCarriesItsGoalAndEdge() {
        Analysis analysis = Analysis.fromReply(call("route", "{\"goal\":\"Add subtract to Calc\",\"edge\":\"job_plan\"}"), OFFERED);

        assertEquals("Add subtract to Calc", analysis.goal());
        assertEquals(new Analysis.Edge("job_plan"), analysis.next());
    }

    @Test
    void aToolCallKeepsItsIdAndArgumentsAndAQuestionItsOptions() {
        Analysis tool = Analysis.fromReply(call("readSection", "{\"path\":\"A.java\",\"section\":\"A\"}"), OFFERED);
        Analysis ask = Analysis.fromReply(call("ask_user", "{\"question\":\"Which library?\",\"options\":[\"Java2D\",\" \",\"Batik\"]}"), OFFERED);

        assertEquals("readSection", ((Analysis.Tool) tool.next()).name());
        assertEquals("c1", ((Analysis.Tool) tool.next()).id());
        assertEquals("A.java", ((Analysis.Tool) tool.next()).args().path("path").asText());
        assertEquals(new Analysis.Ask("Which library?", List.of("Java2D", "Batik")), ask.next());
    }

    @Test
    void onlyTheFirstCallCountsWhenTheModelMakesSeveral() {
        AiMessage two = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("1").name("route").arguments("{\"goal\":\"g\",\"edge\":\"agent\"}").build(),
                ToolExecutionRequest.builder().id("2").name("route").arguments("{\"goal\":\"h\",\"edge\":\"job_plan\"}").build()));

        assertEquals(new Analysis.Edge("agent"), Analysis.fromReply(two, OFFERED).next());
    }

    @Test
    void whatIsNotOfferedOrNotACallIsRefusedWithASentenceForTheModel() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(call("route", "{\"goal\":\"g\",\"edge\":\"job_study\"}"), OFFERED))
                .getMessage().contains("\"job_study\" is not offered: use one of agent, job_plan"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(call("runCommand", "{}"), OFFERED))
                .getMessage().contains("not offered"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(call("route", "{\"goal\":\"\",\"edge\":\"agent\"}"), OFFERED))
                .getMessage().contains("goal"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(AiMessage.from("CODING"), OFFERED))
                .getMessage().contains("you answered in text"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(call("route", "{\"goal\": }"), OFFERED))
                .getMessage().contains("not valid JSON"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Analysis.fromReply(call("ask_user", "{\"question\":\"?\"}"),
                new Analysis.Offered(Set.of("agent"), Set.of(), false))).getMessage().contains("not offered"));
    }
}
