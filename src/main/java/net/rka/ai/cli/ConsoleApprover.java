package net.rka.ai.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.agent.Approver;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.SkillDraft;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.TrustedCommands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks on the terminal before each risky tool call: y runs it, g runs it and trusts it from now on
 * (global settings), anything else denies it.
 *
 * <p>Runs on the agent's thread while the graph is paused, so blocking on {@code readLine} is fine.
 * The default is "no": an empty line, end of input (piped stdin ran out) or anything else denies.
 * For {@code gradlew runCli} to pass your keyboard through, {@code build.gradle} sets
 * {@code standardInput = System.in}. The CLI has no saved conversations, so "always" only exists
 * at the global level; it's offered only for commands that can be trusted (no shell syntax).
 *
 * <p>Not implemented yet: showing the reason (untrusted command vs file change after web content),
 * editing the entry before trusting it (e.g. adding {@code *}), and an "allow all for this run" answer.
 */
final class ConsoleApprover implements Approver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BufferedReader in;

    ConsoleApprover() {
        this(new BufferedReader(new InputStreamReader(System.in)));
    }

    /** Reads the answers from {@code in}; tests pass a {@code StringReader}. */
    ConsoleApprover(BufferedReader in) {
        this.in = in;
    }

    /**
     * Prints the plan (for a revision with the reason first and the done steps marked) and asks:
     * {@code y} approves, {@code r <comment>} asks for a new plan taking the comment into account,
     * anything else (including no input) denies.
     */
    @Override
    public PlanDecision reviewPlan(PlanReview review) {
        System.out.println();
        System.out.println(review.toMarkdown());
        System.out.print("Approve plan? [y = yes, r <comment> = revise, N = deny] ");
        System.out.flush();
        try {
            String answer = in.readLine(); // null when there's no input (e.g. piped/closed stdin)
            answer = answer == null ? "" : answer.strip();
            if (answer.equalsIgnoreCase("y")) return new PlanDecision(PlanDecision.Kind.APPROVE, "");
            // "r" alone has nothing to revise with, so it denies like any other unknown answer
            if (answer.length() > 2 && Character.toLowerCase(answer.charAt(0)) == 'r'
                    && Character.isWhitespace(answer.charAt(1))) {
                return new PlanDecision(PlanDecision.Kind.REVISE, answer.substring(2).strip());
            }
            return PlanDecision.DENIED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Prints a skill's question with its numbered choices and reads the answer: a number picks that choice,
     * optionally followed by a comment ({@code 2 only the tests}); {@code c <comment>} picks the first choice
     * with a comment (the usual "go on, but ..."). Anything else (an empty line, a number out of range, a bare
     * {@code c}, text, end of input) dismisses the question, which finishes the skill as cancelled: a typo
     * never picks a choice for the user.
     */
    @Override
    public SkillAnswer askUser(SkillQuestion question) {
        System.out.println();
        System.out.println(question.question());
        for (int i = 0; i < question.choices().size(); i++) {
            System.out.println("  " + (i + 1) + ". " + question.choices().get(i));
        }
        System.out.print("Choose [number, then an optional comment; c <comment> = first choice with a comment; N = cancel] ");
        System.out.flush();
        try {
            String answer = in.readLine(); // null when there's no input (e.g. piped/closed stdin)
            return parseChoice(answer == null ? "" : answer.strip(), question.choices());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads "N [comment]" or "c <comment>" (see {@link #askUser}); dismissed for anything else. */
    static SkillAnswer parseChoice(String answer, List<String> choices) {
        if (choices.isEmpty()) return SkillAnswer.dismissed();
        // "\\s" (the regex whitespace class), not "\s" (Java's escape for a single space): a TAB separates too
        String[] parts = answer.split("\\s+", 2);
        String comment = parts.length > 1 ? parts[1].strip() : "";
        if (parts[0].equalsIgnoreCase("c")) {
            // "c" alone has no comment to give, so it dismisses like any other unknown answer
            return comment.isEmpty() ? SkillAnswer.dismissed() : new SkillAnswer(choices.get(0), comment);
        }
        try {
            int number = Integer.parseInt(parts[0]);
            return number >= 1 && number <= choices.size()
                    ? new SkillAnswer(choices.get(number - 1), comment) : SkillAnswer.dismissed();
        } catch (NumberFormatException e) {
            return SkillAnswer.dismissed();
        }
    }

    /**
     * Prints the drafted skill (steps, prompts, tools with those that need approval, what it replaces) and
     * asks: {@code s} saves it, {@code r <comment>} drafts it again taking the comment into account,
     * anything else (including no input) cancels without saving.
     */
    @Override
    public SkillDecision reviewSkill(SkillDraft draft) {
        System.out.println();
        System.out.println(draft.toMarkdown());
        System.out.print("Save skill? [s = save, r <comment> = revise, N = cancel] ");
        System.out.flush();
        try {
            String answer = in.readLine();
            answer = answer == null ? "" : answer.strip();
            if (answer.equalsIgnoreCase("s")) return new SkillDecision(SkillDecision.Kind.SAVE, "");
            // "r" alone has nothing to revise with, so it cancels like any other unknown answer
            if (answer.length() > 2 && Character.toLowerCase(answer.charAt(0)) == 'r'
                    && Character.isWhitespace(answer.charAt(1))) {
                return new SkillDecision(SkillDecision.Kind.REVISE, answer.substring(2).strip());
            }
            return SkillDecision.CANCELLED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
        Map<String, Boolean> decisions = new HashMap<>();
        for (ToolExecutionRequest call : calls) {
            String command = command(call);
            // What "g" would trust: the exact command, or for file tools the whole tool (tool:writeFile)
            String entry = command != null ? command.strip() : TrustedCommands.toolEntry(call.name());
            boolean trustable = TrustedCommands.canBeTrusted(entry);
            System.out.println();
            System.out.println("The agent wants to run " + call.name() + ":");
            System.out.println("  " + call.arguments());
            System.out.print(trustable ? "Allow? [y = yes, g = always (global), N = no] " : "Allow? [y/N] ");
            System.out.flush();
            try {
                String answer = in.readLine(); // null when there's no input (e.g. piped/closed stdin)
                answer = answer == null ? "" : answer.strip().toLowerCase();
                boolean always = trustable && answer.equals("g");
                decisions.put(call.id(), always || answer.equals("y"));
                if (always) trustGlobally(entry);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            System.out.println(decisions.get(call.id()) ? "Approved." : "Denied.");
        }
        return decisions;
    }

    /** Adds the entry (a command, or tool:<name>) to the global trusted commands in settings.json. */
    private static void trustGlobally(String entry) {
        Settings global = Settings.loadGlobal();
        List<String> commands = new ArrayList<>(global.trustedCommands());
        commands.add(entry);
        Settings.saveGlobal(global.withTrustedCommands(commands));
        System.out.println("Trusted from now on (" + Settings.globalFile() + "): " + entry);
    }

    private static String command(ToolExecutionRequest call) {
        try {
            JsonNode node = JSON.readTree(call.arguments()).get("command");
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
