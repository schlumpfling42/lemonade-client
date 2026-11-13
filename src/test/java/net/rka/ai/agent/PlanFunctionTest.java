package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan is handed in by a function; its arguments are the plan JSON. */
class PlanFunctionTest {

    @Test
    void theArgumentsOfTheCallAreThePlan() {
        AiMessage reply = AiMessage.from(List.of(ToolExecutionRequest.builder().id("1").name(PlanFunction.NAME)
                .arguments("{\"summary\":\"s\",\"changes\":[{\"file\":\"A.java\",\"section\":\"A\",\"action\":\"add\",\"patch\":\"\",\"intent\":\"i\"}]}").build()));

        CodingPlan plan = CodingPlan.parse(PlanFunction.planText(reply));

        assertEquals("s", plan.summary());
        assertEquals(CodingPlan.Action.ADD, plan.changes().getFirst().action());
    }

    @Test
    void aReplyInTextIsStillRead() {
        assertEquals("{\"summary\":\"s\"}", PlanFunction.planText(AiMessage.from("{\"summary\":\"s\"}")));
    }

    @Test
    void theSchemaNamesTheActionsAndRequiresThePatchField() {
        String schema = PlanFunction.spec().parameters().toString();

        assertTrue(schema.contains("patch") && schema.contains("create") && schema.contains("required = [file, section, action, patch, intent]"), schema);
    }
}
