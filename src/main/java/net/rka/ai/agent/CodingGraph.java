package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillRuntime;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tools.TrustedCommands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The coding job: the steps of the built-in skill {@code coding} ({@code net/rka/ai/agent/skills/coding.json}),
 * run as the subgraph "skill-coding" of {@link AgentGraph} when the model calls {@link #START_TOOL}.
 * Each step is a node kind ({@link net.rka.ai.agent.skills.CodingKinds}: {@code coding.environment},
 * {@code coding.investigate}, ...) implemented by a method here; the file holds the wiring (which step
 * follows which outcome), the limits ({@link Limits}) and the prompts. The built-in file wires them as:
 *
 *   start -> environment -> investigate --(tool calls)---------> investigateTools -> investigate
 *                                       --(findings, 6 rounds)-> plan -> review
 *   review --(valid plan)----> ⏸ planApproval --(approve)-> implement
 *          --(invalid once)--> plan                      --(revise)--> plan
 *                                                        --(deny)----> report
 *   implement --(tool calls)--------> implementTools --(all covered by the plan)-> implement
 *             --(step over, more)---> implement      --(any call not covered)---> plan
 *             --(no item ran: remind once, then skip)-> implement
 *             --(all steps over)----> verify
 *   verify --(passed)--------------------> designReview --(issues, a round left)-> implement (fix mode)
 *                                                       --(done)-------------------> report -> END
 *          --(failed, attempts left)-----> implement (fix mode)
 *          --(failed, 3rd attempt)-------> report
 *   every router except planApproval's --(stopped: model/tool error, invalid plan twice,
 *                                          over 15 turns in a step)--> report
 *   (the skill's built-in nodes around them: start finds the call, toolStep/toolApproval stay unused
 *    because the job runs its own tools, and stopped answers the call if a step ever fails without
 *    a report, e.g. when the skill's maxSteps is reached)
 *
 * The methods are what the kinds run (and their routers, the {@code after...} methods); the ones that ask
 * the model get the node's {@code prompt} from the file as {@code instructions}, the start of the system
 * prompt (or the whole system prompt, for plan, review and the design review).
 *
 * The job keeps its own transcript ({@code codingMessages}) apart from the conversation: the main
 * {@code messages} only see the {@code startCodingJob} call and, from {@code report}, its result.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>environment</b> (no model): takes the goal from the call that started the skill (the
 *       skill's {@code start} node found it, {@code skillCallId}, and answered the other calls of the
 *       reply with "Not run", so the conversation has no open call besides the job's), resets the
 *       job's state keys and loads the {@link EnvironmentInfo} summary (cached, probed when stale).</li>
 *   <li><b>investigate</b> ↔ <b>investigateTools</b>: a ReAct loop over the job's transcript. The model
 *       gets only the read-only tools plus {@code runCommand}, and {@code runCommand} only runs the fixed
 *       {@link EnvironmentInfo#PROBES} (exact match) or the user's trusted commands, the latter only until
 *       web content comes in (anywhere in the turn: before the job, in it, or in an earlier job).
 *       SSH and memory-changing tools ({@link #NOT_IN_JOB}) are never offered. After
 *       {@link #MAX_INVESTIGATE_ROUNDS} rounds of tool calls the model is asked once more without tools,
 *       so the loop always ends with findings.</li>
 *   <li><b>plan</b> and <b>review</b>: two model calls without tools that each answer with the plan JSON
 *       (the prompts of the skill's plan and review nodes); what parses is stored normalized in {@code codingPlan}. A parse error is
 *       appended to {@code codingPlanErrors}; a failed round goes back to plan once with the error,
 *       a second failed round ends the job.</li>
 *   <li><b>planApproval</b>: the parent graph pauses before it (an interrupt), {@code Agent} shows the
 *       plan ({@link PlanReview}: with the steps done so far and, for a revision, why it changed) and
 *       writes the user's answer into {@code codingDecision} ({@link #APPROVE}, {@link #REVISE} with
 *       {@code codingComment}, {@link #DENY}), then resumes. The node only records an approved plan in
 *       {@code codingApprovedPlan}; the router after it reads the answer; anything else counts as
 *       deny (fail closed).</li>
 *   <li><b>implement</b> ↔ <b>implementTools</b>: a ReAct loop per plan step. The model sees the plan
 *       (done steps marked), the current step and the job's transcript; a text answer ends the step
 *       ({@code codingStep} + 1), but only as "done" when the step was carried out: implementTools
 *       records in {@code codingDoneItems} which of the step's commands and file entries ran
 *       successfully, and a step that lists some of them needs at least one. Otherwise the model is
 *       reminded once, naming them; a second text answer marks the step skipped
 *       ({@code codingSkippedSteps}), which the plan and the report show. It is offered read-only tools, {@code runCommand} and the file
 *       tools, but what runs is decided by the plan check alone: a call runs when the approved plan
 *       covers it ({@link CodingPlan#covers}, without asking per call), or when it is one of the user's
 *       trusted commands and no web content came in ({@link State#codingTrusted}). Web content doesn't
 *       change what the plan covers, since the user approved those items explicitly. If any call of a
 *       reply isn't covered, none runs: the reason goes to {@code codingRevisionReason} and the job goes
 *       back to {@code plan} → {@code review} → the user (done steps are kept). {@link #NOT_IN_JOB} tools
 *       never run. A step that takes more than {@link #MAX_STEP_TURNS} tool-calling turns ends the job.
 *       A revised plan must keep the done steps: one with fewer steps than are done counts as a plan
 *       error, like invalid JSON. Exactly as many is a plan error too before verify ran (the pending
 *       step vanished); after a failed verify it is fine (new commands or files in done steps or in
 *       verify), and the approved revision goes on in implement's fix mode.</li>
 *   <li><b>verify</b> (no model): runs the plan's verify commands with the job's timeout. All passing
 *       (exit code 0), or none planned, goes to report. Otherwise implement runs again in fix mode:
 *       {@code codingStep} stays at the end of the plan, so its task is the failing output. At most
 *       {@link #MAX_VERIFY_ATTEMPTS} runs per job (a re-plan doesn't reset the count). A failed command
 *       that says the tool isn't installed marks the {@link EnvironmentInfo} cache stale.</li>
 *   <li><b>report</b>: the outcome (done / done with skipped steps / denied / stopped: why / still
 *       failing), the last approved plan with done and skipped steps marked (and a denied or failed
 *       revision, labelled as not approved) and the last verify output, as Markdown in
 *       {@code codingReport}. It is also the result of the {@code startCodingJob} call in the main
 *       {@code messages}, flagged as an error unless every step is done, so it is saved with the
 *       conversation and the model sees how it went.</li>
 *   <li><b>Stopping early</b>: a model call or tool run that throws, the last failed plan round and a
 *       step over its turns all write {@code codingStopReason}; every router then takes its
 *       {@code stopped} route to report ("Stopped: model error: ... Steps 1 to k are done."), so the
 *       job always answers its call and the conversation keeps a record of it. Any other exception in
 *       a step is caught by the skill graph ({@code skillStopReason}) and takes the same route.</li>
 * </ul>
 * LangGraph4j inlines the skill's subgraph into the parent, so all of this runs on the parent's state
 * ({@link AgentGraph.State}); the job's keys are reset by {@code environment}, so a second job in the
 * same turn starts clean. What a skill file can't change: every rule above lives in these methods,
 * whatever the wiring, and the validator only accepts files in which implementTools and verify are
 * reached through planApproval's {@code approve}.
 *
 * <h2>Not implemented yet</h2>
 * <ul> 
 *   <li>Showing verify's command runs in the job's transcript (today only their output is kept).</li>
 *   <li>Letting the investigation ask the user a question (today it can only read and probe).</li>
 *   <li>A Stop button for a running job (today it runs until it reports).</li>
 *   <li>Visual checking of designs (screenshots of the result for the model or the user to judge).</li>
 * </ul>
 */
public final class CodingGraph {

    /** The built-in skill that runs coding jobs; its subgraph is "skill-coding". */
    public static final String SKILL = "coding";
    /** The tool the model calls to start a coding job (the {@code "tool"} of {@code coding.json}). */
    public static final String START_TOOL = "startCodingJob";

    /** Values of {@code codingDecision}, written by {@code Agent} when the user answers the plan. */
    public static final String APPROVE = "approve";
    public static final String REVISE = "revise";
    public static final String DENY = "deny";

    // The limits' defaults, used when the skill file doesn't set them (coding.json sets these values)
    /** Rounds of investigation tool calls before the model must write its findings. */
    static final int MAX_INVESTIGATE_ROUNDS = 6;
    /** Plan rounds (plan + review) whose answer may fail to parse before the job ends. */
    static final int MAX_PLAN_ATTEMPTS = 2;
    /** Model turns with tool calls one step may take; one more ends the job ("the step did not finish"). */
    static final int MAX_STEP_TURNS = 15;
    /** Verify runs per job (re-plans don't reset the count); after the last failing one the job reports. */
    static final int MAX_VERIFY_ATTEMPTS = 3;
    /** Design reviews per job: one review with a fix round, then one more review whose findings are reported. */
    static final int MAX_DESIGN_ROUNDS = 2;
    /** What the design review reads at most: files and characters of code. */
    private static final int MAX_REVIEW_FILES = 25;
    private static final int MAX_REVIEW_CHARS = 60_000;
    /** At most this many design issues per review, most important first. */
    private static final int MAX_DESIGN_ISSUES = 8;
    /** Probe commands are version checks: a short timeout, like {@link EnvironmentInfo}'s own probes. */
    static final int PROBE_TIMEOUT_SECONDS = 20;

    /**
     * Tools a coding job never offers or runs: no job inside a job, no skill builder, no SSH (the plan
     * only covers the trusted folder on this computer), no changes to the conversation's memory.
     */
    static final Set<String> NOT_IN_JOB = Set.of(START_TOOL, SkillBuilderGraph.START_TOOL, "sshHosts", "runRemoteCommand",
            "remember", "forget");

    private static final String RUN_COMMAND = "runCommand";
    /** The route every router takes to report once a node set {@code codingStopReason}. */
    private static final String STOPPED = "stopped";
    // Prefixes of codingStopReason for an exception from a model call or a tool run
    private static final String MODEL_ERROR = "model error";
    private static final String TOOL_ERROR = "tool error";
    // Prefixes of the codingPlanErrors lines: which phase's answer failed to parse
    private static final String PLAN_ERROR = "plan: ";
    private static final String REVIEW_ERROR = "review: ";
    // First line of codingVerifyOutput, written by verify (not by a command), so the router can trust it
    private static final String VERIFY_PASSED = "Verify passed";
    private static final String VERIFY_FAILED = "Verify failed";
    /** How much of the last verify output the report keeps. */
    private static final int MAX_REPORT_OUTPUT_CHARS = 4_000;
    /** Ends the task message implement adds at the start of every step (and fix). */
    private static final String DO_IT = "\nDo this now.";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the other calls of the reply that started a job are answered with (by the skill's start node). */
    public static final String NOT_RUN = "Not run: a coding job started; ask again afterwards.";

    /**
     * What every coding job of the app runs with, whatever skill file uses the coding kinds.
     *
     * @param environment    the cached environment summary for the prompts
     * @param timeoutSeconds timeout of the job's commands ({@code CODING_COMMAND_TIMEOUT_SECONDS})
     * @param plansDir       where each job's plan is saved as Markdown for review ({@code ~/chat/plans}); null for none
     * @param guides         the design guides plan, implement and the design review follow; {@link DesignGuides#NONE}
     *                       turns the design review off
     */
    public record Setup(EnvironmentInfo environment, int timeoutSeconds, Path plansDir, DesignGuides guides) {
        /** Without plan files and design guides (tests). */
        Setup(EnvironmentInfo environment, int timeoutSeconds) {
            this(environment, timeoutSeconds, null, DesignGuides.NONE);
        }
    }

    /**
     * The job's limits, from the skill file's {@code limits} ({@link SkillFile.Limits#extra}); a limit the
     * file leaves out has the default of the constant above ({@link #MAX_STEP_TURNS} ...).
     */
    public record Limits(int investigateRounds, int stepTurns, int verifyAttempts, int designRounds, int planAttempts) {
        /** The names the limits have in a skill file's {@code limits}. */
        public static final String INVESTIGATE_ROUNDS = "maxInvestigateRounds";
        public static final String STEP_TURNS = "maxStepTurns";
        public static final String VERIFY_ATTEMPTS = "maxVerifyAttempts";
        public static final String DESIGN_ROUNDS = "maxDesignRounds";
        public static final String PLAN_ATTEMPTS = "maxPlanAttempts";

        public static Limits of(SkillFile.Limits limits) {
            Map<String, Integer> extra = limits.extra();
            return new Limits(extra.getOrDefault(INVESTIGATE_ROUNDS, MAX_INVESTIGATE_ROUNDS),
                    extra.getOrDefault(STEP_TURNS, MAX_STEP_TURNS),
                    extra.getOrDefault(VERIFY_ATTEMPTS, MAX_VERIFY_ATTEMPTS),
                    extra.getOrDefault(DESIGN_ROUNDS, MAX_DESIGN_ROUNDS),
                    extra.getOrDefault(PLAN_ATTEMPTS, MAX_PLAN_ATTEMPTS));
        }
    }

    private final ChatModel model;
    private final ToolCallRunner toolCalls;
    private final EnvironmentInfo environment;
    private final int timeoutSeconds;
    private final Path plansDir; // null: no plan files (tests)
    private final DesignGuides guides;
    private final Limits limits;
    private final String startTool; // the skill's tool, whose call the report answers

    /**
     * The job's steps for one skill: its model, tools and limits from the skill's runtime, the rest from
     * the app ({@code setup}). Built once per node when the skill's subgraph is built ({@code CodingKinds}).
     */
    public CodingGraph(SkillRuntime runtime, Setup setup) {
        this.model = runtime.model();
        this.toolCalls = runtime.toolCalls();
        this.limits = Limits.of(runtime.skill().limits());
        this.startTool = runtime.skill().toolName();
        this.environment = setup.environment();
        this.timeoutSeconds = setup.timeoutSeconds();
        this.plansDir = setup.plansDir();
        this.guides = setup.guides();
    }

    /**
     * Starts the job: goal and call id from the call that started the skill ({@code skillCallId}, found
     * by the skill's start node, which also answered the reply's other calls), fresh job keys, the
     * environment summary, and the goal as the first message of the job's transcript.
     */
    public Map<String, Object> environment(State state) {
        ToolExecutionRequest start = state.unansweredToolCalls().stream()
                .filter(c -> c.id().equals(state.skillCallId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The coding job started without its " + startTool + " call"));
        String goal = argument(start, "goal");
        if (goal.isBlank()) goal = "(no goal given)";

        Map<String, Object> updates = new HashMap<>(); // more than Map.of's ten entries
        updates.put(State.CODING_GOAL, goal);
        updates.put(State.CODING_CALL_ID, start.id());
        updates.put(State.CODING_PLAN_FILE, planFileFor(goal));
        updates.put(State.CODING_ENVIRONMENT, environmentSummary()); // probes only when the cache is stale
        // The job's transcript starts after any earlier job of this turn (the channel only appends)
        updates.put(State.CODING_START, state.allCodingMessages().size());
        updates.put(State.CODING_MESSAGES, List.of(UserMessage.from("Coding job goal: " + goal)));
        // Reset what an earlier job of this turn may have left; "" and 0 mean "not set"
        for (String key : List.of(State.CODING_FINDINGS, State.CODING_PLAN, State.CODING_DECISION, State.CODING_COMMENT,
                State.CODING_REVISION_REASON, State.CODING_VERIFY_OUTPUT, State.CODING_REPORT, State.CODING_PLAN_ERRORS,
                State.CODING_APPROVED_PLAN, State.CODING_DONE_ITEMS, State.CODING_SKIPPED_STEPS, State.CODING_STOP_REASON,
                State.CODING_DESIGN_ISSUES)) {
            updates.put(key, "");
        }
        for (String key : List.of(State.CODING_STEP, State.CODING_STEP_TURNS, State.CODING_ATTEMPTS, State.CODING_STEP_REMINDED,
                State.CODING_DESIGN_ROUNDS, State.CODING_VERIFY_BASE)) {
            updates.put(key, 0);
        }
        return updates;
    }

    /**
     * The cached or freshly probed environment. A failure here (cache unreadable in an unexpected way,
     * probe runner crashing) must not lose the job: the plan is then made without the tool versions.
     */
    private String environmentSummary() {
        try {
            return environment.summary();
        } catch (RuntimeException e) {
            return "Environment check failed (" + e.getMessage() + "); check tool versions while investigating.";
        }
    }

    /**
     * One investigation round: the model sees the job's transcript and either calls read-only tools
     * and probes, or answers with its findings. The last allowed round offers no tools.
     */
    public Map<String, Object> investigate(State state, String instructions) {
        List<ChatMessage> job = state.codingMessages();
        long toolRounds = job.stream().filter(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()).count();
        boolean lastRound = toolRounds >= limits.investigateRounds();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(instructions + "\n" + context(state)
                + "\n\nProbe commands you may run: " + String.join(", ", EnvironmentInfo.PROBES)));
        messages.addAll(job); // the goal, then this loop's calls and results
        ChatRequest.Builder request = ChatRequest.builder();
        if (lastRound) {
            // Not stored in the state: only this request needs it
            messages.add(UserMessage.from("That's enough investigating. Write your findings now, without tool calls."));
        } else {
            // Only the tools investigate may use, so the model isn't offered calls that would be refused
            request.toolSpecifications(toolCalls.tools().specifications(this::offeredWhileInvestigating));
        }
        AiMessage reply;
        try {
            reply = model.chat(request.messages(messages).build()).aiMessage();
        } catch (RuntimeException e) {
            return stop(MODEL_ERROR, e); // the job reports instead of the run failing without a record
        }
        if (lastRound && reply.hasToolExecutionRequests()) {
            // The model called tools anyway: keep only its text, so no call stays open in the transcript
            reply = AiMessage.from(textOf(reply).isBlank() ? "(no findings)" : textOf(reply));
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_MESSAGES, List.of(reply));
        if (!reply.hasToolExecutionRequests()) updates.put(State.CODING_FINDINGS, textOf(reply)); // done: on to plan
        return updates;
    }

    /** Tool calls → run them; an answer without calls → the findings are in, plan; a model error → report. */
    public static String afterInvestigate(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingUnansweredToolCalls().isEmpty() ? "plan" : "tools";
    }

    /**
     * Runs the investigation's calls that are allowed before a plan exists (read-only tools, probes,
     * the user's trusted commands); every other call gets an error result naming the probes.
     * Results go to the job's transcript, not the conversation.
     */
    public Map<String, Object> investigateTools(State state) {
        FactBook facts = new FactBook(state.facts()); // read-only here: listFacts may read it, nothing may change it
        List<ChatMessage> results = new ArrayList<>();
        try {
            for (ToolExecutionRequest call : state.codingUnansweredToolCalls()) {
                if (!allowedWhileInvestigating(call, state)) {
                    // After web content a page could have asked for the command, so trusted commands stop counting
                    String trusted = state.codingWebContent(toolCalls.tools())
                            ? " (the user's trusted commands no longer run unasked: web content was read in this turn)"
                            : " (and the user's trusted commands)";
                    results.add(error(call, "Not run: before the plan is approved, the only commands that run are these probes: "
                            + String.join(", ", EnvironmentInfo.PROBES) + trusted + ". Put every other command into the plan."));
                    continue;
                }
                // One call at a time, so a probe gets the short timeout and a trusted command the job's
                Integer timeout = EnvironmentInfo.isProbe(argument(call, "command")) ? PROBE_TIMEOUT_SECONDS : timeoutSeconds;
                results.addAll(toolCalls.run(List.of(call), state.workFolder(), facts, timeout));
            }
        } catch (RuntimeException e) {
            // The registry turns a tool's own exceptions into error results; this is anything else going wrong
            return Map.of(State.CODING_MESSAGES, results, State.CODING_STOP_REASON, reason(TOOL_ERROR, e));
        }
        return Map.of(State.CODING_MESSAGES, results);
    }

    /** Results are in → investigate again; a tool run that failed outright → report. */
    public static String afterInvestigateTools(State state) {
        return stopped(state) ? STOPPED : "investigate";
    }

    /** Asks for the plan JSON: from the findings, or, on a revision, from the previous plan and why it must change. */
    public Map<String, Object> plan(State state, String instructions) {
        StringBuilder prompt = new StringBuilder(context(state)).append("\n\n").append(findings(state)).append(guide(state));
        state.codingPlan().ifPresent(previous -> prompt.append("\n\nThe previous plan:\n").append(previous.toJson()));
        prompt.append(revisionNotes(state));
        List<String> errors = state.codingPlanErrors();
        if (!errors.isEmpty()) {
            // The latest failure of this plan round (usually the review's, which is what ended the round)
            prompt.append("\n\nThe last answer was not a valid plan: ").append(withoutPhase(errors.getLast()))
                    .append("\nAnswer with the plan JSON object only.");
        }
        AiMessage reply;
        try {
            reply = ask(instructions, prompt.toString());
        } catch (RuntimeException e) {
            return stop(MODEL_ERROR, e);
        }
        return withPlan(reply, PLAN_ERROR, state);
    }

    /** A draft (valid or not) → review; a model error → report. */
    public static String afterPlan(State state) {
        return stopped(state) ? STOPPED : "review";
    }

    /** Asks the model to check the draft from {@code plan} and answer with the corrected plan JSON. */
    public Map<String, Object> review(State state, String instructions) {
        StringBuilder prompt = new StringBuilder(context(state)).append("\n\n").append(findings(state)).append(guide(state));
        prompt.append(revisionNotes(state));
        // The draft as the plan phase wrote it (its last message), even if it didn't parse
        prompt.append("\n\nThe draft plan:\n").append(lastAiText(state.codingMessages()));
        List<String> errors = state.codingPlanErrors();
        if (!errors.isEmpty() && errors.getLast().startsWith(PLAN_ERROR)) {
            prompt.append("\n\nThe draft is not a valid plan: ").append(withoutPhase(errors.getLast()))
                    .append("\nWrite it as a valid plan JSON object.");
        }
        AiMessage reply;
        try {
            reply = ask(instructions, prompt.toString());
        } catch (RuntimeException e) {
            return stop(MODEL_ERROR, e);
        }
        Map<String, Object> updates = withPlan(reply, REVIEW_ERROR, state);
        // Every failed round ends with a review error (review runs after every plan); the last one allowed ends the job
        List<String> failedRounds = ((String) updates.getOrDefault(State.CODING_PLAN_ERRORS, "")).lines()
                .filter(e -> e.startsWith(REVIEW_ERROR)).toList();
        if (failedRounds.size() >= limits.planAttempts()) {
            updates.put(State.CODING_STOP_REASON, "no valid plan after " + limits.planAttempts() + " attempts: "
                    + withoutPhase(failedRounds.getLast()));
        }
        // A valid reviewed plan is what you'll be asked about: save it as Markdown for review
        if ("".equals(updates.get(State.CODING_PLAN_ERRORS)) && updates.get(State.CODING_PLAN) instanceof String json) {
            CodingPlan plan = CodingPlan.parse(json);
            boolean revision = state.codingApprovedPlan().isPresent() || REVISE.equals(state.codingDecision());
            PlanReview review = new PlanReview(plan, state.codingStep(), state.codingSkippedSteps(),
                    state.codingRevisionReason(), state.codingComment(), revision);
            writePlanFile(state, "Status: waiting for your approval\n\n" + review.toMarkdown());
        }
        return updates;
    }

    /**
     * A reviewed plan → pause for the user; otherwise the round failed: try once more. {@code review}
     * clears the errors when its answer parses, so "no errors" means a valid plan; after the last
     * failed round (or a model error) it set the stop reason, and the job reports.
     */
    public static String afterReview(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingPlanErrors().isEmpty() ? "approval" : "retry";
    }

    /**
     * Runs after the pause, once Agent stored the user's answer: an approved plan is recorded as
     * {@code codingApprovedPlan}, the plan the report shows (a later revision only replaces it once it
     * is approved too). The router after it acts on the answer.
     */
    public Map<String, Object> planApproval(State state) {
        // Revise: the user's comment is now why the plan changes, so an older reason from the graph
        // (a refused call) must not be shown as the reason of the next draft
        if (REVISE.equals(state.codingDecision())) return Map.of(State.CODING_REVISION_REASON, "");
        if (!APPROVE.equals(state.codingDecision())) return Map.of();
        return Map.of(State.CODING_APPROVED_PLAN, state.codingPlan().map(CodingPlan::toJson).orElse(""));
    }

    /**
     * The user's answer to the plan; a missing or unknown answer counts as deny (fail closed).
     * An approved plan always leaves something to implement: a step, or after a failed verify the fix
     * ({@code withPlan} refuses a revision that would leave neither).
     */
    public static String afterPlanApproval(State state) {
        return switch (state.codingDecision()) {
            case APPROVE -> APPROVE;
            case REVISE -> REVISE;
            default -> DENY;
        };
    }

    /**
     * One model turn on the current step (or, once every step is done, on fixing the failed verify):
     * the model sees the plan, the step and the job's transcript, and either calls tools or answers
     * with text, which ends the step.
     * <p>
     * A text answer only counts as "done" when the step was carried out: a step that lists commands or
     * file changes needs at least one of them to have run successfully ({@code codingDoneItems}, written
     * by implementTools). If none ran, the model is reminded once, naming the step's items; if it answers
     * with text again, the step is marked skipped ({@code codingSkippedSteps}) and the job moves on, so
     * the plan and the report show it as skipped instead of done. A step without commands or files
     * (e.g. "check the result") is done by the answer alone, and so is a fix.
     */
    public Map<String, Object> implement(State state, String instructions) {
        CodingPlan plan = state.codingPlan().orElseThrow(() -> new IllegalStateException("implement without an approved plan"));
        int step = state.codingStep();
        boolean fixing = step >= plan.steps().size(); // all steps done: verify failed and sent the job back
        String task = !fixing
                ? "Step " + (step + 1) + " of " + plan.steps().size() + ": " + plan.steps().get(step).description()
                : designFixPending(state)
                ? "All steps are done and the build passes, but the design review found these issues against the"
                        + " design guide. Fix them by changing the files the plan covers (keep the behavior the same),"
                        + " then answer with a one-line summary. Issues:\n" + state.codingDesignIssues()
                : "All steps are done, but verifying failed. Fix it with the plan's commands and file changes. Verify output:\n"
                        + state.codingVerifyOutput();

        Map<String, Object> updates = new HashMap<>();
        // implement only runs under an approved plan: the reason for the last revision and the user's
        // comment are used up, so they don't show up in a later revision
        updates.put(State.CODING_REVISION_REASON, "");
        updates.put(State.CODING_COMMENT, "");
        List<ChatMessage> added = new ArrayList<>();
        if (state.codingStepTurns() == 0 && !state.codingStepReminded()) {
            // The step's first turn: the task joins the transcript, so the model's turn follows a user message
            // (after approval the transcript ends with the review's answer) and later turns see where a step began
            added.add(UserMessage.from(task + DO_IT));
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(instructions + "\n" + context(state) + "\n\n" + findings(state)
                + guide(state)
                + "\n\nThe approved plan (✓ = done, ✗ = skipped):\n" + plan.toMarkdown(step, state.codingSkippedSteps())
                + "\nYour task now: " + task));
        messages.addAll(state.codingMessages());
        messages.addAll(added);
        AiMessage reply;
        try {
            reply = model.chat(ChatRequest.builder()
                    .messages(messages)
                    // Only tools a job may use; whether a call runs is still decided by the plan in implementTools
                    .toolSpecifications(toolCalls.tools().specifications(this::offeredWhileImplementing))
                    .build()).aiMessage();
        } catch (RuntimeException e) {
            updates.put(State.CODING_MESSAGES, added);
            updates.put(State.CODING_STOP_REASON, reason(MODEL_ERROR, e));
            return updates;
        }
        added.add(reply);
        updates.put(State.CODING_MESSAGES, added);

        if (reply.hasToolExecutionRequests()) {
            int turns = state.codingStepTurns() + 1;
            updates.put(State.CODING_STEP_TURNS, turns);
            if (turns > limits.stepTurns()) {
                // One turn too many: its calls don't run, the job reports
                String what = !fixing ? "Step " + (step + 1)
                        : designFixPending(state) ? "The fix of the design review's issues"
                        : "The fix after a failed verify";
                updates.put(State.CODING_STOP_REASON, what + " did not finish within " + limits.stepTurns() + " model turns.");
            }
            return updates;
        }
        List<String> items = fixing ? List.of() : plan.items(step);
        Set<String> ran = state.codingItemsDone(step + 1);
        boolean carriedOut = items.isEmpty() || items.stream().anyMatch(ran::contains);
        if (!carriedOut && !state.codingStepReminded()) {
            // Answered without doing anything the step lists: ask once more, naming what is missing
            added.add(UserMessage.from("Step " + (step + 1) + " is not done: none of its planned commands or file changes ran ("
                    + String.join(", ", items) + "). Carry them out now with tool calls. If you answer without them"
                    + " again, the step is marked as skipped."));
            updates.put(State.CODING_STEP_REMINDED, 1);
            return updates; // same step, same turn count: the router sends the job back here ("next")
        }
        if (!carriedOut) {
            // Reminded and still nothing: move on, but never as "done"
            Set<Integer> skipped = new TreeSet<>(state.codingSkippedSteps());
            skipped.add(step + 1);
            updates.put(State.CODING_SKIPPED_STEPS, String.join(",", skipped.stream().map(String::valueOf).toList()));
        }
        // The step is over (done or skipped). While fixing, the step stays at the end of the plan
        updates.put(State.CODING_STEP, Math.min(step + 1, plan.steps().size()));
        updates.put(State.CODING_STEP_TURNS, 0);
        updates.put(State.CODING_STEP_REMINDED, 0);
        return updates;
    }

    /**
     * Tool calls → check and run them; a finished (or skipped) step → the next step, or verify when
     * none is left (also after a fix); a reminder → the same step again ("next"); a model error or a
     * step over its turns → report.
     */
    public static String afterImplement(State state) {
        if (stopped(state)) return STOPPED;
        if (!state.codingUnansweredToolCalls().isEmpty()) return "tools";
        int steps = state.codingPlan().map(p -> p.steps().size()).orElse(0);
        return state.codingStep() < steps ? "next" : "verify";
    }

    /**
     * The plan check: when the approved plan covers every call, they run (no per-call approval, the
     * job's command timeout); when any call isn't covered, none runs, each gets a "not in the plan"
     * result, and the job goes back to planning with the reason in {@code codingRevisionReason}.
     */
    public Map<String, Object> implementTools(State state) {
        List<ToolExecutionRequest> calls = state.codingUnansweredToolCalls();
        String unapproved = notApproved(state);
        if (unapproved != null) {
            // The wiring let a plan through that the user never saw: nothing runs, the job reports
            List<ChatMessage> refused = calls.stream().<ChatMessage>map(c -> error(c, "Not run: " + unapproved)).toList();
            return Map.of(State.CODING_MESSAGES, refused, State.CODING_STOP_REASON, unapproved);
        }
        CodingPlan plan = state.codingPlan().orElseThrow(); // the approved one, notApproved checked it
        List<String> reasons = calls.stream()
                .map(call -> whyNotCovered(call, plan, state))
                .filter(Objects::nonNull)
                .toList();
        if (reasons.isEmpty()) {
            FactBook facts = new FactBook(state.facts()); // remember/forget are never offered, so nothing changes it
            List<ChatMessage> results;
            try {
                results = toolCalls.run(calls, state.workFolder(), facts, timeoutSeconds);
            } catch (RuntimeException e) {
                // The registry turns a tool's own exceptions into error results; this is anything else going wrong
                return Map.of(State.CODING_STOP_REASON, reason(TOOL_ERROR, e));
            }
            return Map.of(
                    State.CODING_MESSAGES, results,
                    State.CODING_DONE_ITEMS, withDoneItems(state, plan, calls, results));
        }
        // All or nothing: a reply's calls usually belong together, so none of them runs
        List<ChatMessage> refused = calls.stream()
                .<ChatMessage>map(c -> error(c, "Not in the approved plan; a revised plan will be proposed"))
                .toList();
        return Map.of(
                State.CODING_MESSAGES, refused,
                State.CODING_REVISION_REASON, String.join("\n", reasons),
                State.CODING_STEP_TURNS, 0,     // the step starts over under the revised plan
                State.CODING_STEP_REMINDED, 0); // with its task message, so a reminder is due again
    }

    /** Covered calls ran → back to the model; a call the plan doesn't cover → a revised plan; a failed run → report. */
    public static String afterImplementTools(State state) {
        if (stopped(state)) return STOPPED;
        // implement clears the reason on every turn, so a reason here is the one implementTools just set
        return state.codingRevisionReason().isBlank() ? "implement" : "revise";
    }

    /**
     * {@code codingDoneItems} plus the current step's items that these calls carried out successfully:
     * a result not flagged as an error, and for {@code runCommand} exit code 0. Nothing is added while
     * fixing (every step is over by then).
     */
    private static String withDoneItems(State state, CodingPlan plan, List<ToolExecutionRequest> calls,
                                        List<ChatMessage> results) {
        int step = state.codingStep();
        StringBuilder done = new StringBuilder(state.codingDoneItems());
        if (step >= plan.steps().size()) return done.toString();
        for (int i = 0; i < calls.size() && i < results.size(); i++) { // run returns the results in call order
            ToolExecutionRequest call = calls.get(i);
            ToolExecutionResultMessage result = (ToolExecutionResultMessage) results.get(i);
            boolean succeeded = !Boolean.TRUE.equals(result.isError())
                    && (!call.name().equals(RUN_COMMAND) || ranWithExitCodeZero(result.text()));
            if (!succeeded) continue;
            for (String item : plan.itemsDoneBy(step, call, state.workFolder())) {
                done.append(step + 1).append('\t').append(item).append('\n');
            }
        }
        return done.toString();
    }

    /** runCommand's result starts with its exit code (see TerminalTools). */
    private static boolean ranWithExitCodeZero(String result) {
        return result.startsWith("exit code: 0\n") || result.equals("exit code: 0");
    }

    /**
     * Runs every verify command of the plan (no model), one {@code runCommand} call each with the job's
     * timeout, and stores what they printed in {@code codingVerifyOutput}: a first line written here
     * ({@link #VERIFY_PASSED} or {@link #VERIFY_FAILED}), then each command with its output. A command
     * passes when its result starts with {@code exit code: 0}; a result that says the tool isn't
     * installed makes the environment summary stale, so the next job probes again. Every run counts
     * as an attempt ({@code codingAttempts}). Agent reports the "Verifying" progress after this node,
     * so it shows once the commands have finished. A run that throws (not a failing command, which
     * is a result) stops the job.
     */
    public Map<String, Object> verify(State state) {
        String unapproved = notApproved(state);
        if (unapproved != null) return Map.of(State.CODING_STOP_REASON, unapproved); // nothing runs, the job reports
        CodingPlan plan = state.codingPlan().orElseThrow(); // the approved one, notApproved checked it
        int attempt = state.codingAttempts() + 1;
        FactBook facts = new FactBook(state.facts()); // runCommand doesn't use it, but every run passes the facts
        List<String> outputs = new ArrayList<>();
        int failed = 0;
        boolean missingTool = false;
        for (int i = 0; i < plan.verify().size(); i++) {
            CodingPlan.Command command = plan.verify().get(i);
            Map<String, String> arguments = Map.of("command", command.command(), "workingDirectory", command.workingDirectory());
            ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id("verify-" + attempt + "-" + (i + 1)) // unique per run, like a model's call ids
                    .name(RUN_COMMAND)
                    .arguments(toJson(arguments))
                    .build();
            String result;
            // The plan check, as in implementTools: the approval only covers working directories inside the folder
            if (plan.covers(call, state.workFolder(), TrustedCommands.of(List.of()), toolCalls.tools())) {
                try {
                    result = ((ToolExecutionResultMessage) toolCalls.run(List.of(call), state.workFolder(), facts, timeoutSeconds)
                            .getFirst()).text();
                } catch (RuntimeException e) {
                    // Not a failing command (that is a result) but the run itself breaking: the job reports
                    return Map.of(State.CODING_STOP_REASON, reason(TOOL_ERROR, e), State.CODING_ATTEMPTS, attempt);
                }
            } else {
                result = "Not run: the working directory is outside the trusted folder.";
            }
            boolean passed = ranWithExitCodeZero(result);
            if (!passed) {
                failed++;
                // Only failed commands: a passing build may well print "not found" somewhere in its log
                missingTool |= EnvironmentInfo.looksLikeMissingTool(result);
            }
            String where = command.workingDirectory().isBlank() ? "" : " (in " + command.workingDirectory() + ")";
            outputs.add("$ " + command.command() + where + "\n" + result);
        }
        if (missingTool) environment.markStale(); // a tool the summary listed may be gone (or never was there)

        String summary = plan.verify().isEmpty() ? VERIFY_PASSED + " (the plan has no verify commands)."
                : failed == 0 ? VERIFY_PASSED + "."
                : VERIFY_FAILED + ": " + failed + " of " + plan.verify().size() + " commands failed.";
        outputs.addFirst(summary);
        return Map.of(
                State.CODING_VERIFY_OUTPUT, String.join("\n\n", outputs),
                State.CODING_ATTEMPTS, attempt);
    }

    /**
     * Passed → report; failed → implement in fix mode (the step stays at the end of the plan, so
     * implement's task is the failing output), until the {@link #MAX_VERIFY_ATTEMPTS}th run, then report.
     */
    public static String afterVerify(State state, Limits limits) {
        if (stopped(state)) return STOPPED;
        if (verifyPassed(state)) return "passed";
        // Attempts count from the design review's fix, if there was one: the build passed before it, so a
        // fix that breaks it gets its own attempts instead of ending the job with whatever was left
        int used = state.codingAttempts() - state.codingVerifyBase();
        return used < limits.verifyAttempts() ? "fix" : "failing";
    }

    /**
     * Once the build passes: the model reads the files the plan created or changed and checks them
     * against the design guide (instances over static, small classes, ...), answering with a list of
     * issues. The first review's issues go back to implement as a fix (only through files and commands
     * the plan covers, like any fix), then verify runs again and a second review records what is left
     * for the report. No review runs when no design guide applies to the job or the plan changed no
     * file of a guide's language. A failing review never fails the job: it is noted in the report.
     */
    public Map<String, Object> designReview(State state, String instructions) {
        Set<String> languages = guideLanguages(state);
        String guide = guides.text(languages);
        Map<String, String> files = filesToReview(state, languages);
        if (guide.isBlank() || files.isEmpty()) return Map.of(State.CODING_DESIGN_ISSUES, ""); // nothing to review

        int round = state.codingDesignRounds() + 1;
        StringBuilder prompt = new StringBuilder(context(state)).append("\n\n").append(guide)
                .append("\n\nThe code to review (the files this job created or changed):\n");
        files.forEach((path, code) -> prompt.append("\n### ").append(path).append("\n```\n").append(code).append("\n```\n"));
        AiMessage reply;
        try {
            reply = ask(instructions, prompt.toString());
        } catch (RuntimeException e) {
            // The job's work is done and verified; a review that can't run is only noted
            return Map.of(State.CODING_DESIGN_ROUNDS, limits.designRounds(),
                    State.CODING_DESIGN_ISSUES, "The design review could not run: " + reason(MODEL_ERROR, e));
        }
        String issues = designIssues(textOf(reply));
        if (issues == null) {
            // Not a list of issues: don't claim the code is fine, and don't change working code on a guess
            return Map.of(State.CODING_DESIGN_ROUNDS, limits.designRounds(),
                    State.CODING_DESIGN_ISSUES, "The design review's answer could not be read, so the code wasn't checked.");
        }
        return Map.of(State.CODING_DESIGN_ROUNDS, round,
                State.CODING_DESIGN_ISSUES, issues,
                // A fix follows if there are issues: verify attempts after it count from here
                State.CODING_VERIFY_BASE, state.codingAttempts());
    }

    /** Issues found and a fix round left → implement fixes them; otherwise (or after the last round) → report. */
    public static String afterDesignReview(State state, Limits limits) {
        if (stopped(state)) return STOPPED;
        int rounds = state.codingDesignRounds();
        return rounds > 0 && rounds < limits.designRounds() && designFixPending(state) ? "fix" : "done";
    }

    /** Whether the last design review found issues for implement to fix (the build passes, so it's not a verify fix). */
    private static boolean designFixPending(State state) {
        return verifyPassed(state) && state.codingDesignIssues().startsWith("- ");
    }

    /**
     * The files the plan created or modified that a guide is about, read from the trusted folder
     * (folder entries: the matching files under them, skipping build output), up to
     * {@link #MAX_REVIEW_FILES} files and {@link #MAX_REVIEW_CHARS} characters in total.
     */
    private static Map<String, String> filesToReview(State state, Set<String> languages) {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        Path folder = state.workFolder();
        int[] chars = {0};
        for (String entry : state.codingPlan().map(CodingPlan::changedPaths).orElse(List.of())) {
            Path path;
            try {
                path = net.rka.ai.tools.WorkFolder.resolveInside(folder, entry);
            } catch (IOException | RuntimeException e) {
                continue; // outside the folder: never covered by the plan anyway
            }
            List<Path> candidates = new ArrayList<>();
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> folder.relativize(p).toString().replace('\\', '/')
                                    .matches("(?!.*(^|/)(build|target|node_modules|\\.git|\\.gradle)/).*"))
                            .sorted()
                            .forEach(candidates::add);
                } catch (IOException e) {
                    continue;
                }
            } else if (Files.isRegularFile(path)) {
                candidates.add(path);
            }
            for (Path file : candidates) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                if (!DesignGuides.isAbout(relative, languages) || files.containsKey(relative)) continue;
                if (files.size() >= MAX_REVIEW_FILES || chars[0] >= MAX_REVIEW_CHARS) return files;
                try {
                    String code = Files.readString(file);
                    if (code.length() > MAX_REVIEW_CHARS - chars[0]) {
                        code = code.substring(0, MAX_REVIEW_CHARS - chars[0]) + "\n[... the rest of the file is cut ...]";
                    }
                    chars[0] += code.length();
                    files.put(relative, code);
                } catch (IOException | RuntimeException e) {
                    // unreadable (binary, other encoding): not reviewed
                }
            }
        }
        return files;
    }

    /**
     * The review's answer (a JSON array of issues, possibly in a code fence or with text around it) as a
     * Markdown list, one "- `file`: problem → fix" per issue; "" when it found none; null when the answer
     * isn't a JSON array (then nothing is known about the code: not a pass, and no reason to change it).
     */
    static String designIssues(String answer) {
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode issues = JSON.readTree(answer.substring(start, end + 1));
            if (!issues.isArray()) return null;
            List<String> lines = new ArrayList<>();
            for (JsonNode issue : issues) {
                if (lines.size() >= MAX_DESIGN_ISSUES) break;
                String problem = issue.path("problem").asText("").strip();
                if (problem.isEmpty()) continue;
                String file = issue.path("file").asText("").strip();
                String fix = issue.path("fix").asText("").strip();
                String rule = issue.path("rule").asText("").strip();
                lines.add("- " + (file.isEmpty() ? "" : "`" + file + "`: ") + problem
                        + (fix.isEmpty() ? "" : " → " + fix) + (rule.isEmpty() ? "" : " (" + rule + ")"));
            }
            return String.join("\n", lines);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Ends the job: builds the Markdown report (outcome, the approved plan with done and skipped steps
     * marked, the last verify output) and answers the conversation's {@code startCodingJob} call with
     * it, so the report is saved with the conversation and the tool chip shows it. Anything but "done"
     * (a skipped step included) is flagged as an error, so the model sees the job didn't succeed.
     * <p>
     * Every early end comes here through a "stopped" route with its reason in {@code codingStopReason}:
     * a model or tool error, a step over its turns, no valid plan; or in {@code skillStopReason}, when a
     * step threw something else (the skill graph catches it and takes the step's stopped route).
     */
    public Map<String, Object> report(State state) {
        int done = state.codingStep();
        Set<Integer> skipped = state.codingSkippedSteps();
        Optional<CodingPlan> approved = state.codingApprovedPlan();
        // Once implement ran, covered calls may have changed files even in a step that isn't done
        String changed = implementationStarted(state)
                ? doneSteps(done, skipped) + " Changes made so far stay in the folder."
                : "Nothing was changed.";
        String stopReason = !state.codingStopReason().isBlank() ? state.codingStopReason() : state.skillStopReason();
        String outcome;
        boolean success = false;
        if (!stopReason.isBlank()) {
            outcome = "Stopped: " + sentence(stopReason) + " " + changed;
        } else if (!APPROVE.equals(state.codingDecision())) {
            // Like afterPlanApproval: anything but approve counts as deny
            outcome = "Denied: the user denied the plan. " + changed;
        } else if (verifyPassed(state)) {
            boolean noVerify = approved.map(p -> p.verify().isEmpty()).orElse(false);
            if (!skipped.isEmpty()) {
                // Never "every step is implemented": the skipped steps ran none of their planned items
                outcome = "Done with skipped steps: " + skippedSteps(approved, skipped) + ". The other steps are implemented"
                        + (noVerify ? " (the plan has no verify commands)." : " and the verify commands pass.");
            } else {
                outcome = noVerify
                        ? "Done: every step is implemented (the plan has no verify commands)."
                        : "Done: every step is implemented and verified.";
                success = true;
            }
        } else {
            String steps = skipped.isEmpty() ? "every step is implemented"
                    : "every step is implemented except the skipped ones (" + skippedSteps(approved, skipped) + ")";
            outcome = state.codingVerifyBase() > 0
                    // The build passed, then the design review's fixes broke it and couldn't repair it
                    ? "Still failing after the design review's fixes: " + steps + " and the build passed before them,"
                            + " but the verify commands fail since (" + (state.codingAttempts() - state.codingVerifyBase())
                            + " attempts, output below)."
                    : "Still failing after " + state.codingAttempts() + " verify attempts: " + steps
                            + ", but the verify commands fail (output below).";
        }

        StringBuilder report = new StringBuilder(outcome).append('\n');
        Optional<CodingPlan> current = state.codingPlan();
        if (approved.isPresent()) {
            // The plan the user approved last, which is what the job worked by
            report.append("\n## Plan\n\n").append(approved.get().toMarkdown(done, skipped));
            // The job ended on a revision that was denied, or never became a valid plan
            if (current.isPresent() && !current.get().equals(approved.get())) {
                report.append("\n## Revised plan (not approved)\n\n").append(current.get().toMarkdown(done, skipped));
            }
        } else {
            current.ifPresent(plan -> report.append("\n## Plan (not approved)\n\n").append(plan.toMarkdown(0)));
        }
        if (state.codingDesignRounds() > 0) {
            // What the last design review said: remaining issues are worth a look, but don't make the job fail
            String issues = state.codingDesignIssues();
            report.append("\n## Design review\n\n").append(issues.isBlank()
                    ? "The changed code follows the design guide."
                    : (issues.startsWith("- ") ? "Still open after " + state.codingDesignRounds() + " review"
                            + (state.codingDesignRounds() == 1 ? "" : "s") + ":\n\n" : "") + issues)
                    .append('\n');
        }
        String verifyOutput = state.codingVerifyOutput();
        if (!verifyOutput.isBlank()) {
            String cut = verifyOutput.length() <= MAX_REPORT_OUTPUT_CHARS ? verifyOutput
                    : verifyOutput.substring(0, MAX_REPORT_OUTPUT_CHARS)
                            + "\n[... " + (verifyOutput.length() - MAX_REPORT_OUTPUT_CHARS) + " more characters cut ...]";
            report.append("\n## Last verify output\n\n```\n").append(cut).append("\n```\n");
        }

        writePlanFile(state, report.toString()); // the file ends as the job's report: outcome, plan with marks
        // The answer to the job's call joins the conversation; Agent saves it with the other messages
        ToolExecutionResultMessage answer = ToolExecutionResultMessage.builder()
                .id(state.skillCallId()) // the skill layer's call: set by every run's start node
                .toolName(startTool)
                .text(report.toString())
                .isError(!success)
                .build();
        return Map.of(
                State.CODING_REPORT, report.toString(),
                State.MESSAGES, List.of(answer));
    }

    /**
     * The job's plan file: {@code plans/<date>-<time>-<goal words>.md}, or "" when no plans folder is set.
     * The file is rewritten as the job goes (each reviewed plan, then the report), so it always shows the
     * latest state; it is not part of the conversation and never sent to the model.
     */
    private String planFileFor(String goal) {
        if (plansDir == null) return "";
        String words = goal.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (words.length() > 40) words = words.substring(0, 40).replaceAll("-$", "");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
        return plansDir.resolve(stamp + (words.isEmpty() ? "" : "-" + words) + ".md").toAbsolutePath().toString();
    }

    /** Writes the plan file (a heading with the goal, then {@code body}); a failure only loses the file. */
    private static void writePlanFile(State state, String body) {
        String file = state.codingPlanFile();
        if (file.isBlank()) return;
        try {
            Path path = Path.of(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "# Coding job: " + state.codingGoal() + "\n\n" + body);
        } catch (IOException | RuntimeException e) {
            System.getLogger(CodingGraph.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not write plan file " + file, e);
        }
    }

    // --- helpers ---

    /** {@link #NOT_IN_JOB} tools and every skill's tool: a coding job never offers or runs them (no nesting). */
    private boolean notInJob(String tool) {
        return NOT_IN_JOB.contains(tool) || toolCalls.tools().isSkillTool(tool);
    }

    /** Read-only tools, plus runCommand (whose commands {@link #allowedWhileInvestigating} checks one by one). */
    private boolean offeredWhileInvestigating(String tool) {
        if (notInJob(tool)) return false;
        if (tool.equals(RUN_COMMAND)) return true;
        ToolRegistry tools = toolCalls.tools();
        return !tools.requiresApproval(tool) && !tools.writesFiles(tool); // nothing changes before the plan is approved
    }

    /** Read-only tools, runCommand and the file-changing tools: what a step may need, minus {@link #NOT_IN_JOB}. */
    private boolean offeredWhileImplementing(String tool) {
        if (notInJob(tool)) return false;
        return tool.equals(RUN_COMMAND) || !toolCalls.tools().requiresApproval(tool); // runRemoteCommand is NOT_IN_JOB anyway
    }

    /**
     * Why the call may not run under the approved plan, or null when it may: the plan covers it
     * ({@link CodingPlan#covers}), or it is the user's trusted command and no web content came in
     * ({@link State#codingTrusted}). {@link #NOT_IN_JOB} tools never run, whatever the plan or the
     * trusted list says.
     */
    private String whyNotCovered(ToolExecutionRequest call, CodingPlan plan, State state) {
        String which = call.name() + " " + call.arguments();
        if (notInJob(call.name())) return which + ": this tool is not available in a coding job.";
        ToolRegistry tools = toolCalls.tools();
        // The plan alone (no trusted commands): what the user approved explicitly, so web content doesn't change it
        if (plan.covers(call, state.workFolder(), TrustedCommands.of(List.of()), tools)) return null;
        // The user's trusted commands (and tool: entries), but only until web content came in
        if (state.codingTrusted(tools, call)) return null;
        String why = call.name().equals(RUN_COMMAND)
                ? "the plan has no command with this exact text and working directory"
                : "no file entry of the plan allows this change of this path";
        if (state.codingWebContent(tools) && tools.isTrusted(call, state.trustedCommands(), false)) {
            why += " (it is one of the user's trusted commands, but those no longer run unasked: web content was read in this turn)";
        }
        return which + ": " + why + ".";
    }

    /**
     * A probe command (exact text), or the user's trusted command while no web content came in
     * ({@link State#codingTrusted}); any other offered tool is read-only.
     */
    private boolean allowedWhileInvestigating(ToolExecutionRequest call, State state) {
        if (call.name().equals(RUN_COMMAND)) {
            return EnvironmentInfo.isProbe(argument(call, "command")) || state.codingTrusted(toolCalls.tools(), call);
        }
        return offeredWhileInvestigating(call.name());
    }

    /**
     * The state update for a plan or review answer: the answer joins the job's transcript, and either
     * the plan (normalized JSON) or the parse error is stored. A review that parses clears the errors:
     * the plan round succeeded.
     */
    private static Map<String, Object> withPlan(AiMessage reply, String phase, State state) {
        String text = textOf(reply);
        Map<String, Object> updates = new HashMap<>();
        // No tools were offered; should the model call one anyway, keep only the text so no call stays open
        updates.put(State.CODING_MESSAGES, List.of(reply.hasToolExecutionRequests()
                ? AiMessage.from(text.isBlank() ? "(no plan)" : text)
                : reply));
        try {
            CodingPlan plan = CodingPlan.parse(text);
            int done = state.codingStep();
            if (plan.steps().size() < done) {
                // A revision that dropped steps that are done: codingStep would point past the plan's end
                throw new IllegalArgumentException("The plan has " + plan.steps().size() + " steps, but steps 1 to " + done
                        + " are already done: keep all of them, first and in order.");
            }
            if (done > 0 && plan.steps().size() == done && state.codingVerifyOutput().isBlank()) {
                // Exactly the done steps, before verify ran: the step the job was on (and every later one)
                // vanished, and approving it would skip it silently. After a failed verify it is fine:
                // a fix may only need one more command in a done step
                throw new IllegalArgumentException("The plan ends after step " + done + ", but step " + (done + 1)
                        + " is not done yet: keep the pending steps after the done ones.");
            }
            updates.put(State.CODING_PLAN, plan.toJson());
            if (phase.equals(REVIEW_ERROR)) updates.put(State.CODING_PLAN_ERRORS, "");
        } catch (IllegalArgumentException e) {
            // One error per line, so the line count is the number of failed answers
            List<String> errors = new ArrayList<>(state.codingPlanErrors());
            errors.add(phase + e.getMessage().replaceAll("\\s+", " "));
            updates.put(State.CODING_PLAN_ERRORS, String.join("\n", errors));
        }
        return updates;
    }

    /**
     * Why implementTools and verify may not run now, or null when they may: the current plan
     * ({@code codingPlan}, what the calls are checked against) must be the one the user approved last
     * ({@code codingApprovedPlan}). The built-in wiring always asks about a new draft before these steps;
     * this keeps a skill file that wires a second plan step around the approval from running a draft
     * the user never saw (the validator rejects such wiring too, this is the kind's own rule).
     */
    private static String notApproved(State state) {
        Optional<CodingPlan> approved = state.codingApprovedPlan();
        if (approved.isEmpty()) return "there is no approved plan to work by.";
        if (!approved.equals(state.codingPlan())) {
            return "the current plan is not the one the user approved; a changed plan must be approved before it runs.";
        }
        return null;
    }

    /** Whether the last verify run passed (or the plan had nothing to verify); verify wrote the first line. */
    private static boolean verifyPassed(State state) {
        return state.codingVerifyOutput().startsWith(VERIFY_PASSED);
    }

    /** Whether implement ran in this job: its task message is in the job's transcript. */
    private static boolean implementationStarted(State state) {
        return state.codingMessages().stream()
                .anyMatch(m -> m instanceof UserMessage user && user.hasSingleText() && user.singleText().endsWith(DO_IT));
    }

    /**
     * "No step is done." / "Step 1 is done." / "Steps 1 to 3 are done.", followed by
     * "Skipped among them (none of their planned items ran): 2." when steps were skipped.
     */
    private static String doneSteps(int done, Set<Integer> skipped) {
        String steps = done == 0 ? "No step is done." : done == 1 ? "Step 1 is done." : "Steps 1 to " + done + " are done.";
        if (skipped.isEmpty()) return steps;
        return steps + " Skipped among them (none of their planned items ran): "
                + String.join(", ", skipped.stream().map(String::valueOf).toList()) + ".";
    }

    /** "2 (Check the page), 3 (Build)": the skipped steps with their descriptions from the approved plan. */
    private static String skippedSteps(Optional<CodingPlan> plan, Set<Integer> skipped) {
        return String.join(", ", skipped.stream()
                .map(n -> n + plan.filter(p -> n <= p.steps().size())
                        .map(p -> " (" + p.steps().get(n - 1).description() + ")").orElse(""))
                .toList());
    }

    /** Whether a node set {@code codingStopReason}: every router then takes its {@link #STOPPED} route to report. */
    private static boolean stopped(State state) {
        return !state.codingStopReason().isBlank();
    }

    /** The state update that ends the job: the reason, e.g. "model error: Connection refused". */
    private static Map<String, Object> stop(String what, RuntimeException e) {
        return Map.of(State.CODING_STOP_REASON, reason(what, e));
    }

    /** "model error: &lt;message&gt;" on one line (the exception's class when it has no message). */
    private static String reason(String what, RuntimeException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        return what + ": " + message.strip().replaceAll("\\s+", " ");
    }

    /** The reason as a sentence of the report: ends with a full stop. */
    private static String sentence(String reason) {
        return reason.endsWith(".") ? reason : reason + ".";
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // a map of strings always serializes
        }
    }

    /** What every phase is told about the job: goal, trusted folder and environment. */
    private static String context(State state) {
        return "Goal: " + state.codingGoal()
                + "\nTrusted folder (every path is relative to it): " + state.workFolder().toAbsolutePath()
                + "\n\nEnvironment:\n" + state.codingEnvironment();
    }

    /** The languages whose design guide applies to this job (named in goal or findings, or a plan file's extension). */
    private Set<String> guideLanguages(State state) {
        List<String> paths = state.codingPlan().map(CodingPlan::changedPaths).orElse(List.of());
        return guides.languagesFor(state.codingGoal() + "\n" + state.codingFindings(), paths);
    }

    /** The design guide section of a prompt, or "" when no guide applies. */
    private String guide(State state) {
        String text = guides.text(guideLanguages(state));
        return text.isBlank() ? "" : "\n\nWrite code that follows this design guide (the design review checks it):\n\n" + text;
    }

    private static String findings(State state) {
        return "Findings of the investigation:\n" + (state.codingFindings().isBlank() ? "(none)" : state.codingFindings());
    }

    /** On a revision: which steps are done, the user's comment, and why the graph asked for a new plan. */
    private static String revisionNotes(State state) {
        StringBuilder notes = new StringBuilder();
        if (state.codingStep() > 0) {
            notes.append("\n\nSteps 1 to ").append(state.codingStep())
                    .append(" of the previous plan are done: keep them first and unchanged.");
        }
        if (REVISE.equals(state.codingDecision())) {
            notes.append("\n\nThe user asked for changes to the plan")
                    .append(state.codingComment().isBlank() ? "." : ": " + state.codingComment());
        }
        if (!state.codingRevisionReason().isBlank()) {
            notes.append("\n\nThe plan must change because: ").append(state.codingRevisionReason());
        }
        return notes.toString();
    }

    /** One model call without tools: the phase's instructions, then the prompt. */
    private AiMessage ask(String instructions, String prompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(instructions), UserMessage.from(prompt))
                .build();
        return model.chat(request).aiMessage();
    }

    private static String lastAiText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai) return textOf(ai);
        }
        return "";
    }

    private static String textOf(AiMessage message) {
        return Objects.requireNonNullElse(message.text(), ""); // null when the reply only has tool calls
    }

    private static String withoutPhase(String error) {
        return error.startsWith(PLAN_ERROR) ? error.substring(PLAN_ERROR.length())
                : error.startsWith(REVIEW_ERROR) ? error.substring(REVIEW_ERROR.length())
                : error;
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
