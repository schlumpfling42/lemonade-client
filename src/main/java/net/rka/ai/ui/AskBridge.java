package net.rka.ai.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.SkillDraft;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;
import net.rka.ai.tools.TrustedCommands;

import java.util.Map;

/**
 * Translates between the application's questions to the person and the MCP server's way of showing and answering them: what a waiting
 * question looks like (an object with the details to decide on) and how the arguments of an answer become the decision the dialog would
 * have returned. Pure functions, so they are tested without a window. An answer that does not fit is an
 * {@link IllegalArgumentException} whose message tells the caller what to send.
 */
final class AskBridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AskBridge() {
    }

    // ---- a tool call or command ----

    static ObjectNode toolCall(ToolExecutionRequest call) {
        ObjectNode details = JSON.createObjectNode();
        details.put("tool", call.name());
        details.put("callId", call.id());
        try {
            JsonNode arguments = JSON.readTree(call.arguments() == null ? "{}" : call.arguments());
            details.set("arguments", arguments);
            if (arguments.hasNonNull("command")) details.put("command", arguments.get("command").asText());
        } catch (java.io.IOException e) {
            details.put("arguments", String.valueOf(call.arguments()));
        }
        details.put("answerWith", "run (true/false); optionally always (conversation|everywhere) and entry");
        return details;
    }

    /** run: whether to run it; always: trust it for the conversation or everywhere; entry: what to trust (default: the command, or the whole tool). */
    static ApprovalDialog.Answer toolAnswer(ToolExecutionRequest call, JsonNode arguments) {
        JsonNode run = arguments.get("run");
        if (run == null || !run.isBoolean()) throw new IllegalArgumentException("Answer a tool call with `run`: true or false.");
        if (!run.asBoolean()) return ApprovalDialog.Answer.DENIED;
        String always = arguments.path("always").asText("");
        if (always.isBlank()) return new ApprovalDialog.Answer(true, ApprovalDialog.Scope.NONE, null);
        ApprovalDialog.Scope scope = switch (always) {
            case "conversation" -> ApprovalDialog.Scope.CONVERSATION;
            case "everywhere" -> ApprovalDialog.Scope.GLOBAL;
            default -> throw new IllegalArgumentException("`always` is `conversation` or `everywhere`.");
        };
        String entry = arguments.path("entry").asText("");
        if (entry.isBlank()) {
            try {
                JsonNode command = JSON.readTree(call.arguments() == null ? "{}" : call.arguments()).get("command");
                entry = command != null && command.isTextual() ? command.asText().strip() : TrustedCommands.toolEntry(call.name());
            } catch (java.io.IOException e) {
                entry = TrustedCommands.toolEntry(call.name());
            }
        }
        if (!TrustedCommands.canBeTrusted(entry)) {
            throw new IllegalArgumentException("This entry can't be trusted (a command with ; & | < > ` $ ( ) { } can only be run once): " + entry);
        }
        return new ApprovalDialog.Answer(true, scope, entry);
    }

    // ---- a plan ----

    static ObjectNode plan(PlanReview review) {
        ObjectNode details = JSON.createObjectNode();
        details.put("plan", review.toMarkdown());
        details.put("answerWith", "decision: approve | revise | deny; comment (for revise)");
        return details;
    }

    static PlanDecision planAnswer(JsonNode arguments) {
        String decision = arguments.path("decision").asText("");
        String comment = arguments.path("comment").asText("");
        return switch (decision) {
            case "approve" -> new PlanDecision(PlanDecision.Kind.APPROVE, comment);
            case "deny" -> new PlanDecision(PlanDecision.Kind.DENY, comment);
            case "revise" -> {
                if (comment.isBlank()) throw new IllegalArgumentException("To revise a plan say what should change in `comment`.");
                yield new PlanDecision(PlanDecision.Kind.REVISE, comment);
            }
            default -> throw new IllegalArgumentException("Answer a plan with `decision`: approve, revise or deny.");
        };
    }

    // ---- a question of a skill or of the analysis ----

    static ObjectNode question(SkillQuestion question) {
        ObjectNode details = JSON.createObjectNode();
        details.put("skill", question.skill());
        details.put("question", question.question());
        details.set("choices", JSON.valueToTree(question.choices()));
        details.put("answerWith", "choice (one of the choices) and comment, or dismiss: true");
        return details;
    }

    static SkillAnswer questionAnswer(SkillQuestion question, JsonNode arguments) {
        if (arguments.path("dismiss").asBoolean(false)) return SkillAnswer.dismissed();
        String choice = arguments.path("choice").asText("");
        if (!question.choices().contains(choice)) {
            throw new IllegalArgumentException("`choice` must be one of " + question.choices() + " (or dismiss: true).");
        }
        return new SkillAnswer(choice, arguments.path("comment").asText(""));
    }

    // ---- a drafted skill ----

    static ObjectNode skillReview(SkillDraft draft) {
        ObjectNode details = JSON.createObjectNode();
        details.put("draft", draft.toMarkdown());
        details.put("answerWith", "decision: save | revise | cancel; comment (for revise)");
        return details;
    }

    static SkillDecision skillAnswer(JsonNode arguments) {
        String comment = arguments.path("comment").asText("");
        Map<String, SkillDecision.Kind> kinds = Map.of("save", SkillDecision.Kind.SAVE, "revise", SkillDecision.Kind.REVISE, "cancel", SkillDecision.Kind.CANCEL);
        SkillDecision.Kind kind = kinds.get(arguments.path("decision").asText(""));
        if (kind == null) throw new IllegalArgumentException("Answer a skill review with `decision`: save, revise or cancel.");
        if (kind == SkillDecision.Kind.REVISE && comment.isBlank()) throw new IllegalArgumentException("To revise a skill say what should change in `comment`.");
        return new SkillDecision(kind, comment);
    }
}
