package net.rka.ai.agent.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.skills.SkillFile.NodeSpec;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.ToolRegistry;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The node kinds every skill can use: {@code model}, {@code askUser}, {@code branch}, {@code set}
 * and {@code finish}: their fields, outcomes and variables for the validator, and how they run.
 * Also the actions of the nodes {@link SkillGraphBuilder} adds to every skill: {@code start},
 * {@code toolStep}, {@code toolApproval} and {@code stopped}.
 *
 * <h2>How the kinds run</h2>
 * <ul>
 *   <li><b>model</b>: system prompt = the skill's description + {@link #PREAMBLE}; user message = the
 *       rendered {@code prompt}. It is offered only the tools it lists (minus {@linkplain
 *       SkillValidator#isForbidden forbidden} ones). A reply with tool calls goes to {@code toolStep}
 *       and back to this node, the node's conversation (prompt, calls, results) growing each round;
 *       after {@code maxToolRounds} rounds it is asked once more without tools. The final text goes
 *       to {@code output}; with {@code format: "json"} it is parsed (code fences allowed), with one
 *       retry that names the parse error.</li>
 *   <li><b>askUser</b>: the graph pauses before it; {@code Agent} writes the user's answer into
 *       {@code skillAnswer} and resumes. A missing, blank or unknown choice stops the skill
 *       ("cancelled by the user"); it is never treated as an answer.</li>
 *   <li><b>branch</b>: evaluates the cases on the variables, changes nothing.</li>
 *   <li><b>set</b>: every value is computed from the variables as they were before the node.</li>
 *   <li><b>finish</b>: answers the skill's call in the conversation ({@code messages}) with the
 *       rendered {@code result}, as an error result when {@code error} is true.</li>
 * </ul>
 * Model replies and tool results go to the skill's transcript ({@code skillMessages}), never to the
 * conversation, which only gets the answer to the call. Values flow between nodes only through the
 * variables ({@code skillVars}, a JSON object): {@code input}, the built-ins and what nodes set.
 *
 * <h2>Fields and outcomes</h2>
 * <pre>
 *   kind     | fields                                              | outcomes
 *   ---------+-----------------------------------------------------+---------------------------
 *   model    | prompt*, tools, output, format (text/json),         | done
 *            | thinking (shown/progress)                           |
 *   askUser  | question*, choices*, output                         | one per choice
 *   branch   | cases* (list of {when, then})                       | each case's then, otherwise
 *   set      | values* (variable → value)                          | done
 *   finish   | result*, error (true/false)                         | none: ends the skill
 *   (* required; every kind also has the outcome stopped, see NodeKind.STOPPED)
 * </pre>
 *
 * <h2>Branch conditions</h2>
 * The first case whose {@code when} holds decides the outcome ({@code then}); none → {@code otherwise}.
 * A condition is one JSON object; every text in it is a template:
 * <pre>
 *   {"value": "{{answer.choice}}", "equals": "accept"}      also notEquals, contains, matches (regex)
 *   {"value": "{{notes}}", "isEmpty": true}                 true: empty text; false: not empty
 *   {"value": "{{rounds}}", "atLeast": 3}                   number comparison, also atMost
 *   {"count": "{{items}}", "atLeast": 1}                    size of a list, also atMost, equals
 *   {"and": [ ... ]}, {"or": [ ... ]}                       combine conditions
 * </pre>
 * When it runs: texts compare as rendered ({@code equals} exactly, {@code contains} as a part,
 * {@code matches} when the pattern is found anywhere in the value); {@code isEmpty} means blank; a
 * value that isn't a number fails {@code atLeast}/{@code atMost}, and a {@code count} of something
 * that isn't a list fails every comparison ("" counts as an empty list).
 *
 * <h2>Set values</h2>
 * <pre>
 *   "values": { "title": "Notes for {{input.since}}",   a template
 *               "rounds": {"increment": 1},             add to a number (missing counts as 0)
 *               "drafts": {"append": "{{notes}}"} }     add to a list (missing counts as empty)
 * </pre>
 * A value that isn't a number counts as 0 for {@code increment}; one that isn't a list becomes the
 * first entry of the list {@code append} makes.
 */
public final class GenericKinds {

    /** Variables every skill has: the call's arguments as text, the trusted folder, the environment summary. */
    public static final Set<String> BUILT_IN_VARIABLES = Set.of("goal", "folder", "environment", "knowledge");
    /** The variable holding the inputs: {@code {{input.<name>}}}. */
    public static final String INPUT = "input";
    /** The outcome of a {@code branch} when no case matches. */
    public static final String OTHERWISE = "otherwise";
    /** The kind that pauses to ask the user (it {@linkplain NodeKind#pausesBefore pauses before}). */
    public static final String ASK_USER = "askUser";
    private static final String DONE = "done";

    /** Follows the skill's description in every {@code model} node's system prompt. */
    static final String PREAMBLE = "You are carrying out one step of this skill, a fixed procedure the app runs for the user."
            + " The user message is the step's task: do exactly that and answer with the result only."
            + " You can't ask the user anything here; if something is missing, say so in your answer."
            + " Use the tools you are offered when the task needs them; only you see their results.";
    /** The answer to the other calls of the reply that started a skill. */
    static final String NOT_RUN = "Not run: a skill started; ask again afterwards.";
    /** The stop reason of a dismissed {@code askUser} question. */
    static final String CANCELLED = "cancelled by the user";
    /** Marks an {@code askUser} answer a node has used, so it can't answer another question. */
    private static final String USED = "used";
    // Prefixes of skillStopReason for an exception from a model call or a tool run
    private static final String MODEL_ERROR = "model error";
    private static final String TOOL_ERROR = "tool error";
    // ```json ... ``` around a JSON answer, which models often add
    private static final Pattern FENCE = Pattern.compile("^```[A-Za-z]*\\s*(.*?)\\s*```$", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();

    // Variable and outcome names: identifiers, so {{name}} and "on" keys are easy to read and type
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> COMPARISONS = Set.of("equals", "notEquals", "contains", "matches", "isEmpty", "atLeast", "atMost");
    private static final Set<String> COUNT_COMPARISONS = Set.of("equals", "atLeast", "atMost");

    private GenericKinds() {
    }

    /** The generic kinds by name. */
    public static Map<String, NodeKind> all() {
        Map<String, NodeKind> kinds = new LinkedHashMap<>();
        for (NodeKind kind : List.of(new Model(), new AskUser(), new Branch(), new SetValues(), new Finish())) {
            kinds.put(kind.name(), kind);
        }
        return kinds;
    }

    // --- The nodes every skill subgraph has (SkillGraphBuilder adds them) ---

    /**
     * The {@code start} node: finds the call that started the skill (named {@link SkillFile#toolName()})
     * among the conversation's open calls, answers the other calls of that reply with {@link #NOT_RUN}
     * (or the {@linkplain NodeKind#otherCallsAnswer own answer} of the first node's kind), resets the
     * {@code skill*} keys and sets the variables: {@code input} (every declared input, ""
     * when not passed), {@code goal} (the arguments as text), {@code folder} (the trusted folder) and,
     * only when a node uses it, {@code environment} (the cached environment summary, probed when stale).
     * It is the only node that looks for the call: later nodes (e.g. {@code coding.environment}) find it
     * by {@code skillCallId}.
     */
    static NodeActionWithConfig<State> start(SkillRuntime runtime) {
        SkillFile skill = runtime.skill();
        String notRun = skill.nodes().stream().filter(node -> node.id().equals(skill.start()))
                .map(node -> runtime.kinds().get(node.kind())).filter(Objects::nonNull)
                .flatMap(kind -> kind.otherCallsAnswer().stream())
                .findFirst().orElse(NOT_RUN);
        // Probing can take seconds, so only for a skill that uses it (an unknown kind is the builder's error)
        boolean needsEnvironment = skill.nodes().stream()
                .filter(node -> runtime.kinds().containsKey(node.kind()))
                .anyMatch(node -> runtime.kinds().get(node.kind()).reads(node).stream()
                        .anyMatch(path -> path.equals("environment") || path.startsWith("environment.")));
        return (state, config) -> {
            List<ToolExecutionRequest> calls = state.unansweredToolCalls();
            ToolExecutionRequest start = calls.stream().filter(c -> skill.toolName().equals(c.name())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The skill " + skill.name() + " started without a "
                            + skill.toolName() + " call"));
            // Like a coding job: the conversation keeps exactly one open call, the skill's, answered by its finish
            List<ChatMessage> refused = calls.stream().filter(c -> c != start).<ChatMessage>map(c -> error(c, notRun)).toList();
            Map<String, Object> arguments = arguments(start);
            Map<String, Object> inputs = new LinkedHashMap<>();
            for (SkillFile.Input input : skill.inputs()) {
                Object value = arguments.get(input.name());
                inputs.put(input.name(), value == null ? "" : value instanceof String text ? text : json(value)); // optional inputs default to ""
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put(INPUT, inputs);
            vars.put("goal", goal(start, arguments));
            vars.put("folder", state.workFolder().toAbsolutePath().toString());
            if (needsEnvironment) vars.put("environment", environmentSummary());
            // What the client knows about this kind of work (recipes, earlier failures); "" when nothing matches
            vars.put("knowledge", net.rka.ai.agent.KnowledgeBook.shared().forGoal(String.valueOf(vars.get("goal")), 4_500));

            Map<String, Object> updates = new HashMap<>();
            updates.put(State.MESSAGES, refused);
            updates.put(State.SKILL_NAME, skill.name());
            updates.put(State.SKILL_CALL_ID, start.id());
            // The run's transcript starts after any earlier skill run of this turn (the channel only appends)
            updates.put(State.SKILL_START, state.allSkillMessages().size());
            updates.put(State.SKILL_VARS, json(vars));
            // Reset what an earlier run of this turn may have left; "" and 0 mean "not set"
            for (String key : List.of(State.SKILL_NODE, State.SKILL_ANSWER, State.SKILL_RESULT, State.SKILL_STOP_REASON)) {
                updates.put(key, "");
            }
            updates.put(State.SKILL_STEPS, 0);
            updates.put(State.SKILL_TOOL_ROUNDS, 0);
            return updates;
        };
    }

    /**
     * The {@code toolStep} node: the open calls of the current node's reply ({@code skillNode}). A call
     * of a tool the node doesn't list (or a forbidden one) is answered with an error and never runs.
     * If any other call needs the user's approval (the main graph's rules: {@link State#needsApproval},
     * with web content of the turn and of the skills' transcripts), none runs here: the router sends the
     * batch to {@code toolApproval}, so the user sees the guarded call before anything of the batch runs.
     * Otherwise they run (safe or trusted). Each visit is one tool round of the node.
     */
    static NodeActionWithConfig<State> toolStep(SkillRuntime runtime) {
        Map<String, NodeSpec> nodes = nodesById(runtime.skill());
        ToolRegistry tools = runtime.toolCalls().tools();
        return (state, config) -> {
            Calls calls = Calls.of(state, nodes.get(state.skillNode()), tools);
            Map<String, Object> updates = new HashMap<>();
            updates.put(State.SKILL_TOOL_ROUNDS, state.skillToolRounds() + 1);
            if (calls.allowed().stream().anyMatch(call -> state.needsApproval(tools, call))) {
                updates.put(State.SKILL_MESSAGES, calls.refused()); // the rest waits for the approval pause
                return updates;
            }
            updates.putAll(run(runtime, state, calls.allowed(), calls.refused()));
            return updates;
        };
    }

    /**
     * The {@code toolApproval} node: the graph pauses before it, {@code Agent} asks the user and writes
     * the decisions into {@link State#APPROVALS} (as for the main graph's approval). Guarded calls that
     * weren't approved get a "denied" result (missing counts as no: fail closed); the approved ones run,
     * with the safe and trusted calls of the same reply. Unlisted calls are refused again, so an approval
     * can never let one run.
     */
    static NodeActionWithConfig<State> toolApproval(SkillRuntime runtime) {
        Map<String, NodeSpec> nodes = nodesById(runtime.skill());
        ToolRegistry tools = runtime.toolCalls().tools();
        return (state, config) -> {
            Calls calls = Calls.of(state, nodes.get(state.skillNode()), tools);
            Map<String, Boolean> approvals = state.approvals();
            List<ChatMessage> answered = new ArrayList<>(calls.refused());
            List<ToolExecutionRequest> approved = new ArrayList<>();
            for (ToolExecutionRequest call : calls.allowed()) {
                if (state.needsApproval(tools, call) && !approvals.getOrDefault(call.id(), false)) {
                    // Telling the model explicitly not to retry stops it from asking again in a loop
                    answered.add(error(call, "Denied: the user did not approve this call. Don't retry it unless the user asks."));
                } else {
                    approved.add(call);
                }
            }
            return run(runtime, state, approved, answered);
        };
    }

    /**
     * The automatic {@code stopped} finish: answers the skill's call with an error result saying why it
     * stopped: {@code skillStopReason}, or too many steps when the router sent it here for {@code maxSteps}.
     */
    static NodeActionWithConfig<State> stopped(SkillRuntime runtime) {
        SkillFile skill = runtime.skill();
        return (state, config) -> {
            String error = state.skillStopReason();
            // Over the limit the router comes here even when the node maps stopped: say both
            String reason = state.skillSteps() >= skill.limits().maxSteps()
                    ? "too many steps (the skill may take " + skill.limits().maxSteps() + ")"
                            + (error.isBlank() ? "" : "; the last node stopped with " + error)
                    : !error.isBlank() ? error
                    : "the skill could not go on";
            return answer(skill, state, "Stopped: " + (reason.endsWith(".") ? reason : reason + "."), true);
        };
    }

    /** Whether the user refused the call (see the approval step). */
    static boolean isDenial(ToolExecutionResultMessage result) {
        return result.text() != null && result.text().startsWith("Denied: the user did not approve this call");
    }

    /**
     * Whether the results at the end of the transcript (the last round) are all notes that the call repeated an earlier one, or all
     * denials by the user: the model then answers without tools instead of trying another command the user will be asked about.
     */
    static boolean repeatedOnly(List<ChatMessage> messages) {
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && messages.get(i) instanceof ToolExecutionResultMessage result; i--) {
            if (!net.rka.ai.agent.ToolCallRunner.isRepeat(result) && !isDenial(result)) return false;
            count++;
        }
        return count > 0;
    }

    /**
     * Whether the node's current visit goes on: it is the node that ran last and its tool calls were
     * just answered (by {@code toolStep} or {@code toolApproval}). Anything else is a new visit, which
     * counts against {@code maxSteps} and starts a new conversation for a {@code model} node.
     */
    static boolean continuesVisit(State state, String nodeId) {
        List<ChatMessage> messages = state.skillMessages();
        // A stop reason means the node (or its tool step) stopped: coming back is its stopped route, a new
        // visit, which counts a step and clears the reason (else a self-wired stopped could loop forever)
        return state.skillStopReason().isBlank() && nodeId.equals(state.skillNode()) && !messages.isEmpty()
                && messages.getLast() instanceof ToolExecutionResultMessage;
    }

    /**
     * The skill's variables: {@code input}, the built-ins and what its nodes set, as plain Java values
     * (strings, numbers, lists, maps). A copy: changing it changes nothing in the state. Also what
     * {@code Agent} renders an {@code askUser} question with.
     */
    public static Map<String, Object> vars(State state) {
        String json = state.skillVars();
        if (json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("skillVars is not a JSON object", e); // only the skill nodes write it
        }
    }

    /** The {@code skillVars} update that sets one variable. */
    public static String withVar(State state, String name, Object value) {
        Map<String, Object> vars = vars(state);
        vars.put(name, value);
        return json(vars);
    }

    /**
     * The calls of a tool round, split: {@code allowed} are open calls of tools the node lists (and that
     * aren't forbidden); every other call is in {@code refused} with its error result.
     */
    private record Calls(List<ToolExecutionRequest> allowed, List<ChatMessage> refused) {
        static Calls of(State state, NodeSpec node, ToolRegistry tools) {
            Set<String> listed = node == null ? Set.of() : listedTools(node, tools);
            List<ToolExecutionRequest> allowed = new ArrayList<>();
            List<ChatMessage> refused = new ArrayList<>();
            for (ToolExecutionRequest call : state.skillUnansweredToolCalls()) {
                if (listed.contains(call.name())) {
                    allowed.add(call);
                } else {
                    refused.add(error(call, "Not run: the tool " + call.name() + " is not available in this step of the skill"
                            + (listed.isEmpty() ? "." : "; use only " + String.join(", ", listed) + ".")));
                }
            }
            return new Calls(allowed, refused);
        }
    }

    /** The tools a node may offer and run: those it lists that exist and aren't forbidden in skills. */
    private static Set<String> listedTools(NodeSpec node, ToolRegistry tools) {
        Set<String> listed = new LinkedHashSet<>();
        if (node.fields().get("tools") instanceof List<?> list) {
            for (Object tool : list) {
                // Never a skill's tool (whatever its name): skills don't start skills
                if (tool instanceof String name && !SkillValidator.isForbidden(name) && !tools.isSkillTool(name)
                        && tools.names().contains(name)) {
                    listed.add(name);
                }
            }
        }
        return listed;
    }

    /** Runs the calls; the update holds {@code answered} plus their results (or the stop reason if running failed). */
    private static Map<String, Object> run(SkillRuntime runtime, State state, List<ToolExecutionRequest> calls,
                                           List<ChatMessage> answered) {
        Map<String, Object> updates = new HashMap<>();
        List<ChatMessage> messages = new ArrayList<>(answered);
        try {
            if (!calls.isEmpty()) {
                Map<String, Object> ran = runtime.toolCalls().runInSkill(state, calls);
                @SuppressWarnings("unchecked")
                List<ChatMessage> results = (List<ChatMessage>) ran.get(State.SKILL_MESSAGES);
                messages.addAll(results);
                updates.putAll(ran); // the facts, if a listed memory tool changed them
            }
        } catch (RuntimeException e) {
            // The registry turns a tool's own exceptions into error results; this is anything else going wrong
            updates.put(State.SKILL_STOP_REASON, reason(TOOL_ERROR, e));
        }
        updates.put(State.SKILL_MESSAGES, messages);
        return updates;
    }

    /** The update that ends the skill: the answer to its call in the conversation, and {@code skillResult}. */
    private static Map<String, Object> answer(SkillFile skill, State state, String text, boolean error) {
        ToolExecutionResultMessage answer = ToolExecutionResultMessage.builder()
                .id(state.skillCallId())
                .toolName(skill.toolName())
                .text(text.isEmpty() ? "(no result)" : text)
                .isError(error)
                .build();
        return Map.of(State.MESSAGES, List.of(answer), State.SKILL_RESULT, text);
    }

    /** The tools that change a file: a visit that called none of them has only read. */
    static final Set<String> CHANGING_TOOLS = Set.of("editSection", "patchFile", "writeFile");

    /** Whether the visit made a call that changes a file. */
    static boolean changedSomething(List<ChatMessage> visit) {
        return visit.stream().anyMatch(m -> m instanceof AiMessage ai
                && ai.toolExecutionRequests().stream().anyMatch(call -> CHANGING_TOOLS.contains(call.name())));
    }

    /** The current visit's conversation of a {@code model} node: from its prompt (the last user message) on. */
    private static List<ChatMessage> visitMessages(State state) {
        List<ChatMessage> messages = state.skillMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) return messages.subList(i, messages.size());
        }
        return messages;
    }

    private static Map<String, NodeSpec> nodesById(SkillFile skill) {
        Map<String, NodeSpec> nodes = new HashMap<>();
        skill.nodes().forEach(node -> nodes.put(node.id(), node));
        return nodes;
    }

    /** The call's arguments as a map; empty when they aren't a JSON object (malformed arguments from the model). */
    private static Map<String, Object> arguments(ToolExecutionRequest call) {
        try {
            Object value = JSON.readValue(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(), Object.class);
            if (value instanceof Map<?, ?> map) return toStringKeys(map);
        } catch (JsonProcessingException e) {
            // fall through: no arguments
        }
        return Map.of();
    }

    /** {@code {{goal}}}: the arguments as "name: value" lines, or the raw text when they aren't a JSON object. */
    private static String goal(ToolExecutionRequest call, Map<String, Object> arguments) {
        if (arguments.isEmpty()) {
            String raw = Objects.requireNonNullElse(call.arguments(), "").strip();
            return raw.equals("{}") ? "" : raw;
        }
        List<String> lines = new ArrayList<>();
        arguments.forEach((name, value) -> lines.add(name + ": " + (value instanceof String text ? text : json(value))));
        return String.join("\n", lines);
    }

    /** The cached or freshly probed environment; a failure must not lose the skill, so it becomes the text. */
    private static String environmentSummary() {
        try {
            return EnvironmentInfo.fromSettings().summary();
        } catch (RuntimeException e) {
            return "Environment check failed (" + e.getMessage() + ").";
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // plain Java values from JSON always serialize
        }
    }

    /** A JSON answer: the text, without a code fence around it. */
    private static Object parseJson(String text) {
        String body = text.strip();
        Matcher fence = FENCE.matcher(body);
        if (fence.matches()) body = fence.group(1);
        try {
            return JSON.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage(), e);
        }
    }

    /** "model error: &lt;message&gt;" on one line (the exception's class when it has no message). */
    private static String reason(String what, RuntimeException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return what + ": " + message.strip().replaceAll("\\s+", " ");
    }

    private static String textOf(AiMessage message) {
        return Objects.requireNonNullElse(message.text(), ""); // null when the reply only has tool calls
    }

    /** A result flagged {@code isError}, with the call's id so the model can pair them. */
    private static ToolExecutionResultMessage error(ToolExecutionRequest call, String message) {
        return ToolExecutionResultMessage.builder()
                .id(call.id())
                .toolName(call.name())
                .text(message)
                .isError(true)
                .build();
    }

    /**
     * What the generic kinds share: the list of known fields (anything else is reported, it is
     * usually a typo) and small checks for text, choices and variable names.
     */
    private abstract static class Spec implements NodeKind {
        private final String name;
        private final Set<String> required;
        private final Set<String> optional;

        Spec(String name, Set<String> required, Set<String> optional) {
            this.name = name;
            this.required = required;
            this.optional = optional;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> check(NodeSpec node, SkillContext context) {
            List<String> errors = new ArrayList<>();
            Set<String> known = new TreeSet<>(required);
            known.addAll(optional);
            for (String field : node.fields().keySet()) {
                if (!known.contains(field)) {
                    errors.add(where(node) + "unknown field `" + field + "`; a " + name + " node has " + String.join(", ", known) + ".");
                }
            }
            for (String field : required) {
                if (node.fields().get(field) == null) errors.add(where(node) + "`" + field + "` is required.");
            }
            checkFields(node, errors);
            return errors;
        }

        /** The kind's own checks of present fields; required ones were already reported when missing. */
        abstract void checkFields(NodeSpec node, List<String> errors);

        @Override
        public Set<String> writes(NodeSpec node) {
            return node.fields().get("output") instanceof String output ? Set.of(output) : Set.of();
        }

        static String where(NodeSpec node) {
            return "node `" + node.id() + "`: ";
        }

        static void text(NodeSpec node, String field, List<String> errors) {
            Object value = node.fields().get(field);
            if (value != null && !(value instanceof String)) errors.add(where(node) + "`" + field + "` must be text.");
        }

        static void oneOf(NodeSpec node, String field, Set<String> allowed, List<String> errors) {
            Object value = node.fields().get(field);
            if (value != null && !allowed.contains(value)) {
                errors.add(where(node) + "`" + field + "` must be one of " + String.join(", ", new TreeSet<>(allowed))
                        + ", not `" + value + "`.");
            }
        }

        /** A variable the node writes: an identifier that doesn't hide an input or a built-in. */
        static void variable(NodeSpec node, String field, Object value, List<String> errors) {
            if (value == null) return;
            if (!(value instanceof String name) || !NAME.matcher(name).matches()) {
                errors.add(where(node) + "`" + field + "` must be a variable name (letters, digits, _), not `" + value + "`.");
            } else if (BUILT_IN_VARIABLES.contains(name) || name.equals(INPUT)) {
                errors.add(where(node) + "`" + field + "` can't be `" + name + "`: that variable is built in.");
            }
        }

        static Set<String> templateReads(NodeSpec node, String field) {
            return node.fields().get(field) instanceof String template ? Templates.references(template) : Set.of();
        }
    }

    /** Asks the model; with {@code tools}, a ReAct loop. The answer goes to {@code output}. */
    private static final class Model extends Spec {
        Model() {
            super("model", Set.of("prompt"), Set.of("tools", "output", "format", "thinking", "maxTokens", "effort", "history", "editBy"));
        }

        @Override
        void checkFields(NodeSpec node, List<String> errors) {
            text(node, "prompt", errors);
            // the tool names themselves are checked by SkillValidator for every node, whatever its kind
            Object tools = node.fields().get("tools");
            if (tools != null && !(tools instanceof List<?> list && list.stream().allMatch(t -> t instanceof String))) {
                errors.add(where(node) + "`tools` must be a list of tool names.");
            }
            variable(node, "output", node.fields().get("output"), errors);
            oneOf(node, "format", Set.of("text", "json"), errors);
            oneOf(node, "thinking", Set.of("shown", "progress"), errors);
            oneOf(node, "effort", net.rka.ai.agent.Effort.LEVELS, errors);
            Object history = node.fields().get("history");
            if (history != null && !(history instanceof Boolean)) errors.add(where(node) + "`history` must be true or false.");
            Object maxTokens = node.fields().get("maxTokens");
            if (maxTokens != null && !(maxTokens instanceof Integer n && n >= 50 && n <= 32_000)) {
                errors.add(where(node) + "`maxTokens` must be a whole number from 50 to 32000.");
            }
            Object editBy = node.fields().get("editBy");
            if (editBy != null && !(editBy instanceof Integer n && n >= 2 && n <= 40)) {
                errors.add(where(node) + "`editBy` must be a whole number from 2 to 40.");
            }
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            return Set.of(DONE);
        }

        @Override
        public Set<String> reads(NodeSpec node) {
            return templateReads(node, "prompt");
        }

        /**
         * One model turn of the node's visit. A new visit adds the rendered prompt to the transcript;
         * a visit that goes on after its tool step sends the conversation so far. A reply with tool
         * calls leaves them open (the builder's router sends them to {@code toolStep}); a text reply
         * is the output.
         */
        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            String prompt = (String) node.fields().get("prompt");
            String output = (String) node.fields().get("output"); // null: the answer is only in the transcript
            boolean json = "json".equals(node.fields().get("format"));
            Set<String> tools = listedTools(node, runtime.toolCalls().tools());
            int maxRounds = runtime.skill().limits().maxToolRounds();
            Integer maxTokens = node.fields().get("maxTokens") instanceof Integer n ? n : null; // null: the model's own limit
            Integer editBy = node.fields().get("editBy") instanceof Integer n ? n : null; // null: no nudge to change something
            boolean history = Boolean.TRUE.equals(node.fields().get("history")); // the conversation before the skill, in front of the prompt
            String system = runtime.skill().description() + "\n\n" + PREAMBLE;
            return (state, config) -> {
                boolean continuing = continuesVisit(state, node.id());
                int rounds = continuing ? state.skillToolRounds() : 0;
                // What this turn adds to the transcript: the prompt (on a new visit), then the reply
                List<ChatMessage> added = new ArrayList<>();
                if (!continuing) added.add(UserMessage.from(Templates.render(prompt, vars(state))));
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(SystemMessage.from(system));
                if (history) messages.addAll(net.rka.ai.agent.JobHistory.of(state.messages()));
                if (continuing) messages.addAll(net.rka.ai.agent.RollingResults.apply(net.rka.ai.agent.ContextGroom.apply(visitMessages(state)))); // the prompt, the calls and their results
                messages.addAll(added);

                Map<String, Object> updates = new HashMap<>();
                updates.put(State.SKILL_TOOL_ROUNDS, rounds);
                // A round whose calls all repeated earlier ones is a loop: the model is told once and then asked to answer, not 40 rounds later
                boolean looped = continuing && repeatedOnly(state.skillMessages());
                boolean offerTools = !tools.isEmpty() && rounds < maxRounds && !looped;
                ChatRequest.Builder request = ChatRequest.builder();
                if (node.fields().get("effort") instanceof String effort) request.parameters(net.rka.ai.agent.Effort.parameters(effort));
                if (maxTokens != null) request.maxOutputTokens(maxTokens); // a step that writes notes has no use for a runaway reply
                List<ChatMessage> sent = new ArrayList<>(messages);
                // A model that only reads: after editBy rounds without a change it is told to decide (only this request needs it, so it is
                // not stored). A step once read for 21 rounds, undid its one edit with git and ran 37 minutes without a result.
                if (editBy != null && continuing && rounds >= editBy && offerTools && !changedSomething(visitMessages(state))) {
                    sent.add(UserMessage.from("You have used " + rounds + " rounds and changed nothing yet. Do not read anything else: decide now what to change"
                            + " and make the change with your next call (patchFile, editSection or writeFile). If you cannot say what to change, answer"
                            + " with what you need to know."));
                }
                if (offerTools) {
                    // Only the listed tools, so the model isn't offered calls that would be refused
                    request.toolSpecifications(runtime.toolCalls().tools().specifications(tools::contains));
                } else if (!tools.isEmpty()) {
                    // The tool definitions stay in the request, with the choice "none": they are the start of the prompt, and a prompt
                    // without them looks new to the model server, which then reads the whole conversation again (89 s at 12,000 tokens)
                    request.toolSpecifications(runtime.toolCalls().tools().specifications(tools::contains));
                    request.toolChoice(dev.langchain4j.model.chat.request.ToolChoice.NONE);
                    // Only this request needs it, so it isn't stored in the transcript
                    sent.add(UserMessage.from(looped
                            ? "Your last tool calls repeated earlier ones and gave nothing new. Answer now without tool calls:"
                            + " say what you found, or what blocks you."
                            : "You have used " + maxRounds + " rounds of tool calls in this step. Answer now without tool calls."));
                }
                AiMessage reply;
                try {
                    reply = withoutCalls(runtime.model().chat(request.messages(sent).build()).aiMessage(), offerTools);
                } catch (RuntimeException e) {
                    // The skill answers its call with the error instead of the run failing without a record
                    updates.put(State.SKILL_MESSAGES, added);
                    updates.put(State.SKILL_STOP_REASON, reason(MODEL_ERROR, e));
                    return updates;
                }
                added.add(reply);
                updates.put(State.SKILL_MESSAGES, added); // the same list: what a JSON retry adds below joins it
                if (reply.hasToolExecutionRequests()) return updates; // on to the tool step, then back here

                Object value = textOf(reply);
                if (json) {
                    try {
                        value = parseJson(textOf(reply));
                    } catch (IllegalArgumentException first) {
                        // One retry that says what was wrong; its messages join the transcript too
                        UserMessage retry = UserMessage.from("That was not valid JSON (" + first.getMessage()
                                + "). Answer with the JSON only.");
                        List<ChatMessage> again = new ArrayList<>(messages);
                        again.add(reply);
                        again.add(retry);
                        try {
                            AiMessage second = withoutCalls(runtime.model().chat(ChatRequest.builder().messages(again).build())
                                    .aiMessage(), false);
                            added.add(retry);
                            added.add(second);
                            value = parseJson(textOf(second));
                        } catch (IllegalArgumentException e) {
                            updates.put(State.SKILL_STOP_REASON, "the answer of " + node.id() + " is not valid JSON: "
                                    + e.getMessage().replaceAll("\\s+", " "));
                            return updates;
                        } catch (RuntimeException e) {
                            updates.put(State.SKILL_STOP_REASON, reason(MODEL_ERROR, e));
                            return updates;
                        }
                    }
                }
                if (output != null) updates.put(State.SKILL_VARS, withVar(state, output, value));
                return updates;
            };
        }

        /** The reply, with its tool calls dropped when no tools were offered, so no call stays open. */
        private static AiMessage withoutCalls(AiMessage reply, boolean toolsOffered) {
            if (toolsOffered || !reply.hasToolExecutionRequests()) return reply;
            return AiMessage.from(textOf(reply).isBlank() ? "(no answer)" : textOf(reply));
        }

        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            return DONE;
        }
    }

    /** Pauses and asks the user; each choice is an outcome, the answer {@code {choice, comment}} goes to {@code output}. */
    private static final class AskUser extends Spec {
        AskUser() {
            super("askUser", Set.of("question", "choices"), Set.of("output"));
        }

        /** The graph pauses before it, so {@code Agent} can ask the question and store the answer. */
        @Override
        public boolean pausesBefore() {
            return true;
        }

        @Override
        void checkFields(NodeSpec node, List<String> errors) {
            text(node, "question", errors);
            variable(node, "output", node.fields().get("output"), errors);
            Object choices = node.fields().get("choices");
            if (choices == null) return;
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                errors.add(where(node) + "`choices` must be a list of at least one choice.");
                return;
            }
            Set<Object> seen = new LinkedHashSet<>();
            for (Object choice : list) {
                if (!(choice instanceof String c) || c.isBlank()) {
                    errors.add(where(node) + "every entry of `choices` must be non-blank text.");
                } else if (c.equals(STOPPED)) {
                    errors.add(where(node) + "`stopped` can't be a choice: it is the outcome for errors.");
                } else if (!seen.add(c)) {
                    errors.add(where(node) + "the choice `" + c + "` is listed more than once.");
                }
            }
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            Set<String> outcomes = new LinkedHashSet<>();
            if (node.fields().get("choices") instanceof List<?> list) {
                for (Object choice : list) if (choice instanceof String c && !c.isBlank()) outcomes.add(c);
            }
            return outcomes;
        }

        @Override
        public Set<String> reads(NodeSpec node) {
            return templateReads(node, "question");
        }

        /**
         * Runs after the pause, once {@code Agent} stored the answer: a valid choice goes to {@code output}
         * as {@code {choice, comment}}; no answer (dismissed, a failing approver) or a choice that isn't
         * offered stops the skill, so it is never taken for an answer. An answer counts only for the node
         * it names and only once: one for another node, or one this node already used (an Agent that
         * resumed without writing a new answer), counts as dismissed. Using it marks it {@code used}
         * instead of blanking it, because the router after this node still reads the choice.
         */
        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            Set<String> choices = outcomes(node);
            String output = (String) node.fields().get("output");
            return (state, config) -> {
                Map<String, Object> answer = answer(state);
                String choice = answer.get("choice") instanceof String c ? c : "";
                boolean fresh = node.id().equals(answer.get("node")) && !Boolean.TRUE.equals(answer.get(USED));
                if (choice.isBlank() || !fresh) return Map.of(State.SKILL_STOP_REASON, CANCELLED);
                if (!choices.contains(choice)) {
                    return Map.of(State.SKILL_STOP_REASON, "the answer `" + choice + "` to " + node.id()
                            + " is not one of its choices (" + String.join(", ", choices) + ")");
                }
                Map<String, Object> used = new LinkedHashMap<>(answer);
                used.put(USED, true);
                Map<String, Object> updates = new HashMap<>();
                updates.put(State.SKILL_ANSWER, json(used));
                if (output != null) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("choice", choice);
                    value.put("comment", answer.get("comment") instanceof String comment ? comment : "");
                    updates.put(State.SKILL_VARS, withVar(state, output, value));
                }
                return updates;
            };
        }

        /** The choice; the action already stopped the skill for anything that isn't one. */
        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            return answer(state).get("choice") instanceof String choice ? choice : STOPPED;
        }

        /**
         * {@code skillAnswer} as a map ({@code {node, choice, comment}}, plus {@code used} once a node used
         * it); empty when there is none or it isn't a JSON object.
         */
        private static Map<String, Object> answer(State state) {
            try {
                return parseJson(state.skillAnswer()) instanceof Map<?, ?> map ? toStringKeys(map) : Map.of();
            } catch (IllegalArgumentException e) {
                return Map.of(); // "" (dismissed) or not JSON: no answer
            }
        }
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    /** Routes on values: the first case whose {@code when} holds gives the outcome, else {@code otherwise}. */
    private static final class Branch extends Spec {
        Branch() {
            super("branch", Set.of("cases"), Set.of());
        }

        @Override
        void checkFields(NodeSpec node, List<String> errors) {
            Object cases = node.fields().get("cases");
            if (cases == null) return;
            if (!(cases instanceof List<?> list) || list.isEmpty()) {
                errors.add(where(node) + "`cases` must be a list of at least one {\"when\": ..., \"then\": ...}.");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                String at = "cases[" + i + "]";
                if (!(list.get(i) instanceof Map<?, ?> entry) || !entry.containsKey("when") || !entry.containsKey("then")
                        || entry.size() != 2) {
                    errors.add(where(node) + "`" + at + "` must be {\"when\": <condition>, \"then\": <outcome>}.");
                    continue;
                }
                if (!(entry.get("then") instanceof String then) || !NAME.matcher(then).matches()) {
                    errors.add(where(node) + "`" + at + ".then` must be an outcome name (letters, digits, _).");
                } else if (then.equals(OTHERWISE) || then.equals(STOPPED)) {
                    errors.add(where(node) + "`" + at + ".then` can't be `" + then + "`: that outcome is built in.");
                }
                condition(node, at + ".when", entry.get("when"), errors);
            }
        }

        private static void condition(NodeSpec node, String at, Object condition, List<String> errors) {
            if (!(condition instanceof Map<?, ?> map)) {
                errors.add(where(node) + "`" + at + "` must be a condition object.");
                return;
            }
            if (map.containsKey("and") || map.containsKey("or")) {
                Object parts = map.containsKey("and") ? map.get("and") : map.get("or");
                if (map.size() != 1 || !(parts instanceof List<?> list) || list.isEmpty()) {
                    errors.add(where(node) + "`" + at + "`: `and`/`or` must stand alone and hold a list of conditions.");
                    return;
                }
                for (int i = 0; i < list.size(); i++) condition(node, at + "[" + i + "]", list.get(i), errors);
                return;
            }
            boolean count = map.containsKey("count");
            String subject = count ? "count" : "value";
            Set<String> allowed = count ? COUNT_COMPARISONS : COMPARISONS;
            List<Object> comparisons = map.keySet().stream().filter(k -> !k.equals(subject)).map(k -> (Object) k).toList();
            if (!(map.get(subject) instanceof String) || comparisons.size() != 1 || !allowed.contains(comparisons.get(0))) {
                errors.add(where(node) + "`" + at + "` must be {\"" + subject + "\": <template>, <one of "
                        + String.join(", ", new TreeSet<>(allowed)) + ">: ...}.");
                return;
            }
            String op = (String) comparisons.get(0);
            Object operand = map.get(op);
            switch (op) {
                case "isEmpty" -> {
                    if (!(operand instanceof Boolean)) errors.add(where(node) + "`" + at + ".isEmpty` must be true or false.");
                }
                case "atLeast", "atMost" -> {
                    if (!(operand instanceof Number)) errors.add(where(node) + "`" + at + "." + op + "` must be a number.");
                }
                default -> {
                    if (count) {
                        if (!(operand instanceof Integer)) errors.add(where(node) + "`" + at + ".equals` must be a whole number.");
                    } else if (!(operand instanceof String text)) {
                        errors.add(where(node) + "`" + at + "." + op + "` must be text.");
                    } else if (op.equals("matches") && Templates.references(text).isEmpty()) {
                        // a pattern with placeholders can only be compiled when it runs
                        try {
                            Pattern.compile(text);
                        } catch (PatternSyntaxException e) {
                            errors.add(where(node) + "`" + at + ".matches` is not a valid regular expression: " + e.getDescription() + ".");
                        }
                    }
                }
            }
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            Set<String> outcomes = new LinkedHashSet<>();
            if (node.fields().get("cases") instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof Map<?, ?> map && map.get("then") instanceof String then) outcomes.add(then);
                }
            }
            outcomes.add(OTHERWISE);
            return outcomes;
        }

        @Override
        public Set<String> writes(NodeSpec node) {
            return Set.of();
        }

        @Override
        public Set<String> reads(NodeSpec node) {
            Set<String> reads = new LinkedHashSet<>();
            collectTemplates(node.fields().get("cases"), reads);
            return reads;
        }

        /** Every text anywhere in the cases is a template (then values hold no braces, so they add nothing). */
        private static void collectTemplates(Object value, Set<String> reads) {
            if (value instanceof String text) reads.addAll(Templates.references(text));
            else if (value instanceof List<?> list) list.forEach(item -> collectTemplates(item, reads));
            else if (value instanceof Map<?, ?> map) map.values().forEach(item -> collectTemplates(item, reads));
        }

        /** A branch only decides; the router evaluates the cases ({@link #route}). */
        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            return (state, config) -> Map.of();
        }

        /** The {@code then} of the first case whose condition holds, else {@link #OTHERWISE}. */
        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            Map<String, Object> vars = vars(state);
            for (Object entry : (List<?>) node.fields().get("cases")) {
                Map<?, ?> branchCase = (Map<?, ?>) entry;
                if (holds(branchCase.get("when"), vars)) return (String) branchCase.get("then");
            }
            return OTHERWISE;
        }

        /** Evaluates one condition of the shape the validator checked (see the class comment). */
        private static boolean holds(Object condition, Map<String, Object> vars) {
            Map<?, ?> map = (Map<?, ?>) condition;
            if (map.get("and") instanceof List<?> parts) return parts.stream().allMatch(part -> holds(part, vars));
            if (map.get("or") instanceof List<?> parts) return parts.stream().anyMatch(part -> holds(part, vars));
            boolean count = map.containsKey("count");
            String subject = Templates.render((String) map.get(count ? "count" : "value"), vars);
            String op = map.keySet().stream().map(String::valueOf).filter(k -> !k.equals(count ? "count" : "value"))
                    .findFirst().orElseThrow();
            Object operand = map.get(op);
            if (count) {
                Integer size = listSize(subject);
                if (size == null) return false; // not a list: no count to compare
                return compare(size, op, ((Number) operand).doubleValue());
            }
            return switch (op) {
                case "equals" -> subject.equals(Templates.render((String) operand, vars));
                case "notEquals" -> !subject.equals(Templates.render((String) operand, vars));
                case "contains" -> subject.contains(Templates.render((String) operand, vars));
                case "matches" -> matches(subject, Templates.render((String) operand, vars));
                case "isEmpty" -> subject.isBlank() == (Boolean) operand;
                default -> {
                    Double number = number(subject);
                    yield number != null && compare(number, op, ((Number) operand).doubleValue());
                }
            };
        }

        private static boolean compare(double value, String op, double operand) {
            return switch (op) {
                case "atLeast" -> value >= operand;
                case "atMost" -> value <= operand;
                default -> value == operand; // equals (count)
            };
        }

        /** Whether the pattern is found in the value; a pattern that only breaks once rendered matches nothing. */
        private static boolean matches(String value, String pattern) {
            try {
                return Pattern.compile(pattern).matcher(value).find();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        /** The size of a list rendered as JSON ("" is an empty list); {@code null} for anything else. */
        private static Integer listSize(String text) {
            if (text.isBlank()) return 0;
            try {
                return parseJson(text) instanceof List<?> list ? list.size() : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static Double number(String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Sets variables: from a template, by incrementing a number or by appending to a list. */
    private static final class SetValues extends Spec {
        SetValues() {
            super("set", Set.of("values"), Set.of());
        }

        @Override
        void checkFields(NodeSpec node, List<String> errors) {
            Object values = node.fields().get("values");
            if (values == null) return;
            if (!(values instanceof Map<?, ?> map) || map.isEmpty()) {
                errors.add(where(node) + "`values` must be an object of variable → value, with at least one entry.");
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String at = "values." + entry.getKey();
                variable(node, at, entry.getKey(), errors);
                Object value = entry.getValue();
                boolean valid = value instanceof String
                        || value instanceof Map<?, ?> op && op.size() == 1
                        && (op.get("increment") instanceof Integer || op.get("append") instanceof String
                        || Templates.isPath(op.get("copy")) || Templates.isPath(op.get("first")) || Templates.isPath(op.get("rest")));
                if (!valid) {
                    errors.add(where(node) + "`" + at + "` must be a template, {\"increment\": <whole number>}, {\"append\": <template>},"
                            + " {\"copy\": <variable>}, {\"first\": <list variable>} or {\"rest\": <list variable>}.");
                }
            }
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            return Set.of(DONE);
        }

        @Override
        public Set<String> writes(NodeSpec node) {
            Set<String> writes = new LinkedHashSet<>();
            if (node.fields().get("values") instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) writes.add(String.valueOf(key));
            }
            return writes;
        }

        @Override
        public Set<String> reads(NodeSpec node) {
            // increment and append on a variable not set yet start from 0 / an empty list: no read
            Set<String> reads = new LinkedHashSet<>();
            if (node.fields().get("values") instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof String template) reads.addAll(Templates.references(template));
                    else if (value instanceof Map<?, ?> op && op.get("append") instanceof String template) {
                        reads.addAll(Templates.references(template));
                    } else if (value instanceof Map<?, ?> op) {
                        for (String name : List.of("copy", "first", "rest")) {
                            if (op.get(name) instanceof String path) reads.add(path);
                        }
                    }
                }
            }
            return reads;
        }

        /** Computes every value from the variables as they were before the node, then stores them all. */
        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            Map<?, ?> values = (Map<?, ?>) node.fields().get("values");
            return (state, config) -> {
                Map<String, Object> before = vars(state);
                Map<String, Object> after = new LinkedHashMap<>(before);
                values.forEach((key, value) -> {
                    String name = String.valueOf(key);
                    Object current = before.get(name);
                    if (value instanceof String template) {
                        after.put(name, Templates.render(template, before));
                    } else if (((Map<?, ?>) value).get("increment") instanceof Integer step) {
                        long number = current instanceof Number n ? n.longValue()
                                : current instanceof String text && text.strip().matches("-?\\d+") ? Long.parseLong(text.strip())
                                : 0;
                        after.put(name, number + step);
                    } else if (((Map<?, ?>) value).get("copy") instanceof String path) {
                        after.put(name, Templates.value(path, before) == null ? "" : Templates.value(path, before));
                    } else if (((Map<?, ?>) value).get("first") instanceof String path) {
                        // The first entry of a list, "" for an empty one or none: with {rest} a node works through a list one entry a round
                        after.put(name, Templates.value(path, before) instanceof List<?> items && !items.isEmpty() ? items.getFirst() : "");
                    } else if (((Map<?, ?>) value).get("rest") instanceof String path) {
                        after.put(name, Templates.value(path, before) instanceof List<?> items && !items.isEmpty()
                                ? new ArrayList<>(items.subList(1, items.size())) : new ArrayList<>());
                    } else {
                        List<Object> list = new ArrayList<>();
                        if (current instanceof List<?> items) list.addAll(items);
                        else if (current != null) list.add(current); // not a list yet: it becomes the first entry
                        list.add(Templates.render((String) ((Map<?, ?>) value).get("append"), before));
                        after.put(name, list);
                    }
                });
                return Map.of(State.SKILL_VARS, json(after));
            };
        }

        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            return DONE;
        }
    }

    /** Ends the skill; {@code result} becomes the call's result, an error result when {@code error} is true. */
    private static final class Finish extends Spec {
        Finish() {
            super("finish", Set.of("result"), Set.of("error", "say"));
        }

        @Override
        void checkFields(NodeSpec node, List<String> errors) {
            text(node, "result", errors);
            if (node.fields().containsKey("say")) text(node, "say", errors);
            Object error = node.fields().get("error");
            if (error != null && !(error instanceof Boolean)) errors.add(where(node) + "`error` must be true or false.");
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            return Set.of();
        }

        @Override
        public Set<String> reads(NodeSpec node) {
            Set<String> reads = new java.util.HashSet<>(templateReads(node, "result"));
            if (node.fields().containsKey("say")) reads.addAll(templateReads(node, "say"));
            return reads;
        }

        /**
         * Answers the skill's call in the conversation with the rendered result. With {@code say}, the rendered text is also the
         * reply to the user, and the agent makes no model call to word it again.
         */
        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            String result = (String) node.fields().get("result");
            String say = (String) node.fields().get("say");
            boolean error = Boolean.TRUE.equals(node.fields().get("error"));
            return (state, config) -> {
                Map<String, Object> update = answer(runtime.skill(), state, Templates.render(result, vars(state)), error);
                if (say == null) return update;
                String reply = Templates.render(say, vars(state)).strip();
                if (reply.isEmpty()) return update;
                List<ChatMessage> messages = new ArrayList<>((List<ChatMessage>) update.get(State.MESSAGES));
                messages.add(AiMessage.from(reply));
                Map<String, Object> both = new HashMap<>(update);
                both.put(State.MESSAGES, messages);
                return both;
            };
        }

        /** Never asked: a finish has no outcomes, the builder ends the subgraph after it. */
        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            throw new IllegalStateException("a finish node has no outcome");
        }
    }
}
