package net.rka.ai.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the waiting questions look from outside and how an answer from outside becomes the dialog's decision, and how messages are shown. */
class AskBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode args(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ToolExecutionRequest command(String command) {
        return ToolExecutionRequest.builder().id("c1").name("runCommand").arguments("{\"command\":\"" + command + "\",\"workingDirectory\":\"\"}").build();
    }

    @Test
    void aCommandIsShownWithItsCommandAndTheToolName() {
        JsonNode details = AskBridge.toolCall(command("git status"));

        assertEquals("runCommand", details.get("tool").asText());
        assertEquals("git status", details.get("command").asText());
        assertEquals("c1", details.get("callId").asText());
        assertTrue(details.get("answerWith").asText().contains("run"));
    }

    @Test
    void aToolCallIsRunOnceDeniedOrTrustedForTheConversationOrEverywhere() {
        ToolExecutionRequest call = command("git status*");

        assertEquals(new ApprovalDialog.Answer(true, ApprovalDialog.Scope.NONE, null), AskBridge.toolAnswer(call, args("{\"run\":true}")));
        assertEquals(ApprovalDialog.Answer.DENIED, AskBridge.toolAnswer(call, args("{\"run\":false}")));
        // trusting takes the command itself unless an entry is given
        assertEquals(new ApprovalDialog.Answer(true, ApprovalDialog.Scope.CONVERSATION, "git status*"),
                AskBridge.toolAnswer(call, args("{\"run\":true,\"always\":\"conversation\"}")));
        assertEquals(new ApprovalDialog.Answer(true, ApprovalDialog.Scope.GLOBAL, "git log*"),
                AskBridge.toolAnswer(call, args("{\"run\":true,\"always\":\"everywhere\",\"entry\":\"git log*\"}")));
    }

    @Test
    void aToolWithoutACommandIsTrustedAsTheWholeTool() {
        ToolExecutionRequest write = ToolExecutionRequest.builder().id("w1").name("writeFile").arguments("{\"path\":\"a.txt\",\"content\":\"x\"}").build();

        ApprovalDialog.Answer answer = AskBridge.toolAnswer(write, args("{\"run\":true,\"always\":\"everywhere\"}"));

        assertEquals("tool:writeFile", answer.entry());
    }

    @Test
    void anAnswerThatDoesNotFitIsRefusedWithWhatToSend() {
        ToolExecutionRequest call = command("ls");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskBridge.toolAnswer(call, args("{}"))).getMessage().contains("`run`"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskBridge.toolAnswer(call, args("{\"run\":true,\"always\":\"sometimes\"}")))
                .getMessage().contains("conversation"));
        // a command with a shell operator can only be run once, the dialog's own rule
        ToolExecutionRequest chained = command("ls; rm -rf x");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskBridge.toolAnswer(chained, args("{\"run\":true,\"always\":\"everywhere\"}")))
                .getMessage().contains("can't be trusted"));
        assertEquals(new ApprovalDialog.Answer(true, ApprovalDialog.Scope.NONE, null), AskBridge.toolAnswer(chained, args("{\"run\":true}")));
    }

    @Test
    void aPlanIsApprovedDeniedOrRevisedWithAComment() {
        assertEquals(new PlanDecision(PlanDecision.Kind.APPROVE, ""), AskBridge.planAnswer(args("{\"decision\":\"approve\"}")));
        assertEquals(new PlanDecision(PlanDecision.Kind.DENY, ""), AskBridge.planAnswer(args("{\"decision\":\"deny\"}")));
        assertEquals(new PlanDecision(PlanDecision.Kind.REVISE, "smaller"), AskBridge.planAnswer(args("{\"decision\":\"revise\",\"comment\":\"smaller\"}")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskBridge.planAnswer(args("{\"decision\":\"revise\"}"))).getMessage().contains("`comment`"));
        assertThrows(IllegalArgumentException.class, () -> AskBridge.planAnswer(args("{\"decision\":\"maybe\"}")));
    }

    @Test
    void aQuestionTakesOneOfItsChoicesOrIsDismissed() {
        SkillQuestion question = new SkillQuestion("coding", "approveRoadmap", "Do these steps?", List.of("Do these steps", "One job instead"));

        JsonNode details = AskBridge.question(question);
        assertEquals("Do these steps?", details.get("question").asText());
        assertEquals("One job instead", details.get("choices").get(1).asText());

        assertEquals(new SkillAnswer("Do these steps", "go"), AskBridge.questionAnswer(question, args("{\"choice\":\"Do these steps\",\"comment\":\"go\"}")));
        assertTrue(AskBridge.questionAnswer(question, args("{\"dismiss\":true}")).isDismissed());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskBridge.questionAnswer(question, args("{\"choice\":\"Do nothing\"}")))
                .getMessage().contains("One job instead"));
    }

    @Test
    void aSkillDraftIsSavedRevisedOrCancelled() {
        assertEquals(new SkillDecision(SkillDecision.Kind.SAVE, ""), AskBridge.skillAnswer(args("{\"decision\":\"save\"}")));
        assertEquals(new SkillDecision(SkillDecision.Kind.CANCEL, ""), AskBridge.skillAnswer(args("{\"decision\":\"cancel\"}")));
        assertEquals(new SkillDecision(SkillDecision.Kind.REVISE, "shorter"), AskBridge.skillAnswer(args("{\"decision\":\"revise\",\"comment\":\"shorter\"}")));
        assertThrows(IllegalArgumentException.class, () -> AskBridge.skillAnswer(args("{\"decision\":\"revise\"}")));
        assertThrows(IllegalArgumentException.class, () -> AskBridge.skillAnswer(args("{}")));
    }

    @Test
    void messagesAreShownWithTheirRolesToolCallsAndResultsAndLongTextIsClipped() {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("t1").name("readFile").arguments("{\"path\":\"a.txt\"}").build();
        List<ChatMessage> messages = List.of(SystemMessage.from("system text"), UserMessage.from("read a.txt"),
                AiMessage.from("looking", List.of(call)), ToolExecutionResultMessage.from(call, "contents"),
                AiMessage.from("x".repeat(ChatControl.MAX_TEXT + 50)));

        JsonNode shown = ChatControl.messages(messages);

        assertEquals("system", shown.get(0).get("role").asText());
        assertEquals("read a.txt", shown.get(1).get("text").asText());
        assertEquals("readFile", shown.get(2).get("toolCalls").get(0).get("name").asText());
        assertEquals("t1", shown.get(3).get("callId").asText());
        assertEquals("tool", shown.get(3).get("role").asText());
        assertFalse(shown.get(3).get("error").asBoolean());
        assertTrue(shown.get(4).get("text").asText().endsWith("[… 50 more characters]"), shown.get(4).get("text").asText().substring(ChatControl.MAX_TEXT));
        assertNull(shown.get(1).get("toolCalls"));
        assertEquals(4, shown.get(4).get("n").asInt());
    }
}
