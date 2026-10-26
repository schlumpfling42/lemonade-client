package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.skills.CodingKinds;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.agent.skills.SkillValidator;
import net.rka.ai.graph.annotations.Edge;
import net.rka.ai.graph.annotations.Graph;
import net.rka.ai.graph.annotations.Node;
import net.rka.ai.graph.annotations.NodeWrapper;
import net.rka.ai.graph.annotations.Route;
import net.rka.ai.graph.annotations.Router;
import net.rka.ai.tools.ToolRegistry;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The skill builder subgraph, run as the "skillBuilder" node of {@link AgentGraph} when the model calls
 * {@link #START_TOOL}:
 *
 *   START -> tools -> draft --(model error)----------------------------> finish
 *                           --(answer)--> validate --(valid)-----------> ⏸ review
 *                                                  --(invalid, &lt; 3)----> draft (with the problems)
 *                                                  --(invalid, 3rd)-----> finish (nothing saved)
 *   review --(save)---> save -> finish -> END
 *          --(revise)-> draft (with the comment; a new round of 3 attempts)
 *          --(cancel)-> finish (nothing saved)
 *
 * It is Java, part of the app, so no skill file can break or change it.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>tools</b> (no model): takes the description from the {@code buildSkill} call, resets the
 *       builder's keys and starts its transcript ({@code skillMessages}) with the request: the skill file
 *       format with the node kinds, wiring and templates, the tools a skill may list (from the
 *       validator's {@link ToolRegistry}, without the {@linkplain SkillValidator#isForbidden forbidden}
 *       ones; marked when they ask for approval or bring web content), one example, the existing skills'
 *       names, and the description. When the description asks to change an existing skill by name
 *       ("change the coding skill", "edit release-notes"; a mere mention of the name doesn't count), its
 *       current JSON ({@link SkillLoader#source}) is included as the starting point, and for a file with
 *       coding kinds also their reference ({@link CodingKinds#builderReference}: kinds, outcomes, limits, the
 *       pinned tool). Other calls of the reply are answered "Not run".</li>
 *   <li><b>draft</b>: one model call without tools over the request, the latest draft and its problems
 *       or the user's comment (not every earlier draft, so the request stays the same size round after
 *       round); the answer is the draft ({@code skillDraft}, a code fence around it allowed).</li>
 *   <li><b>validate</b> (no model): the draft must be one JSON object; {@code "replaces": true} is taken
 *       out of it (it says the draft replaces the existing skill of its name, and is never saved); what is
 *       left must pass the loader's own checks ({@link SkillLoader#check}: the real validator, as a user
 *       file, and the skills folder as it is at that moment for names and node ids that are taken). A name that is taken needs {@code replaces}, and {@code replaces} needs a skill to replace.
 *       Problems go to {@code skillDraftErrors} (numbered by attempt) and, while attempts are left, back
 *       to the model as the next message of the transcript. {@link #MAX_ATTEMPTS} invalid drafts per review
 *       round end the builder.</li>
 *   <li><b>review</b>: the parent graph pauses before it (an interrupt); {@code Agent} shows the draft
 *       ({@link SkillDraft}) and writes the user's answer into {@code skillDecision} ({@link #SAVE},
 *       {@link #REVISE} with {@code skillComment}, {@link #CANCEL}), then resumes. A Revise adds the
 *       comment to the transcript and starts a new round; anything but save or revise counts as cancel
 *       (fail closed).</li>
 *   <li><b>save</b>: writes {@code <skill folder>/<name>.json} itself, never through the file tools (which
 *       refuse the skill folder), and only here, after the user chose Save. It overwrites a file only when
 *       the draft says {@code replaces}. {@code Agent} reloads the skills after the turn, so the turn that
 *       saved keeps the graph it started with.</li>
 *   <li><b>finish</b>: answers the {@code buildSkill} call with what happened (saved, cancelled, still
 *       invalid with the last problems, or stopped by a model or save error), flagged as an error unless
 *       the skill was saved, so the conversation keeps a record.</li>
 * </ul>
 * LangGraph4j inlines this subgraph into the parent, so it runs on the parent's state; besides its own
 * {@code skillDraft*} / {@code skillDecision} / {@code skillComment} keys it uses the skill keys
 * {@code skillCallId}, {@code skillMessages}, {@code skillStart}, {@code skillStopReason} and
 * {@code skillResult}, which a skill's start node resets too.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Trying a draft before saving it (a dry run with the user's inputs).</li>
 *   <li>Deleting a skill (today: remove its file from the skills folder and reload).</li>
 * </ul>
 */
@Graph(name = "skillBuilder", state = State.class, serializer = Graph.Serializer.LANGCHAIN4J)
@Edge(from = Graph.START, to = "tools")
@Edge(from = "tools", to = "draft")
@Edge(from = "save", to = "finish")
@Edge(from = "finish", to = Graph.END)
public class SkillBuilderGraph {

    /** The tool the model calls to build a skill; the main router sends such a call here. */
    static final String START_TOOL = "buildSkill";
    /** The node the parent graph pauses before, to show the draft to the user. */
    static final String REVIEW = "review";

    /** Values of {@code skillDecision}, written by {@code Agent} when the user answers the review. */
    static final String SAVE = "save";
    static final String REVISE = "revise";
    static final String CANCEL = "cancel";

    /** Invalid drafts per review round before the builder gives up; a Revise starts a new round. */
    static final int MAX_ATTEMPTS = 3;

    /** The example skill of the draft request (valid with the default tools; a test checks it). */
    static final String EXAMPLE = """
            {
              "name": "release-notes",
              "description": "Draft release notes from the recent commits of the trusted folder. Use when the user asks for release notes or a changelog.",
              "inputs": [ { "name": "since", "description": "tag or date to start from", "required": false } ],
              "start": "collect",
              "nodes": [
                { "id": "collect", "kind": "model", "prompt": "List the commits since {{input.since}} with git log in {{folder}}.",
                  "tools": ["runCommand"], "output": "commits", "next": "draft" },
                { "id": "draft", "kind": "model", "prompt": "Write release notes from these commits:\\n{{commits}}",
                  "output": "notes", "next": "check" },
                { "id": "check", "kind": "askUser", "question": "Release notes draft:\\n\\n{{notes}}",
                  "choices": ["accept", "revise", "cancel"], "output": "answer",
                  "on": { "accept": "done", "revise": "count", "cancel": "cancelled" } },
                { "id": "count", "kind": "set", "values": { "rounds": { "increment": 1 } }, "next": "tooMany" },
                { "id": "tooMany", "kind": "branch",
                  "cases": [ { "when": { "value": "{{rounds}}", "atLeast": 3 }, "then": "giveUp" } ],
                  "on": { "giveUp": "cancelled", "otherwise": "redraft" } },
                { "id": "redraft", "kind": "model",
                  "prompt": "Revise these release notes:\\n{{notes}}\\n\\nThe user asked: {{answer.comment}}",
                  "output": "notes", "next": "check" },
                { "id": "done", "kind": "finish", "result": "{{notes}}" },
                { "id": "cancelled", "kind": "finish", "result": "No release notes: cancelled.", "error": true }
              ]
            }
            """;

    /** The system prompt of every draft. */
    static final String INSTRUCTIONS = "You write skill files for an AI agent app: JSON documents that describe a fixed"
            + " procedure built from the app's node kinds, which the agent can then start as a tool. You run nothing"
            + " yourself: the app checks your draft and shows it to the user, who saves it or asks for changes."
            + " Follow the file format exactly. Answer with the skill's JSON object only, no explanation.";

    /** The format, kinds, wiring and templates of a skill file, as the draft request teaches them. */
    private static final String FORMAT = """
            # How a skill file looks

            A skill is one JSON object:
            - "name": lower-case letters, digits and "-", starting with a letter, e.g. "release-notes". The file is \
            saved as <name>.json, and the agent starts the skill with the tool skill_<name> ("-" becomes "_").
            - "description" (required, at least 20 characters): what the skill does and when to use it. The agent \
            reads it to decide when to start the skill.
            - "inputs" (optional): the tool's parameters, a list of {"name": ..., "description": ..., "required": \
            true or false}. Names are letters, digits and "_". Values are text; an optional input that isn't passed is "".
            - "limits" (optional): {"maxSteps": node visits per run, 1 to 200, default 40; "maxToolRounds": rounds \
            of tool calls per visit of a model node, 1 to 50, default 8}. These kinds have no other limits.
            - "start": the id of the first node.
            - "nodes": the steps.
            - "replaces": true, only when you change an existing skill and keep its name (see "Existing skills"). \
            Leave it out otherwise.
            Never set "tool", and no other top-level fields.

            Each node has "id" (letters, digits and "_", starting with a letter; not start, toolStep, toolApproval or \
            stopped), "kind", the \
            kind's fields and its wiring.

            ## Node kinds

              kind     | fields                                              | outcomes
              ---------+-----------------------------------------------------+---------------------------
              model    | prompt*, tools, output, format (text/json),         | done
                       | thinking (shown/progress)                           |
              askUser  | question*, choices*, output                         | one per choice
              branch   | cases* (list of {when, then})                       | each case's then, otherwise
              set      | values* (variable → value)                          | done
              finish   | result*, error (true/false)                         | none: ends the skill
              (* required; every kind also has the outcome stopped)

            - model: asks the model with "prompt". With "tools" (names from the list below) it may call them before \
            it answers; its final text goes to the variable named by "output". "format": "json" parses the answer, \
            so later nodes can use {{<output>.<field>}}. "thinking": "progress" shows progress instead of the text. \
            The model can't ask the user: use askUser for that.
            - askUser: pauses and asks the user "question", with a button per entry of "choices"; the answer goes \
            to "output" as {"choice": ..., "comment": ...}. A closed question stops the skill.
            - branch: the first case whose "when" holds gives the outcome named by its "then" (letters, digits, \
            "_"); no case → otherwise. Conditions, every text in them a template:
                {"value": "{{answer.choice}}", "equals": "accept"}      also notEquals, contains, matches (regex)
                {"value": "{{notes}}", "isEmpty": true}                 true: empty text; false: not empty
                {"value": "{{rounds}}", "atLeast": 3}                   number comparison, also atMost
                {"count": "{{items}}", "atLeast": 1}                    size of a list, also atMost, equals
                {"and": [ ... ]}, {"or": [ ... ]}                       combine conditions
            - set: "values" sets variables, each to a template text, {"increment": <whole number>} (adds to a \
            number; missing counts as 0) or {"append": <template>} (adds to a list).
            - finish: "result" (a template) is the skill's answer to the agent; "error": true marks it as failed.

            ## Wiring

            Every outcome of a node leads to a node id. A node with one outcome (model, set) uses "next": "<id>"; \
            a node with several (askUser, branch) uses "on": {"<outcome>": "<id>", ...} and maps every one of them. \
            The outcome "stopped" (an error, a closed question, a limit) needs no wiring: unmapped, it ends the \
            skill with an error; you may map it in "on". A finish has no "next" or "on". Every node must be \
            reachable from "start", and from every node a finish must be reachable (no endless loops).

            ## Templates

            Text fields can use {{var}}, {{var.field}} and {{input.<name>}}, plus the built-ins {{goal}} (the call's \
            arguments as text), {{folder}} (the trusted folder) and {{environment}} (OS, shell and tool versions). \
            Every variable must be an input, a built-in, or set by an earlier node's "output" or "set" on every path \
            to the node that uses it. Values are inserted as text; there are no expressions.
            """;

    /** The verbs that make a description a request to change an existing skill ({@link #asksToChange}). */
    private static final String CHANGE_VERBS = "change|edit|update|fix|modify|adjust|improve|extend|revise|rework|rename";
    // A node of a coding kind in a skill's JSON: such a source needs the coding kinds' reference
    private static final Pattern CODING_KIND = Pattern.compile("\"kind\"\\s*:\\s*\"coding\\.");
    private static final String STOPPED = "stopped";
    private static final String MODEL_ERROR = "model error";
    private static final String NOT_RUN = "Not run: the skill builder started; ask again afterwards.";
    // A ```json ... ``` block, which models often put around (or between prose and) a JSON answer: from the first
    // line that opens a fence to the last line that closes one (greedy), each fence line on its own
    private static final Pattern FENCE = Pattern.compile("^```[A-Za-z]*[ \\t]*\\R(.*)\\R```[ \\t]*$",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final ObjectMapper JSON = new ObjectMapper();
    // Two-space indent, "name": value (Jackson's default would write "name" : value)
    private static final ObjectWriter PRETTY = JSON.writer(new DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    private final ChatModel model;
    private final NodeTracing tracing;
    private final SkillLoader loader;
    private final String reference; // FORMAT, the tools and the example: the same for every run

    /**
     * @param loader the skill loader: its validator and rules check the drafts (against the skills folder as
     *               it is when the draft is checked), its folder is where Save writes, its files are the
     *               starting point of a change
     */
    SkillBuilderGraph(ChatModel model, NodeTracing tracing, SkillLoader loader) {
        this.model = model;
        this.tracing = tracing;
        this.loader = loader;
        this.reference = FORMAT + "\n" + toolList(loader.validator().tools()) + "\n## Example\n\n" + EXAMPLE;
    }

    /**
     * The tools a skill may list, one line each: name, description and what the user should know about it
     * (it asks for approval unless trusted, or it brings web content). From the registry the validator
     * checks against, without the forbidden tools, so the model is never told about a tool it can't list.
     */
    static String toolList(ToolRegistry tools) {
        StringBuilder out = new StringBuilder("## Tools a model node may list\n\n"
                + "List only what the step needs. A tool marked \"asks\" pauses the skill for the user's approval before"
                + " it runs, unless the user trusts it; after a \"web content\" tool, every such call asks.\n");
        for (ToolSpecification spec : tools.specifications(tool -> !SkillValidator.isForbidden(tool) && !tools.isSkillTool(tool))) {
            out.append("- ").append(spec.name());
            List<String> marks = new ArrayList<>();
            if (tools.requiresApproval(spec.name()) || tools.writesFiles(spec.name())) marks.add("asks");
            if (tools.readsWeb(spec.name())) marks.add("web content");
            if (!marks.isEmpty()) out.append(" (").append(String.join(", ", marks)).append(')');
            String description = Objects.requireNonNullElse(spec.description(), "").strip().replaceAll("\\s+", " ");
            if (!description.isEmpty()) out.append(": ").append(description);
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Starts the builder: the call id, fresh builder keys, and the draft request as the first message of
     * the builder's transcript. Other calls in the same reply are answered "Not run", so the conversation
     * has exactly one open call: the builder's, answered by finish.
     */
    @Node("tools")
    Map<String, Object> tools(State state) {
        List<ToolExecutionRequest> calls = state.unansweredToolCalls();
        ToolExecutionRequest start = calls.stream().filter(c -> START_TOOL.equals(c.name())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The skill builder started without a " + START_TOOL + " call"));
        List<ChatMessage> refused = calls.stream().filter(c -> c != start).<ChatMessage>map(c -> error(c, NOT_RUN)).toList();
        String description = argument(start, "description");
        if (description.isBlank()) description = "(no description given)";

        Map<String, Object> updates = new HashMap<>();
        updates.put(State.MESSAGES, refused);
        updates.put(State.SKILL_CALL_ID, start.id());
        // The transcript starts after any skill run or builder run earlier in this turn (the channel only appends)
        updates.put(State.SKILL_START, state.allSkillMessages().size());
        updates.put(State.SKILL_MESSAGES, List.of(UserMessage.from(request(description))));
        for (String key : List.of(State.SKILL_DRAFT, State.SKILL_DRAFT_ERRORS, State.SKILL_DECISION, State.SKILL_COMMENT,
                State.SKILL_STOP_REASON, State.SKILL_RESULT)) {
            updates.put(key, "");
        }
        return updates;
    }

    /**
     * The first message of a run: the reference, the existing skills (with the JSON of those the
     * description names, as the starting point of a change) and the description.
     */
    private String request(String description) {
        StringBuilder out = new StringBuilder(reference);
        Set<String> existing = loader.existingNames();
        out.append("\n## Existing skills\n\n");
        if (existing.isEmpty()) {
            out.append("None yet.\n");
        } else {
            List<String> names = existing.stream()
                    .map(name -> loader.builtInNames().contains(name) ? name + " (built-in)" : name).toList();
            out.append("These names are taken: ").append(String.join(", ", names)).append(". A new skill needs another name."
                    + " To change one of these skills, keep its name and add \"replaces\": true.\n");
        }
        boolean codingSource = false;
        for (String name : existing) {
            // Only a request to change the skill brings its file: "a skill that reviews my coding style" names
            // the word "coding", but asks for a new skill (and the coding file is long)
            if (!asksToChange(description, name)) continue;
            String json = loader.source(name).orElse(null);
            if (json == null) continue;
            codingSource |= CODING_KIND.matcher(json).find();
            out.append("\nThe description asks to change the existing skill `").append(name)
                    .append("`. Start from its current file below: keep what the description doesn't ask to change,"
                            + " keep the name and add \"replaces\": true.\n\n```json\n").append(json.strip()).append("\n```\n");
        }
        // The coding kinds aren't in the format above: a change to a coding skill needs them, and its own limits
        if (codingSource) out.append('\n').append(CodingKinds.builderReference());
        out.append("\n## The skill to write\n\n").append(description.strip())
                .append("\n\nAnswer with the skill's JSON object only.");
        return out.toString();
    }

    /**
     * Whether the text asks to change the skill: a verb of changing ({@link #CHANGE_VERBS}), then within three
     * words the skill's name as a whole word (not part of a longer name), any case: "change the coding skill",
     * "edit release-notes", "fix my greet skill". A mere mention ("a skill that reviews my coding style") is not.
     */
    static boolean asksToChange(String text, String name) {
        return Pattern.compile("\\b(?:" + CHANGE_VERBS + ")\\b(?:\\s+[\\w'-]+){0,3}?\\s+"
                        + "(?<![a-z0-9-])" + Pattern.quote(name) + "(?![a-z0-9-])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /**
     * One model call without tools: the request (the format reference, the existing skills and the
     * description), then only the latest draft and what was said about it (its problems, or the user's
     * Revise comment). The transcript keeps every draft, but sending them all would grow the request with
     * each attempt and round, until ContextFittingChatModel had to cut it, the reference first.
     */
    @Node("draft")
    Map<String, Object> draft(State state) {
        List<ChatMessage> transcript = state.skillMessages();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(INSTRUCTIONS));
        if (!transcript.isEmpty()) messages.add(transcript.getFirst()); // the request, written by the tools node
        int lastDraft = -1;
        for (int i = transcript.size() - 1; i > 0 && lastDraft < 0; i--) {
            if (transcript.get(i) instanceof AiMessage) lastDraft = i;
        }
        if (lastDraft > 0) messages.addAll(transcript.subList(lastDraft, transcript.size()));
        AiMessage reply;
        try {
            reply = model.chat(ChatRequest.builder().messages(messages).build()).aiMessage();
        } catch (RuntimeException e) {
            return Map.of(State.SKILL_STOP_REASON, reason(MODEL_ERROR, e)); // finish answers the call with it
        }
        String text = Objects.requireNonNullElse(reply.text(), "");
        // No tools were offered; should the model call one anyway, keep only the text so no call stays open
        if (reply.hasToolExecutionRequests()) reply = AiMessage.from(text.isBlank() ? "(no draft)" : text);
        return Map.of(State.SKILL_MESSAGES, List.of(reply), State.SKILL_DRAFT, text);
    }

    /** A model error ends the builder; otherwise the draft is checked. */
    @Router(from = "draft", routes = {
            @Route(on = "validate", to = "validate"),
            @Route(on = STOPPED, to = "finish")})
    String afterDraft(State state) {
        return state.skillStopReason().isBlank() ? "validate" : STOPPED;
    }

    /**
     * Checks the draft by the loader's rules. Valid: the draft is stored normalized and the round's
     * problems are cleared. Invalid: its problems are recorded under the attempt's number and, while
     * attempts are left, sent back to the model.
     */
    @Node("validate")
    Map<String, Object> validate(State state) {
        List<String> problems = new ArrayList<>();
        Draft draft = null;
        try {
            draft = Draft.parse(state.skillDraft());
            SkillFile skill = SkillFile.parse(draft.json());
            problems.addAll(nameProblems(skill, draft.replaces()));
            // Against the folder as it is now, not as the turn started: another window may have saved a skill
            problems.addAll(loader.check(skill));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage()); // not JSON, not an object, a wrong type: one problem, the rest can't be checked
        }
        if (problems.isEmpty()) return Map.of(State.SKILL_DRAFT, draft.stateValue(), State.SKILL_DRAFT_ERRORS, "");

        int attempt = failedAttempts(state) + 1;
        StringBuilder errors = new StringBuilder(state.skillDraftErrors());
        for (String problem : problems) errors.append(attempt).append('\t').append(problem.replaceAll("\\s+", " ")).append('\n');
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.SKILL_DRAFT_ERRORS, errors.toString());
        if (attempt < MAX_ATTEMPTS) {
            updates.put(State.SKILL_MESSAGES, List.of(UserMessage.from("The draft is not valid yet:\n- "
                    + String.join("\n- ", problems) + "\nFix every problem and answer with the whole corrected skill JSON only.")));
        }
        return updates;
    }

    /**
     * A taken name needs {@code replaces} (a new skill must not silently replace one), and {@code replaces}
     * needs a skill of that name, so a draft that says it replaces something really does.
     */
    private List<String> nameProblems(SkillFile skill, boolean replaces) {
        if (skill.name() == null) return List.of(); // the validator reports it
        boolean exists = loader.existingNames().contains(skill.name());
        if (exists && !replaces) {
            String where = loader.builtInNames().contains(skill.name()) ? "a built-in skill" : "a skill in the skills folder";
            return List.of("The name `" + skill.name() + "` is taken: there is " + where + " of that name. Give the new"
                    + " skill another name, or, to change that skill, keep the name and add \"replaces\": true.");
        }
        if (!exists && replaces) {
            return List.of("There is no skill `" + skill.name() + "` to replace; leave out \"replaces\".");
        }
        return List.of();
    }

    /** Valid → the user reviews it; invalid with attempts left → draft again; the last attempt → finish. */
    @Router(from = "validate", routes = {
            @Route(on = REVIEW, to = REVIEW),
            @Route(on = "retry", to = "draft"),
            @Route(on = "failed", to = "finish")})
    String afterValidate(State state) {
        if (state.skillDraftErrors().isBlank()) return REVIEW;
        return failedAttempts(state) < MAX_ATTEMPTS ? "retry" : "failed";
    }

    /**
     * Runs after the pause, once Agent stored the user's answer. A Revise starts a new round: the round's
     * problems are cleared and the comment goes to the model as the next message. The router after it
     * acts on the answer.
     */
    @Node(REVIEW)
    Map<String, Object> review(State state) {
        if (!REVISE.equals(state.skillDecision())) return Map.of();
        String comment = state.skillComment().isBlank() ? "(no comment)" : state.skillComment().strip();
        return Map.of(State.SKILL_DRAFT_ERRORS, "",
                State.SKILL_MESSAGES, List.of(UserMessage.from("The user reviewed the draft and asked for changes: " + comment
                        + "\nRevise the skill accordingly and answer with the whole revised skill JSON only.")));
    }

    /** The user's answer; a missing or unknown answer counts as cancel (fail closed). */
    @Router(from = REVIEW, routes = {
            @Route(on = SAVE, to = "save"),
            @Route(on = REVISE, to = "draft"),
            @Route(on = CANCEL, to = "finish")})
    String afterReview(State state) {
        return switch (state.skillDecision()) {
            case SAVE -> SAVE;
            case REVISE -> REVISE;
            default -> CANCEL;
        };
    }

    /**
     * Writes the reviewed draft as {@code <skill folder>/<name>.json}: through a temporary file and a move,
     * so a reload never reads half a file. An existing file is only replaced when the draft says
     * {@code replaces} (it was checked, but the folder may have changed while the user looked). A failure
     * is the stop reason; nothing is written then.
     */
    @Node("save")
    Map<String, Object> save(State state) {
        Draft draft = Draft.parse(state.skillDraft()); // validated, so it parses
        String name = SkillFile.parse(draft.json()).name();
        Path folder = loader.folder();
        if (folder == null) return Map.of(State.SKILL_STOP_REASON, "there is no skill folder to save the skill to");
        Path file = folder.resolve(name + ".json");
        if (Files.exists(file) && !draft.replaces()) {
            return Map.of(State.SKILL_STOP_REASON, file + " appeared while the draft was reviewed; it was not replaced");
        }
        // ".tmp" after ".json": the loader only reads *.json
        Path temporary = folder.resolve(name + ".json.tmp");
        try {
            Files.createDirectories(folder);
            Files.writeString(temporary, draft.json(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary); // don't leave the half-done save behind
            } catch (IOException ignored) {
                // the save already failed; the reason below says so
            }
            return Map.of(State.SKILL_STOP_REASON, reason("could not save " + file, e));
        }
        return Map.of();
    }

    /**
     * Answers the {@code buildSkill} call with the outcome, also stored as {@code skillResult}: stopped
     * (with the reason), saved (where, and with which tool from the next message on), still invalid after
     * {@link #MAX_ATTEMPTS} attempts (with the last draft's problems), or cancelled. Only a saved skill is
     * not an error.
     */
    @Node("finish")
    Map<String, Object> finish(State state) {
        String text;
        boolean saved = false;
        if (!state.skillStopReason().isBlank()) {
            String why = state.skillStopReason();
            text = "Stopped: " + (why.endsWith(".") ? why : why + ".") + " No skill was saved.";
        } else if (SAVE.equals(state.skillDecision())) {
            Draft draft = Draft.parse(state.skillDraft());
            SkillFile skill = SkillFile.parse(draft.json());
            // A replaced built-in keeps its tool (the loader pins it), so say the one the agent will offer
            String tool = loader.toolOf(skill);
            text = (draft.replaces() ? "Replaced" : "Saved") + " the skill `" + skill.name() + "` ("
                    + loader.folder().resolve(skill.name() + ".json") + "). The skills are reloaded after this turn:"
                    + " from the next message on, the tool `" + tool + "` starts it.";
            saved = true;
        } else if (!state.skillDraftErrors().isBlank()) {
            List<String> last = lastProblems(state);
            text = "No skill was saved: the draft was still invalid after " + MAX_ATTEMPTS + " attempts. Its problems:\n- "
                    + String.join("\n- ", last);
        } else {
            text = "No skill was saved: the user cancelled the review.";
        }
        ToolExecutionResultMessage answer = ToolExecutionResultMessage.builder()
                .id(state.skillCallId())
                .toolName(START_TOOL)
                .text(text)
                .isError(!saved)
                .build();
        return Map.of(State.MESSAGES, List.of(answer), State.SKILL_RESULT, text);
    }

    /** LangGraph4j inlines subgraphs into the parent, so name the spans "skillBuilder/draft" etc. */
    @NodeWrapper
    NodeActionWithConfig<State> traced(String name, NodeActionWithConfig<State> action) {
        return tracing.wrap(AgentGraph.SKILL_BUILDER + "/" + name, action);
    }

    // --- helpers ---

    /**
     * A draft as the model wrote it, split for the file: {@code json} is the skill file Save writes
     * (pretty-printed, without {@code replaces}), {@code replaces} whether the draft says it replaces the
     * existing skill of its name.
     */
    record Draft(String json, boolean replaces) {

        /**
         * Reads a draft: the text, or the code-fenced block in it, must be one JSON object; {@code replaces}
         * is true or false when present.
         *
         * @throws IllegalArgumentException with a message the model can act on
         */
        static Draft parse(String text) {
            String body = text == null ? "" : text.strip();
            JsonNode root;
            try {
                // The answer as it is first: a skill's prompt may itself contain ``` (e.g. "answer in a ```json block")
                root = JSON.readTree(body);
            } catch (JsonProcessingException e) {
                // Then the fenced block, fence lines on their own lines (a ``` inside a JSON string never starts a line)
                Matcher fence = FENCE.matcher(body);
                root = fence.find() ? readOrNull(fence.group(1)) : null;
                if (root == null) {
                    String line = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")";
                    throw new IllegalArgumentException("The answer is not valid JSON" + line + ": " + e.getOriginalMessage()
                            + ". Answer with the skill's JSON object only.", e);
                }
            }
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("The answer must be one JSON object: the skill file.");
            }
            ObjectNode skill = root.deepCopy();
            JsonNode replaces = skill.remove("replaces");
            if (replaces != null && !replaces.isBoolean()) throw new IllegalArgumentException("`replaces` must be true or false.");
            return new Draft(pretty(skill), replaces != null && replaces.asBoolean());
        }

        /** The draft as {@code skillDraft} keeps it once valid: the file, with {@code "replaces": true} first if set. */
        String stateValue() {
            if (!replaces) return json;
            try {
                ObjectNode withFlag = JSON.createObjectNode().put("replaces", true);
                withFlag.setAll((ObjectNode) JSON.readTree(json));
                return pretty(withFlag);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e); // json was written by pretty, so it parses
            }
        }

        private static JsonNode readOrNull(String json) {
            try {
                return JSON.readTree(json);
            } catch (JsonProcessingException e) {
                return null; // the caller reports the whole answer's error
            }
        }

        private static String pretty(JsonNode node) {
            try {
                return PRETTY.writeValueAsString(node) + "\n";
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e); // a tree read from JSON always serializes
            }
        }
    }

    /** How many invalid drafts this review round had: the highest attempt number in {@code skillDraftErrors}. */
    private static int failedAttempts(State state) {
        int attempts = 0;
        for (String line : state.skillDraftErrors().lines().toList()) {
            int tab = line.indexOf('\t');
            if (tab > 0) attempts = Math.max(attempts, Integer.parseInt(line.substring(0, tab))); // written by validate only
        }
        return attempts;
    }

    /** The problems of the round's last invalid draft. */
    private static List<String> lastProblems(State state) {
        String prefix = failedAttempts(state) + "\t";
        return state.skillDraftErrors().lines().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length())).toList();
    }

    /** "model error: &lt;message&gt;" on one line (the exception's class when it has no message). */
    private static String reason(String what, Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return what + ": " + message.strip().replaceAll("\\s+", " ");
    }

    /** A text argument of the call, or "" when it is missing or the arguments aren't JSON. */
    private static String argument(ToolExecutionRequest call, String name) {
        try {
            JsonNode value = JSON.readTree(call.arguments()).get(name);
            return value != null && value.isTextual() ? value.asText() : "";
        } catch (Exception e) {
            return ""; // malformed arguments from the model
        }
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
}
