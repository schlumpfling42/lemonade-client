package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.CodingPlan.Action;
import net.rka.ai.agent.CodingPlan.Change;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillRuntime;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.WorkFolder;
import net.rka.ai.tracing.AgentLog;
import net.rka.ai.tools.code.java.JavaAnchors;
import net.rka.ai.tools.code.java.JavaBlockEdit;
import net.rka.ai.tools.code.java.JavaClass;
import net.rka.ai.tools.code.java.JavaSourceReader;
import net.rka.ai.tools.code.java.JavaStructure;
import net.rka.ai.tools.code.java.JavaWorkspace;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import net.rka.ai.tools.code.java.ProjectFiles;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;
import net.rka.ai.tools.outline.Section;
import net.rka.ai.tools.outline.SectionEdit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The coding job: the steps of the built-in skill {@code coding} ({@code net/rka/ai/agent/skills/coding.json}), run
 * as the subgraph "skill-coding" of {@link AgentGraph} when {@link #START_TOOL} is called. Each step is a node kind
 * ({@link net.rka.ai.agent.skills.CodingKinds}) implemented by a method here; the file holds the wiring.
 *
 * <h2>A pipeline, not a conversation</h2>
 * <b>The model never calls a tool inside a job.</b> The graph reads, writes, builds and applies; the model answers
 * single questions with text of a known shape, each one validated here, each loop bounded:
 * <pre>
 *   environment -> roadmap (big goal: steps, each worked on alone) -> pickFiles -> gather -> plan --(read: more code)--> plan
 *                                          |
 *                                    planApproval (the user: approve / revise / deny)
 *                                          |
 *                                    setup --(a command failed: plan again, with what failed)--> plan
 *                                      |
 *                                    edit (per change) -> verify --(fail)--> fix -> verify
 *                                                                     | passed         \-(outside the plan)-> planApproval
 *                                                               designReview --(issues)--> fix
 *                                                                     |
 *                                                                  report
 * </pre>
 * <ul>
 *   <li><b>pickFiles</b> (model): the files the work belongs in, from the project map: {@code {"files": [...]}}.</li>
 *   <li><b>gather</b> (graph): the outlines (section ids, kinds, lines) of those files.</li>
 *   <li><b>plan</b> (model): {@link CodingPlan}, from the outlines. The model may ask to {@code read} sections first
 *       (the graph reads them, at most {@code maxReadRounds} times). {@link PlanValidator} checks the plan against the
 *       folder; an invalid one is told back once ({@code maxPlanAttempts}). The valid plan is saved as Markdown.</li>
 *   <li><b>planApproval</b> (user): nothing changes before the plan is approved, and only what it says: the model has
 *       no write path at all, the graph applies the approved changes. The files the plan changes are copied first
 *       (the baseline for the design review's diffs).</li>
 *   <li><b>setup</b> (graph): the approved setup commands, exactly as written.</li>
 *   <li><b>edit</b> (model, once per change): the section's current text and the intent in, the section's new text out
 *       (one code block); the graph applies it with {@link SectionEdit}. Every change is recorded in the {@link Ledger}.</li>
 *   <li><b>verify</b> (graph): the approved verify commands. Positions in their output ({@link BuildErrors}) are mapped
 *       to the sections that hold them.</li>
 *   <li><b>fix</b> (model, once per failing section, at most {@value #MAX_FIX_SECTIONS} per round): the section and its
 *       messages in, the section's new text out, applied like an edit. An error in a file the plan doesn't change goes
 *       back to the user as a revised plan (at most {@value #MAX_REVISIONS} times), never edited silently.</li>
 *   <li><b>designReview</b> (model, once): the diff of what the job changed against the design guide; its issues are fixed
 *       by the same fix step.</li>
 *   <li><b>report</b> (graph): the outcome, the plan with its changes marked, the design review and the last verify
 *       output; it answers the conversation's {@code startCodingJob} call and finishes the plan file.</li>
 * </ul>
 * Every early end (a model error, no valid plan, a failed setup command) sets {@code codingStopReason}; every router
 * then takes its {@code stopped} route to the report, so the job always answers its call. The job's keys are reset by
 * {@code environment}. What a skill file can't change: every rule above lives in these methods, whatever the wiring, and
 * the validator only accepts files in which {@code setup}, {@code edit}, {@code fix} and {@code verify} are reached
 * through planApproval's {@code approve}.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Structure-aware sections for languages other than Java and Markdown (others are edited by blocks of lines).</li>
 *   <li>Visual checking of designs (screenshots of the result).</li>
 *   <li>A Stop button for a running job.</li>
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
    /** Times the plan may ask to read more code before it must plan from what it has. */
    static final int MAX_READ_ROUNDS = 2;
    /** Model answers for the plan that may be unusable (not a plan, or not valid) before the job ends. */
    static final int MAX_PLAN_ATTEMPTS = 2;
    /** Model answers for one change that may be unusable (no code, section gone) before the change is marked failed. */
    static final int MAX_EDIT_ATTEMPTS = 2;
    /** Verify runs per job; after the last failing one the job reports. */
    static final int MAX_VERIFY_ATTEMPTS = 3;
    /** Design reviews per job: one review, its issues fixed once. */
    static final int MAX_DESIGN_ROUNDS = 1;
    /** Times the job goes back to the user because a fix needs a file the plan doesn't change, before it stops. */
    static final int MAX_REVISIONS = 2;
    /** More files than this with problems outside the plan: the verify command is wrong for the project, the plan is not revised for them. */
    static final int MAX_OUTSIDE_FINDINGS = 3;
    /** Sections one fix round repairs at most. */
    static final int MAX_FIX_SECTIONS = 4;
    /** Files the model may pick, and how much of their outlines (characters) go into the plan's request. */
    private static final int MAX_PICKS = 6;
    /** The reasoning effort of the steps that decide (where the change belongs, what it is, whether it does what was asked); the rest run low. */
    private static final String DECIDING_EFFORT = net.rka.ai.config.Env.get("CODING_DECIDING_EFFORT", "medium");
    /** The plan thinks less than the checks of it: with the know-how in front of it a plan at medium effort spent 2,000 tokens (2.5 minutes) thinking, twice. */
    /** The edit and the repair write code the plan and the know-how already shaped: a medium effort made a repair think for 2,400 tokens (three minutes) three times. */
    private static final String WRITE_EFFORT = net.rka.ai.config.Env.get("CODING_WRITE_EFFORT", "low");
    private static final String PLAN_EFFORT = net.rka.ai.config.Env.get("CODING_PLAN_EFFORT", "low");
    private static final String SUCCESS = "success";
    private static final String FAILED = "failed";
    private static final String UNCHANGED = "unchanged";
    private static final int MAX_CODE_CHARS = 20_000;
    private static final int SMALL_FILE_CHARS = 3_000; // a picked file that is not Java and this short is shown whole to the plan
    private static final int MAX_PLACES = 6;
    private static final int MAX_PLACES_CHARS = 9_000;
    /** How much code the sections the plan asked to read may add (characters), and the longest one section may be. */
    private static final int MAX_READ_CHARS = 24_000;
    private static final int MAX_SECTION_CHARS = 12_000;
    /** What the design review reads at most: files and characters of code. */
    private static final int MAX_REVIEW_FILES = 25;
    private static final int MAX_REVIEW_CHARS = 60_000;
    /** At most this many design issues per review, most important first. */
    private static final int MAX_DESIGN_ISSUES = 5;
    /** A review of fewer changed lines than this finds less than it costs (a small model invents issues): it is skipped. */
    static final int MIN_REVIEW_LINES = 40;
    /** The longest a failing section's messages may be in a fix request (characters). */
    private static final int MAX_MESSAGE_CHARS = 2_000;

    private static final String RUN_COMMAND = "runCommand";
    /** The route every router takes to report once a node set {@code codingStopReason}. */
    private static final String STOPPED = "stopped";
    private static final String MODEL_ERROR = "model error";
    private static final String TOOL_ERROR = "tool error";
    // First line of codingVerifyOutput, written by verify (not by a command), so the router can trust it
    private static final String VERIFY_PASSED = "Verify passed";
    private static final String VERIFY_FAILED = "Verify failed";
    private static final String VERIFY_UNAVAILABLE = "Verify could not run";
    /** How much of the last verify output the report keeps. */
    private static final int MAX_REPORT_OUTPUT_CHARS = 4_000;
    /** What a fix answers when the failing place is only a symptom and the cause is elsewhere. */
    private static final String SKIP = "SKIP";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the other calls of the reply that started a job are answered with (by the skill's start node). */
    public static final String NOT_RUN = "Not run: a coding job started; ask again afterwards.";

    /**
     * What every coding job of the app runs with, whatever skill file uses the coding kinds.
     *
     * @param environment    the cached environment summary for the prompts
     * @param timeoutSeconds timeout of the job's commands ({@code CODING_COMMAND_TIMEOUT_SECONDS})
     * @param plansDir       where each job's plan is saved as Markdown for review ({@code ~/chat/plans}); null for none
     * @param guides         the design guides the edits follow and the design review checks; {@link DesignGuides#NONE}
     *                       turns the design review off
     * @param workspaces     the Java workspaces: in a Java project the job reads and changes the {@code .java} files in memory
     *                       and writes them at the end ({@link ProjectFiles})
     */
    public record Setup(EnvironmentInfo environment, int timeoutSeconds, Path plansDir, DesignGuides guides, JavaWorkspaces workspaces) {
        /** The app's workspaces. */
        public Setup(EnvironmentInfo environment, int timeoutSeconds, Path plansDir, DesignGuides guides) {
            this(environment, timeoutSeconds, plansDir, guides, JavaWorkspaces.shared());
        }

        /** Without plan files and design guides, with workspaces of its own that don't watch (tests). */
        Setup(EnvironmentInfo environment, int timeoutSeconds) {
            this(environment, timeoutSeconds, null, DesignGuides.NONE, new JavaWorkspaces(false));
        }
    }

    /**
     * The job's limits, from the skill file's {@code limits} ({@link SkillFile.Limits#extra}); a limit the file leaves
     * out has the default of the constant above.
     */
    public record Limits(int readRounds, int planAttempts, int editAttempts, int verifyAttempts, int designRounds) {
        /** The names the limits have in a skill file's {@code limits}. */
        public static final String READ_ROUNDS = "maxReadRounds";
        public static final String PLAN_ATTEMPTS = "maxPlanAttempts";
        public static final String EDIT_ATTEMPTS = "maxEditAttempts";
        public static final String VERIFY_ATTEMPTS = "maxVerifyAttempts";
        public static final String DESIGN_ROUNDS = "maxDesignRounds";

        public static Limits of(SkillFile.Limits limits) {
            Map<String, Integer> extra = limits.extra();
            return new Limits(extra.getOrDefault(READ_ROUNDS, MAX_READ_ROUNDS),
                    extra.getOrDefault(PLAN_ATTEMPTS, MAX_PLAN_ATTEMPTS),
                    extra.getOrDefault(EDIT_ATTEMPTS, MAX_EDIT_ATTEMPTS),
                    extra.getOrDefault(VERIFY_ATTEMPTS, MAX_VERIFY_ATTEMPTS),
                    extra.getOrDefault(DESIGN_ROUNDS, MAX_DESIGN_ROUNDS));
        }
    }

    /** Something to repair: a section and what is wrong with it (compiler messages or a design issue). */
    record Finding(String file, String section, String text) {
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
     * The job's steps for one skill: its model, tools (only used to run the approved commands) and limits from the
     * skill's runtime, the rest from the app ({@code setup}). Built once per node when the skill's subgraph is built.
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
        this.workspaces = setup.workspaces();
    }

    /** The project's files as the job reads and writes them: Java files in memory, others on disk. */
    private ProjectFiles files(State state) {
        return new ProjectFiles(state.workFolder(), workspaces);
    }

    // ---- environment ----

    /**
     * Starts the job: goal and call id from the call that started the skill ({@code skillCallId}, found by the skill's
     * start node, which also answered the reply's other calls), fresh job keys, the environment summary and the
     * folder's files ({@link ProjectMap}).
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
        updates.put(State.CODING_PROJECT_MAP, ProjectMap.of(state.workFolder(), goal));
        // Reset what an earlier job of this turn may have left; "" and 0 mean "not set"
        for (String key : List.of(State.CODING_PICKS, State.CODING_APPROACH, State.CODING_CODE, State.CODING_READ, State.CODING_PLAN,
                State.CODING_DECISION, State.CODING_COMMENT, State.CODING_REVISION_REASON, State.CODING_VERIFY_OUTPUT,
                State.CODING_REPORT, State.CODING_APPROVED_PLAN, State.CODING_LEDGER, State.CODING_STOP_REASON,
                State.CODING_DESIGN_ISSUES, State.CODING_BASELINE, State.CODING_FIXES, State.CODING_GOOD, State.CODING_REVISE_KIND, State.CODING_POINTS,
                State.CODING_ANSWER, State.CODING_OUTCOME, State.CODING_EXPLANATION, State.CODING_PLAN_CHECK)) {
            updates.put(key, "");
        }
        for (String key : List.of(State.CODING_ATTEMPTS, State.CODING_DESIGN_ROUNDS, State.CODING_READ_ROUNDS,
                State.CODING_REVISIONS, State.CODING_SETUP_DONE, State.CODING_READ_PENDING, State.CODING_PLAN_ROUNDS,
                State.CODING_DESIGN_UNDONE, State.CODING_PLAN_CHECKS)) {
            updates.put(key, 0);
        }
        return updates;
    }

    /**
     * The cached or freshly probed environment. A failure here (cache unreadable in an unexpected way, probe runner
     * crashing) must not lose the job: the plan is then made without the tool versions.
     */
    private String environmentSummary() {
        try {
            return environment.summary();
        } catch (RuntimeException e) {
            return "Environment check failed (" + e.getMessage() + "); the tool versions are unknown.";
        }
    }

    // ---- pickFiles, gather ----

    /**
     * Asks which existing files the work belongs in, from the project map in the job's context. An empty folder (a
     * new project) or an empty list (the work is only new files) is fine; an answer that isn't JSON is told back once.
     * Paths that don't exist are dropped (a bare file name that fits one file in the folder is accepted).
     */
    public Map<String, Object> pickFiles(State state, String instructions) {
        // The analysis already said how the job goes; "free" needs no files to start from, so no model call
        if ("free".equals(state.codingRoute())) return Map.of(State.CODING_PICKS, "", State.CODING_APPROACH, "free");
        // A new project has no existing file to start from: asking the model to pick from the trusted folder (which holds other
        // projects) put 7,800 tokens of unrelated code into the plan and cost a model call
        if (looksLikeNewProject(state.codingGoal())) return Map.of(State.CODING_PICKS, "", State.CODING_APPROACH, "");
        if (state.codingProjectMap().startsWith("(")) return Map.of(State.CODING_PICKS, "", State.CODING_APPROACH, state.codingRoute().equals("study") ? "study" : "");
        String problem = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            String prompt = problem.isEmpty() ? "" : "Your last answer could not be used: " + problem
                    + "\nAnswer with the JSON object only.";
            AiMessage reply;
            try {
                reply = ask(JobPhase.PICK_FILES, state, instructions, prompt);
            } catch (RuntimeException e) {
                return stop(MODEL_ERROR, e);
            }
            try {
                String answer = textOf(reply);
                List<String> files = parsePicks(answer, state.workFolder());
                // How the job goes comes from the analysis when it said; the files are what this step is for
                String approach = state.codingRoute().isEmpty() ? approachOf(answer) : state.codingRoute().equals("study") ? "study" : "";
                return Map.of(State.CODING_PICKS, String.join("\n", files), State.CODING_APPROACH, approach);
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            }
        }
        return Map.of(State.CODING_STOP_REASON, "could not tell which files the work belongs in (" + problem
                + "): name the file in your request.");
    }

    private static final java.util.regex.Pattern NEW_PROJECT = java.util.regex.Pattern.compile(
            "(?i)\\b(create|make|generate|scaffold|bootstrap|initiali[sz]e|set ?up|start)\\b[^.\\n]{0,60}\\b(new|fresh)\\b[^.\\n]{0,40}"
                    + "\\b(project|app|application|service|module|starter)\\b|\\b(new|fresh)\\b[^.\\n]{0,30}\\b(project|application|starter)\\b");

    /**
     * Whether the goal asks for a new project (a "new Spring Boot project", "create a new app"): then there is no existing file to pick
     * and nothing to study. A goal that names an existing project ("add to the existing project") is not one.
     */
    static boolean looksLikeNewProject(String goal) {
        return goal != null && NEW_PROJECT.matcher(goal).find() && !goal.toLowerCase(java.util.Locale.ROOT).contains("existing");
    }

    /** Work to plan → gather the outlines; open-ended work → free; a model error or no usable answer → report. */
    public static String afterPickFiles(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingFree() ? "free" : state.codingStudy() ? "study" : "done";
    }

    /**
     * The approach the answer names: "free" (find out as it goes: no plan), "study" (understand the existing code first,
     * then plan), else "" (plan right away).
     */
    static String approachOf(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) return "";
        try {
            String approach = JSON.readTree(answer.substring(start, end + 1)).path("approach").asText().strip().toLowerCase(java.util.Locale.ROOT);
            return approach.equals("free") || approach.equals("study") ? approach : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** The existing files of the answer ({@code {"files": [...]}} or a bare array), relative, at most {@value #MAX_PICKS}. */
    static List<String> parsePicks(String answer, Path folder) {
        int object = answer.indexOf('{');
        int array = answer.indexOf('[');
        boolean useObject = object >= 0 && (array < 0 || object < array);
        int start = useObject ? object : array;
        int end = useObject ? answer.lastIndexOf('}') : answer.lastIndexOf(']');
        if (start < 0 || end <= start) throw new IllegalArgumentException("it has no JSON object with \"files\".");
        JsonNode root;
        try {
            root = JSON.readTree(answer.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("it is not valid JSON.");
        }
        JsonNode list = root.isArray() ? root : root.get("files");
        if (list == null || !list.isArray()) throw new IllegalArgumentException("it has no \"files\" array.");
        Set<String> files = new LinkedHashSet<>();
        for (JsonNode name : list) {
            if (files.size() >= MAX_PICKS) break;
            if (!name.isTextual()) continue;
            String relative = existing(name.asText().strip(), folder);
            if (relative != null) files.add(relative);
        }
        return new ArrayList<>(files);
    }

    /** The file's path relative to the folder when it exists inside it, or when its bare name fits exactly one file. */
    private static String existing(String name, Path folder) {
        if (name.isBlank()) return null;
        try {
            Path root = WorkFolder.resolveInside(folder, "");
            Path file = WorkFolder.resolveInside(folder, name);
            if (Files.isRegularFile(file)) return root.relativize(file).toString().replace('\\', '/');
            if (name.contains("/") || name.contains("\\")) return null;
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> same = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(name))
                        .filter(p -> root.relativize(p).getNameCount() > 0 && skippedFree(root, p))
                        .limit(2).toList();
                return same.size() == 1 ? root.relativize(same.get(0)).toString().replace('\\', '/') : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean skippedFree(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (FileTools.SKIPPED_FOLDERS.contains(name) || name.startsWith(".")) return false;
        }
        return true;
    }

    /**
     * In a Java project, what the workspace knows about the types of a picked file: which other types use them and which
     * types they use (the project's cross-references, from its index), so the plan sees what a change reaches. "" outside a
     * Java project or when the file has no types.
     */
    private String references(State state, String path) {
        Optional<JavaWorkspace> workspace = workspaces.workspace(state.workFolder());
        if (workspace.isEmpty() || !path.endsWith(".java")) return "";
        StringBuilder out = new StringBuilder();
        for (JavaClass type : workspace.get().typesIn(path)) {
            String qualified = type.qualifiedName();
            java.util.Set<String> users = workspace.get().usersOf(qualified);
            java.util.Set<String> uses = new java.util.TreeSet<>();
            workspace.get().referencesOf(qualified).forEach(r -> {
                if (!r.toType().equals(qualified) && !r.toType().startsWith(qualified + ".")) uses.add(r.toType());
            });
            if (users.isEmpty() && uses.isEmpty()) continue;
            out.append("\n").append(qualified);
            if (!users.isEmpty()) out.append(" is used by: ").append(shortList(users));
            if (!uses.isEmpty()) out.append(users.isEmpty() ? " uses: " : "; uses: ").append(shortList(uses));
        }
        return out.isEmpty() ? "" : "\nCross-references of the types in this file:" + out;
    }

    private static String shortList(java.util.Collection<String> names) {
        List<String> all = new ArrayList<>(names);
        String shown = String.join(", ", all.subList(0, Math.min(12, all.size())));
        return all.size() > 12 ? shown + ", … (" + (all.size() - 12) + " more)" : shown;
    }

    /** Builds the outlines of the picked files (no model): what the plan is made from. */
    public Map<String, Object> gather(State state) {
        StringBuilder code = new StringBuilder();
        ProjectFiles files = files(state).fresh();
        for (String path : state.codingPicks()) {
            Path file = state.workFolder().resolve(path);
            String text;
            try {
                byte[] bytes = files.readBytes(file);
                text = Outlines.of(file.getFileName().toString(), bytes).render(path, null) + references(state, path);
                // A small file that is not Java is shown whole: a patch of it copies its lines, and an outline of a style sheet or a build
                // file says too little to copy them from
                String whole = new String(bytes, StandardCharsets.UTF_8);
                if (!path.endsWith(".java") && whole.length() <= SMALL_FILE_CHARS && whole.indexOf(0) < 0) {
                    text += "\nThe whole file " + path + ":\n```\n" + whole.stripTrailing() + "\n```";
                }
            } catch (IOException | RuntimeException e) {
                continue; // vanished or not text: left out
            }
            if (code.length() + text.length() > MAX_CODE_CHARS) {
                code.append("\n(more files were picked, but their outlines don't fit; ask to read them)\n");
                break;
            }
            code.append(text).append("\n\n");
        }
        // The parts the research read whole, as they are now: the plan is made from the code itself, not from notes about it
        String readWhole = readWhole(state, files);
        if (!readWhole.isBlank()) {
            code.append("Code the research read, whole parts as they are now (a change names the section by its id; copy the lines of a patch from this code):\n\n")
                    .append(readWhole).append("\n\n");
        }
        // The places the goal is about, with their code: what the plan is made from, besides the outlines
        List<Locator.Candidate> found = new ArrayList<>(workspaces.workspace(state.workFolder()).map(workspace -> Locator.find(workspace, state.codingGoal(), MAX_PLACES)).orElse(List.of()));
        found.addAll(Locator.findInTextFiles(state.workFolder(), state.codingGoal(), MAX_PLACES));
        found.sort(java.util.Comparator.comparingInt(Locator.Candidate::score).reversed());
        String places = Locator.render(found.subList(0, Math.min(MAX_PLACES, found.size())), MAX_PLACES_CHARS);
        if (!places.isBlank()) {
            code.append("Likely places for this work, with their code as it is now (a change names the section by the id shown here; a place without an id is part of a file that is not Java: patch the file without a section, copying the lines of the patch from this code; the files a change needs may be of any kind: style sheets, build files, configuration, text, code):\n\n")
                    .append(places).append("\n\n");
        }
        return Map.of(State.CODING_CODE, code.toString().strip(),
                State.KNOWLEDGE, Knowledge.afterPaths(state.knowledge(), state.codingPicks(), state.workFolder(), workspaces)); // the versions the outlines show
    }

    private static final java.util.regex.Pattern READ_HEADER =
            java.util.regex.Pattern.compile("^\\[(.+?) · (.+?) · [^·]+ · lines \\d+–\\d+ of \\d+ · md5 [0-9a-f]+ · complete]");
    private static final int MAX_WHOLE_CHARS = 24_000;

    /**
     * The parts the research step read whole (its {@code readSection} calls that came back "complete"), as they are in the files now,
     * each as {@code --- path · id · lines a–b} and its code. A part that was read twice is shown once.
     */
    private String readWhole(State state, ProjectFiles files) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (dev.langchain4j.data.message.ChatMessage message : state.allSkillMessages()) {
            if (!(message instanceof ToolExecutionResultMessage result) || !"readSection".equals(result.toolName())) continue;
            java.util.regex.Matcher header = READ_HEADER.matcher(result.text() == null ? "" : result.text());
            if (!header.find() || !seen.add(header.group(1) + "\u0000" + header.group(2))) continue;
            try {
                Path file = state.workFolder().resolve(header.group(1));
                byte[] bytes = files.readBytes(file);
                Outline outline = Outlines.of(file.getFileName().toString(), bytes);
                Section section = outline.find(header.group(2));
                String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
                String text = String.join("\n", java.util.Arrays.copyOfRange(lines, section.start() - 1, Math.min(section.end(), lines.length)));
                String block = "--- " + header.group(1) + " · " + section.id() + " · lines " + section.start() + "–" + section.end() + "\n" + text + "\n\n";
                if (out.length() + block.length() > MAX_WHOLE_CHARS) break;
                out.append(block);
            } catch (IOException | RuntimeException e) {
                // gone or renamed since: left out
            }
        }
        return out.toString().stripTrailing();
    }

    // ---- plan ----

    /**
     * Asks for the plan JSON from the project map and the outlines (on a revision also from the previous plan, the
     * user's comment or the reason the graph asked again). A plan that only asks to {@code read} sections gets them
     * (no model) and is asked again, at most {@code maxReadRounds} times. A plan that can't be used is told back
     * ({@code maxPlanAttempts} answers in all); a valid one is saved as Markdown for review.
     */
    public Map<String, Object> plan(State state, String instructions) {
        // A plan for new files only has no use for the rules about sections, patches and anchors (3,700 of 5,800 characters)
        instructions = planInstructions(instructions, !state.codingPicks().isEmpty() || state.codingApprovedPlan().isPresent());
        files(state).flush(); // the plan is checked against the files: a repair that changed Java files in memory is written first
        state = withFreshOutlines(state);
        Optional<CodingPlan> previous = state.codingPlan();
        String problem = "";
        String lastAnswer = "";
        for (int attempt = 1; attempt <= limits.planAttempts(); attempt++) {
            AiMessage reply;
            boolean cutOff = false;
            try {
                // The step that decides what changes. A plan is a list: a reply that fills the limit is code or thinking that does not belong in it
                ChatResponse response = askResponse(JobPhase.PLAN, state, instructions, planPrompt(state, previous, problem), PLAN_EFFORT, PLAN_MAX_TOKENS,
                        PlanFunction.spec());
                reply = response.aiMessage();
                cutOff = response.tokenUsage() != null && response.tokenUsage().outputTokenCount() != null
                        && response.tokenUsage().outputTokenCount() >= PLAN_MAX_TOKENS - 8;
            } catch (RuntimeException e) {
                return stop(MODEL_ERROR, e);
            }
            if (cutOff) {
                problem = "Your plan was cut off after " + PLAN_MAX_TOKENS + " tokens: it is far too long. Write it again, much shorter: no code, one short sentence of"
                        + " intent per change, a patch only for a change of a few lines. The edit step writes the code.";
                AgentLog.current().line("✗ plan cut off at " + PLAN_MAX_TOKENS + " tokens: written again");
                continue;
            }
            lastAnswer = PlanFunction.planText(reply);
            try {
                CodingPlan plan = CodingPlan.parse(lastAnswer);
                if (plan.changes().isEmpty() && !plan.read().isEmpty()) {
                    if (state.codingReadRounds() < limits.readRounds()) return readMore(state, plan);
                    problem = "You can't read more code: write the changes now, from the code you have.";
                    continue;
                }
                PlanValidator.Result result = PlanValidator.validate(plan, state.workFolder());
                if (!result.valid()) {
                    // The refused plan goes back with the problems: without it the model has to write 12 changes again from nothing
                    // (a minutes-long call), and it gave up with an empty plan instead of fixing the one change that was refused.
                    problem = String.join(" ", result.errors()) + "\nThe plan you submitted, of which only the changes named above are wrong"
                            + " (keep the others as they are):\n" + lastAnswer;
                    AgentLog.current().line("✗ plan refused (attempt " + attempt + " of " + limits.planAttempts() + ", " + plan.changes().size()
                            + " changes): " + AgentLog.oneLine(problem, 300));
                    continue;
                }
                List<String> unused = state.codingApprovedPlan().isPresent() ? List.of() : unusedCreations(state, result.plan());
                // A new class nothing uses is probably dead code: the plan is written again (a rewrite of a big plan takes minutes).
                // On the last attempt the plan goes to the user with the remark, instead of the job stopping with nothing.
                if (!unused.isEmpty() && attempt < limits.planAttempts()) {
                    problem = String.join(" ", unused);
                    AgentLog.current().line("✗ plan sent back (attempt " + attempt + "): " + AgentLog.oneLine(problem, 300));
                    continue;
                }
                if (REVISE.equals(state.codingDecision()) && !state.codingPoints().isBlank() && previous.isPresent()
                        && previous.get().changes().equals(result.plan().changes())) {
                    problem = "This plan has the same changes as the previous one, but the user sent it back. Change it for their points:\n"
                            + state.codingPoints();
                    continue;
                }
                Map<String, Object> updates = new HashMap<>();
                List<String> remarks = new ArrayList<>(result.remarks());
                remarks.addAll(unused);
                if (!remarks.isEmpty()) {
                    updates.put(State.CODING_REVISION_REASON, "look at this before you approve: " + String.join(" ", remarks));
                }
                CodingPlan next = keepDone(state, result.plan(), updates);
                updates.put(State.CODING_PLAN, next.toJson());
                updates.put(State.CODING_READ_PENDING, 0);
                updates.put(State.CODING_PLAN_ROUNDS, state.codingPlanRounds() + 1);
                // What you'll be asked about, saved as Markdown for review
                boolean revision = state.codingApprovedPlan().isPresent() || REVISE.equals(state.codingDecision());
                Ledger kept = Ledger.parse((String) updates.getOrDefault(State.CODING_LEDGER, ""));
                writePlanFile(state, "Status: waiting for your approval\n\n" + new PlanReview(next, kept.indexes(Ledger.Status.APPLIED), Set.of(),
                        state.codingRevisionReason(), state.codingComment(), revision).toMarkdown());
                return updates;
            } catch (IllegalArgumentException e) {
                problem = e.getMessage().replaceAll("\\s+", " ");
                AgentLog.current().line("✗ plan not usable (attempt " + attempt + " of " + limits.planAttempts() + "): " + AgentLog.oneLine(problem, 300));
            }
        }
        return Map.of(State.CODING_STOP_REASON, "no valid plan after " + limits.planAttempts() + " attempts: "
                + (problem.isBlank() ? "the answer was not a plan" : problem));
    }

    /**
     * The plan's instructions for this job: the parts between {@code [[existing]]} and {@code [[/existing]]} (how to name a section, patch
     * or replace one, ask to read more code) stay only when existing code is involved; the markers never reach the model.
     */
    static String planInstructions(String instructions, boolean existingCode) {
        if (existingCode) return instructions.replace("[[existing]]", "").replace("[[/existing]]", "");
        return instructions.replaceAll("(?s)\\[\\[existing]].*?\\[\\[/existing]]", "").replaceAll("\\n{2,}", "\n");
    }

    // ---- planCheck ----

    /** The most tokens a plan may take to write (a good plan is a few hundred; at about 11 tokens a second 3,500 are over five minutes). */
    static final int PLAN_MAX_TOKENS = 6_000;

    /** Plan checks per answer of the user: after this many the plan goes to the user as it is. */
    static final int MAX_PLAN_CHECKS = 2;
    private static final int MAX_REFERENCE_FILES = 2_000;
    private static final long MAX_REFERENCE_BYTES = 300_000;

    /**
     * Checks a plan before the user sees it. First without a model: a Java class the plan creates that nothing in the plan or the
     * project mentions is dead code, whatever the request said (a "plugin" nobody registers). Then one model call at high effort
     * reads the request, the research notes and the plan: is this the right approach (an existing mechanism ignored, a request word
     * the plan never addresses), and does it cover every point of the request? The answer is {@code ok}, {@code problems: …} (the
     * plan step writes the plan again with them) or {@code ask: …} (only the user can decide: the plan goes to the user with the
     * question). A model that cannot be reached or does not answer in that form counts as ok.
     */
    public Map<String, Object> planCheck(State state, String instructions) {
        Optional<CodingPlan> current = state.codingPlan();
        if (current.isEmpty() || state.codingPlanChecks() >= MAX_PLAN_CHECKS) return Map.of(State.CODING_PLAN_CHECK, "ok");
        CodingPlan plan = current.get();
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_PLAN_CHECKS, state.codingPlanChecks() + 1);
        // (a class nothing uses is already sent back, or remarked on, by plan() itself)
        String verdict = "ok";
        {
            StringBuilder prompt = new StringBuilder("The user's request:\n").append(state.codingGoal()).append("\n\nThe plan:\n").append(plan.toMarkdown());
            try {
                String answer = textOf(ask(JobPhase.PLAN_CHECK, state, instructions, prompt.toString(), DECIDING_EFFORT)).strip();
                String lower = answer.toLowerCase(java.util.Locale.ROOT);
                String said = answer.substring(answer.indexOf(':') >= 0 ? answer.indexOf(':') + 1 : Math.min(answer.length(), 4)).strip();
                if (lower.startsWith("problems") && !said.isBlank()) verdict = "problems: " + said;
                else if (lower.startsWith("ask") && !said.isBlank()) verdict = "ask: " + said;
            } catch (RuntimeException e) {
                // not checked: the plan goes on to the user
            }
        }
        updates.put(State.CODING_PLAN_CHECK, verdict);
        if (verdict.startsWith("ask:")) {
            String before = state.codingRevisionReason();
            updates.put(State.CODING_REVISION_REASON, (before.isBlank() ? "" : before + " ") + "the plan check needs a decision from you: "
                    + verdict.substring("ask:".length()).strip() + " Approve to keep the plan as it is, or send a comment to change it.");
        }
        return updates;
    }

    /** Problems found → the plan step again; a question → the user; otherwise the user is asked about the plan. */
    public static String afterPlanCheck(State state) {
        String check = state.codingPlanCheck();
        return check.startsWith("problems:") ? "revise" : check.startsWith("ask:") ? "ask" : "ok";
    }

    /**
     * One sentence for every Java class the plan creates that is mentioned nowhere else: not by another change (its intent or
     * section), not in the summary or approach, not in any Java file of the project. Tests, main classes and package or module
     * descriptors stand alone by nature and are left out.
     */
    static List<String> unusedCreations(State state, CodingPlan plan) {
        List<String> problems = new ArrayList<>();
        Path root = state.workFolder();
        String text = plan.summary() + " " + plan.approach();
        if (newProjectFolder(root, plan)) return problems; // every file is new and in one new folder: the project is a whole, nothing in it is dead
        for (Change change : plan.changes()) {
            if (change.action() != Action.CREATE || !change.file().endsWith(".java")) continue;
            String name = Path.of(change.file()).getFileName().toString().replaceFirst("\\.java$", "");
            if (name.matches(".*(Test|Tests|IT|Main|App|Application)$") || name.equals("package-info") || name.equals("module-info")
                    || change.file().replace('\\', '/').contains("src/test/")) continue;
            java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b");
            boolean named = word.matcher(text).find();
            for (Change other : plan.changes()) {
                if (other != change && (word.matcher(other.intent()).find() || word.matcher(other.section()).find()
                        || word.matcher(other.patch()).find() || word.matcher(other.anchor()).find())) named = true;
            }
            if (!named && !mentionedInProject(root, word, root.resolve(change.file()).normalize())) {
                problems.add("The plan creates `" + change.file() + "`, but no other change and no file of the project uses " + name
                        + ": it would be dead code. Add the change that uses it where the request needs it (the class, registry or call that "
                        + "makes it take effect), or use what the project already has; if it really stands alone, say so in the summary.");
            }
        }
        return problems;
    }

    /** Whether the plan only creates files, all in one folder that does not exist yet: a new project. */
    static boolean newProjectFolder(Path root, CodingPlan plan) {
        if (plan.changes().isEmpty()) return false;
        Set<String> tops = new java.util.HashSet<>();
        for (Change change : plan.changes()) {
            String file = change.file().replace('\\', '/');
            if (change.action() != Action.CREATE || !file.contains("/")) return false;
            tops.add(file.substring(0, file.indexOf('/')));
        }
        return tops.size() == 1 && !Files.exists(root.resolve(tops.iterator().next()));
    }

    /** Whether some Java file of the folder other than {@code except} contains the word (a folder it cannot read counts as yes). */
    private static boolean mentionedInProject(Path root, java.util.regex.Pattern word, Path except) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java") && !p.normalize().equals(except))
                    .filter(p -> root.relativize(p).iterator().hasNext() && !skipped(root, p))
                    .limit(MAX_REFERENCE_FILES)
                    .anyMatch(p -> {
                        try {
                            return Files.size(p) <= MAX_REFERENCE_BYTES && word.matcher(Files.readString(p, StandardCharsets.UTF_8)).find();
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private static boolean skipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (FileTools.SKIPPED_FOLDERS.contains(name) || name.startsWith(".")) return true;
        }
        return false;
    }

    /**
     * A revised plan starts with the changes already made, as the approved plan listed them (they stay done: the
     * ledger keeps them applied), followed by the new plan's changes without the ones that repeat a made change (same
     * file, action and section, and an intent that says the same). The model is asked for the changes still to do; a
     * model that lists the whole job again doesn't make the job do it again. The ledger for the result goes into
     * {@code updates}. A first plan is returned as it is.
     */
    private static CodingPlan keepDone(State state, CodingPlan plan, Map<String, Object> updates) {
        Optional<CodingPlan> approved = state.codingApprovedPlan();
        if (approved.isEmpty()) return plan;
        Ledger old = state.codingLedger().resizedTo(approved.get().changes().size());
        List<CodingPlan.Change> done = new ArrayList<>();
        for (int i : old.indexes(Ledger.Status.APPLIED)) done.add(approved.get().changes().get(i));
        if (done.isEmpty()) return plan;
        List<CodingPlan.Change> changes = new ArrayList<>(done);
        for (CodingPlan.Change change : plan.changes()) {
            if (done.stream().noneMatch(d -> repeats(d, change))) changes.add(change);
        }
        Ledger ledger = new Ledger(List.of()).resizedTo(changes.size());
        for (int i = 0; i < done.size(); i++) ledger = ledger.with(i, Ledger.Status.APPLIED, "");
        updates.put(State.CODING_LEDGER, ledger.toJson());
        return plan.with(plan.read(), plan.setup(), changes, plan.verify());
    }

    /** Whether {@code again} is the made change {@code done} listed once more: same place and action, about the same intent. */
    static boolean repeats(CodingPlan.Change done, CodingPlan.Change again) {
        if (!done.file().equals(again.file()) || !done.section().equals(again.section()) || done.action() != again.action()) return false;
        Set<String> a = words(done.intent());
        Set<String> b = words(again.intent());
        if (a.isEmpty() || b.isEmpty()) return a.equals(b);
        Set<String> common = new LinkedHashSet<>(a);
        common.retainAll(b);
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return common.size() * 2 >= all.size();
    }

    private static final Set<String> FILLER = Set.of("the", "that", "this", "and", "for", "with", "from", "into", "its", "are", "can");

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (word.length() > 2 && !FILLER.contains(word)) words.add(word);
        }
        return words;
    }

    /**
     * The state with the outlines of the picked files built again when one of them changed since they were built (a plan revised after
     * an edit, a file changed in the editor while the user read the plan): only the outlines, no model, and only when something changed.
     */
    private State withFreshOutlines(State state) {
        if (state.codingPicks().isEmpty() || state.knowledge().isEmpty()) return state;
        List<String> changed = Knowledge.changesIn(state.knowledge(), state.codingPicks(), state.workFolder(), workspaces);
        if (changed.isEmpty()) return state;
        Map<String, Object> data = new HashMap<>(state.data());
        data.putAll(gather(state));
        return new State(data);
    }

    /** The user's comment is a question about the plan: it was answered, and the user is asked about the same plan again. */
    public static final String QUESTION = "question";
    /** The user's comment asks for something else in the plan: the plan is made again, point by point. */
    public static final String CHANGE = "change";

    /**
     * The step between "revise" and the new plan: reads the user's comment against the plan and the code it was made from. A question
     * (why this file, does it touch that, what about X) is answered, from the outlines and the code the job read, and the user is asked
     * about the same plan again; a request for something else becomes a numbered list of points the new plan must deal with, one by one.
     * Without it the comment was one more sentence at the end of the planning prompt, and a model that reads its own plan above it writes
     * the same plan again and never answers.
     */
    public Map<String, Object> revise(State state, String instructions) {
        String comment = state.codingComment().strip();
        CodingPlan plan = state.codingPlan().orElse(null);
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_ANSWER, "");
        if (comment.isBlank() || plan == null) {
            updates.put(State.CODING_REVISE_KIND, CHANGE);
            updates.put(State.CODING_POINTS, comment);
            return updates;
        }
        StringBuilder prompt = new StringBuilder("The plan the user was shown:\n").append(plan.toMarkdown());
        String code = state.codingCode();
        if (!code.isBlank()) prompt.append("\n\nOutlines of the files the work is probably in:\n").append(code);
        if (!state.codingRead().isBlank()) prompt.append("\n\nCode that was read for the plan:\n").append(state.codingRead());
        prompt.append("\n\nThe user's comment on the plan: ").append(comment);
        String kind = CHANGE;
        String points = comment;
        String answer = "";
        try {
            String text = textOf(ask(JobPhase.REVISE, state, instructions, prompt.toString()));
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                com.fasterxml.jackson.databind.JsonNode json = JSON.readTree(text.substring(start, end + 1));
                kind = "question".equalsIgnoreCase(json.path("kind").asText().strip()) ? QUESTION : CHANGE;
                answer = json.path("answer").asText("").strip();
                List<String> listed = new ArrayList<>();
                json.path("points").forEach(p -> {
                    if (!p.asText().isBlank()) listed.add(p.asText().strip());
                });
                if (!listed.isEmpty()) points = String.join("\n", listed);
                if (QUESTION.equals(kind) && answer.isBlank()) kind = CHANGE; // a question without an answer helps nobody: plan again
            }
        } catch (RuntimeException | java.io.IOException e) {
            // the comment goes to the plan as it is written, as it did before this step
        }
        updates.put(State.CODING_REVISE_KIND, kind);
        updates.put(State.CODING_POINTS, points);
        updates.put(State.CODING_ANSWER, answer);
        if (QUESTION.equals(kind)) updates.put(State.CODING_COMMENT, ""); // the user is asked again about the same plan
        return updates;
    }

    /** A question → the user is asked about the same plan again; anything else → a new plan. */
    public static String afterRevise(State state) {
        return QUESTION.equals(state.codingReviseKind()) ? QUESTION : CHANGE;
    }

    /** A usable plan → ask the user; a plan that asked to read → plan again; a model error or no plan → report. */
    public static String afterPlan(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingReadPending() > 0 ? "read" : "approval";
    }

    private String planPrompt(State state, Optional<CodingPlan> previous, String problem) {
        StringBuilder prompt = new StringBuilder();
        String code = state.codingCode();
        prompt.append("Outlines of the files the work is probably in (section id, kind, lines):\n")
                .append(code.isBlank() ? "(no existing file was picked: the work is new files)" : code);
        if (!state.codingRead().isBlank()) prompt.append("\n\nCode you asked to read:\n").append(state.codingRead());
        int left = Math.max(0, limits.readRounds() - state.codingReadRounds());
        prompt.append("\n\nYou may ask to read more code ").append(left).append(left == 1 ? " time" : " times")
                .append(" (answer with \"read\" and no \"changes\").");
        // The size the plan may have: a long plan takes minutes to write and is refused above the hard limit, so the model is told
        // up front what to do with work that does not fit (it plans the most important part, and says what is left)
        prompt.append("\nA plan has at most ").append(PlanValidator.MAX_CHANGES).append(" changes (more than ").append(PlanValidator.HARD_MAX_CHANGES)
                .append(" is refused) and must be written in few words. Plan incrementally: only the next part that is small, whole and leaves the project building, never"
                        + " the whole goal at once (there is little room to write the code). Name what is left in \"summary\": it is planned after this part is made.");
        boolean revising = REVISE.equals(state.codingDecision()) && !state.codingPoints().isBlank();
        previous.ifPresent(plan -> prompt.append(revising ? "\n\nThe previous plan (this is what the user wants changed, not what to write again):\n"
                : "\n\nThe previous plan:\n").append(plan.toJson()));
        state.codingApprovedPlan().ifPresent(approved -> {
            String made = madeSoFar(approved, state.codingLedger().resizedTo(approved.changes().size()));
            if (!made.isEmpty()) prompt.append("\n\nChanges already made (kept; don't list them again):\n").append(made);
        });
        if (REVISE.equals(state.codingDecision())) {
            if (revising) {
                prompt.append("\n\nThe user sent the plan back. Their points, to be dealt with one by one (a point that changes nothing in the plan"
                        + " is answered in \"summary\" with the reason; every other point changes at least one change, and the change's intent says which point):\n");
                int number = 1;
                for (String point : state.codingPoints().split("\n")) prompt.append(number++).append(". ").append(point).append('\n');
                if (!state.codingAnswer().isBlank()) prompt.append("(Already answered to the user: ").append(state.codingAnswer()).append(")\n");
            } else {
                prompt.append("\n\nThe user asked for changes to the plan")
                        .append(state.codingComment().isBlank() ? "." : ": " + state.codingComment());
            }
        }
        if (!state.codingRevisionReason().isBlank()) {
            prompt.append("\n\nThe plan must change because: ").append(state.codingRevisionReason())
                    .append("\nWrite only the changes that are still to do: the ones already made are kept as they are, and a change"
                            + " you repeat from the list is dropped.");
        }
        if (state.codingPlanCheck().startsWith("problems:")) {
            prompt.append("\n\nA check of your previous plan found problems. Write the plan again so that it has none of them (change the plan itself;"
                    + " a reply that argues is not a plan):\n").append(state.codingPlanCheck().substring("problems:".length()).strip());
        }
        if (!problem.isBlank()) prompt.append("\n\nYour last answer could not be used: ").append(problem)
                .append("\nCall submit_plan again.");
        return prompt.toString();
    }

    /** Reads the sections the plan asked for (no model) into {@code codingRead}; the plan step then runs again. */
    private Map<String, Object> readMore(State state, CodingPlan asked) {
        StringBuilder read = new StringBuilder(state.codingRead());
        for (CodingPlan.Read item : asked.read()) {
            if (read.length() >= MAX_READ_CHARS) break;
            read.append(read.isEmpty() ? "" : "\n\n").append(readItem(files(state).fresh(), item));
        }
        return Map.of(State.CODING_READ, read.toString(),
                State.CODING_READ_ROUNDS, state.codingReadRounds() + 1,
                State.CODING_READ_PENDING, 1);
    }

    /** One section (or a file's outline) as text for the model, or the reason it couldn't be read. */
    private static String readItem(ProjectFiles files, CodingPlan.Read item) {
        try {
            Path file = WorkFolder.resolveInside(files.root(), item.file());
            if (!files.isRegularFile(file)) return "### " + item.file() + "\n(no such file)";
            byte[] bytes = files.readBytes(file);
            Outline outline = Outlines.of(file.getFileName().toString(), bytes);
            if (item.section().isBlank()) return "### " + item.file() + "\n" + outline.render(item.file(), null);
            Section section = outline.find(item.section());
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            String text = String.join("\n", lines.subList(section.start() - 1, section.end()));
            if (text.length() > MAX_SECTION_CHARS) {
                text = text.substring(0, MAX_SECTION_CHARS) + "\n[... cut: ask for its parts ...]";
            }
            return "### " + item.file() + " · " + section.id() + "\n```\n" + text + "\n```";
        } catch (IOException | RuntimeException e) {
            return "### " + item.file() + " · " + item.section() + "\n(" + e.getMessage() + ")";
        }
    }

    // ---- planApproval ----

    /**
     * Runs after the pause, once Agent stored the user's answer: an approved plan is recorded as
     * {@code codingApprovedPlan} (the plan the job works by, and the report shows), the ledger gets an entry for every
     * change (kept for changes that are already done), and the files the plan changes are copied for the design
     * review's diffs. The router after it acts on the answer.
     */
    public Map<String, Object> planApproval(State state) {
        // Revise: the user's comment is now why the plan changes, so an older reason from the graph must not be shown
        // Whatever the user answers, the plan check starts over for the next plan
        if (REVISE.equals(state.codingDecision())) {
            return Map.of(State.CODING_REVISION_REASON, "", State.CODING_ANSWER, "", State.CODING_PLAN_CHECK, "", State.CODING_PLAN_CHECKS, 0);
        }
        if (!APPROVE.equals(state.codingDecision())) return Map.of();
        CodingPlan plan = state.codingPlan().orElseThrow(() -> new IllegalStateException("plan approval without a plan"));
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_APPROVED_PLAN, plan.toJson());
        updates.put(State.CODING_LEDGER, state.codingLedger().resizedTo(plan.changes().size()).toJson());
        updates.put(State.CODING_BASELINE, snapshot(state, plan));
        updates.put(State.CODING_REVISION_REASON, "");
        updates.put(State.CODING_COMMENT, "");
        updates.put(State.CODING_ANSWER, "");
        updates.put(State.CODING_PLAN_CHECK, "");
        updates.put(State.CODING_PLAN_CHECKS, 0);
        // The user was asked a question by the review and approved a plan (as it was or revised): the question is settled
        if (state.codingIntent().startsWith(ASK)) updates.put(State.CODING_INTENT, DECIDED);
        return updates;
    }

    /** The intent check's final verdict after the user settled its question by approving a plan. */
    static final String DECIDED = "decided";

    /** The user's answer to the plan; a missing or unknown answer counts as deny (fail closed). */
    public static String afterPlanApproval(State state) {
        return switch (state.codingDecision()) {
            case APPROVE -> APPROVE;
            case REVISE -> REVISE;
            default -> DENY;
        };
    }

    // ---- setup ----

    /** Runs the approved setup commands, exactly as written, once per job (a revised plan doesn't run them again). */
    public Map<String, Object> setup(State state) {
        String unapproved = notApproved(state);
        if (unapproved != null) return Map.of(State.CODING_STOP_REASON, unapproved);
        CodingPlan plan = state.codingPlan().orElseThrow();
        if (state.codingSetupDone() > 0 || plan.setup().isEmpty()) return Map.of(State.CODING_SETUP_DONE, 1);
        for (int i = 0; i < plan.setup().size(); i++) {
            CodingPlan.Command command = plan.setup().get(i);
            String result;
            try {
                result = run(state, command, "setup-" + (i + 1));
            } catch (RuntimeException e) {
                return Map.of(State.CODING_STOP_REASON, reason(TOOL_ERROR, e));
            }
            if (!ranWithExitCodeZero(result)) {
                String failed = "the setup command " + CodingPlan.describe(command) + " failed: " + oneLine(result, 400);
                KnowledgeBook.shared().learn(state.codingGoal(), "A setup command failed: " + CodingPlan.describe(command) + " -> " + oneLine(result, 200)
                        + ". Plan another way to get the same result.");
                // The job used to stop here with nothing done. The plan is made again, with what failed, up to three plans in all
                if (state.codingPlanRounds() < MAX_PLAN_ROUNDS_AFTER_SETUP) {
                    return Map.of(State.CODING_REVISION_REASON, SETUP_FAILED + failed + ". Plan again without relying on it: another way to get the same result, or leave that setup out.");
                }
                return Map.of(State.CODING_STOP_REASON, failed);
            }
        }
        return Map.of(State.CODING_SETUP_DONE, 1);
    }

    /** The start of the revision reason that says the plan's setup failed (the router sends the job back to the plan). */
    static final String SETUP_FAILED = "The setup of your plan failed: ";
    /** After this many plans a failed setup ends the job. */
    static final int MAX_PLAN_ROUNDS_AFTER_SETUP = 3;

    /** Setup is done (or there was none) → the changes; a failed command → a new plan, or the report when it has failed too often. */
    public static String afterSetup(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingSetupDone() == 0 && state.codingRevisionReason().startsWith(SETUP_FAILED) ? "replan" : "done";
    }

    // ---- edit ----

    /** Most changes to one file the model writes in one answer. */
    private static final int MAX_TOGETHER = 6;

    /** What one try at the changes left: the ledger as it is, and why the job must stop (null: it goes on). */
    private record Edited(Ledger ledger, String stopReason) {
    }

    /**
     * Makes the next pending change of the approved plan, and with it the other pending changes to the same file: the
     * model writes them in one answer (one block per change) and the graph applies them to the file in one write.
     * A change that can't be made that way (the answer had too few blocks, the section can't be found) gets its own
     * call, a second one when the answer has no code or doesn't apply. A change that still can't be made is marked
     * failed in the ledger and the job goes on with the others. A {@code delete} needs no model.
     */
    public Map<String, Object> edit(State state, String instructions) {
        String unapproved = notApproved(state);
        if (unapproved != null) return Map.of(State.CODING_STOP_REASON, unapproved);
        CodingPlan approved = state.codingPlan().orElseThrow();
        Ledger ledger = state.codingLedger().resizedTo(approved.changes().size());
        // The plan stays as approved; a patch on its second try is worked as a replace the model writes (see effectivePlan)
        CodingPlan plan = effectivePlan(approved, ledger);
        ledger = applyPatches(state, plan, ledger);
        int index = ledger.nextPending();
        if (index < 0) { // changes that failed may fit now that others landed (an import, a member they use): once more, in plan order
            ledger = retryFailed(ledger);
            plan = effectivePlan(approved, ledger);
            index = ledger.nextPending();
        }
        if (index < 0) return result(ledger, null);

        List<Integer> group = sameFile(plan, ledger, index);
        Map<Integer, String> problems = new HashMap<>(); // why a change of the group wasn't made together with the others
        if (group.size() > 1) {
            Edited together = editTogether(state, instructions, plan, ledger, group, problems);
            ledger = together.ledger();
            if (together.stopReason() != null) return result(ledger, together.stopReason());
        }
        for (int i : group) {
            if (ledger.entries().get(i).status() != Ledger.Status.PENDING) continue;
            Edited one = editOne(state, instructions, plan, ledger, i, problems.getOrDefault(i, ""));
            ledger = one.ledger();
            if (one.stopReason() != null) return result(ledger, one.stopReason());
        }
        return result(ledger, null);
    }

    private static Map<String, Object> result(Ledger ledger, String stopReason) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_LEDGER, ledger.toJson());
        if (stopReason != null) updates.put(State.CODING_STOP_REASON, stopReason);
        return updates;
    }

    /**
     * The plan as the edit step works it: each patch on its second try (its ledger note starts with {@link #SECOND_TRY}) is a replace of
     * its section that the model writes, with the patch and why it did not fit in the intent. Running the same patch again can only fail
     * the same way. The approved plan is not changed (a changed plan must be approved again): this is worked out from the plan and the ledger
     * every time, so the plan the user saw and the report stay as they were.
     */
    static CodingPlan effectivePlan(CodingPlan plan, Ledger ledger) {
        List<Change> changes = new ArrayList<>(plan.changes());
        boolean rewritten = false;
        for (int i = 0; i < changes.size() && i < ledger.entries().size(); i++) {
            Change change = changes.get(i);
            String note = ledger.entries().get(i).note();
            if (change.action() != Action.PATCH || change.section().isBlank() || !note.startsWith(SECOND_TRY)) continue;
            changes.set(i, new Change(change.file(), change.section(), Action.REPLACE, change.intent()
                    + " (A patch for this did not fit: " + oneLine(note.substring(SECOND_TRY.length()), 300) + " It wanted this change:\n"
                    + change.patch() + "\nWrite the complete new text of the section.)"));
            rewritten = true;
        }
        return rewritten ? plan.with(List.of(), plan.setup(), changes, plan.verify()) : plan;
    }

    /** The note of a change that is tried a second time; a second failure keeps it, so a change is never tried a third time. */
    static final String SECOND_TRY = "second try: ";

    /** Failed changes back to pending, once, when something else was applied (else nothing has changed for them). */
    static Ledger retryFailed(Ledger ledger) {
        if (!ledger.anyApplied()) return ledger;
        Ledger again = ledger;
        for (int i : ledger.indexes(Ledger.Status.FAILED)) {
            String note = ledger.entries().get(i).note();
            if (!note.startsWith(SECOND_TRY)) again = again.with(i, Ledger.Status.PENDING, SECOND_TRY + note);
        }
        return again;
    }

    /** A change that failed; the note says it was a second try when it was. */
    private static Ledger failed(Ledger ledger, int index, String note) {
        boolean second = ledger.entries().get(index).note().startsWith(SECOND_TRY);
        return ledger.with(index, Ledger.Status.FAILED, (second && !note.startsWith(SECOND_TRY) ? SECOND_TRY : "") + note);
    }

    /**
     * The patch changes that are next in plan order, applied without a model: their hunks were tried when the plan was checked and fit
     * the file, and a patch that does not fit any more (an earlier change moved things) fails with the reason, like any change.
     */
    private Ledger applyPatches(State state, CodingPlan plan, Ledger ledger) {
        for (int i = ledger.nextPending(); i >= 0 && plan.changes().get(i).action() == Action.PATCH; i = ledger.nextPending()) {
            Change change = plan.changes().get(i);
            try {
                ProjectFiles files = files(state).fresh();
                Path file = WorkFolder.resolveInside(state.workFolder(), change.file());
                files.writeString(file, applyToText(file.getFileName().toString(), files.readString(file), change, ""));
                ledger = ledger.with(i, Ledger.Status.APPLIED, "");
            } catch (ApplyFailure | IOException | RuntimeException e) {
                ledger = failed(ledger, i, e.getMessage() == null ? "the patch could not be applied" : e.getMessage());
            }
        }
        return ledger;
    }

    /** The pending changes to the file of the change at {@code index} (it first), in plan order; a new file is alone. */
    private static List<Integer> sameFile(CodingPlan plan, Ledger ledger, int index) {
        Change first = plan.changes().get(index);
        if (first.action() == Action.CREATE) return List.of(index);
        List<Integer> group = new ArrayList<>();
        for (int i = index; i < plan.changes().size() && group.size() < MAX_TOGETHER; i++) {
            Change change = plan.changes().get(i);
            if (change.action() == Action.PATCH) break; // a patch is applied by itself, in its place in the plan
            if (ledger.entries().get(i).status() == Ledger.Status.PENDING && change.action() != Action.CREATE
                    && change.file().equals(first.file())) {
                group.add(i);
            }
        }
        return group;
    }

    /**
     * One model call for all changes of the group (a {@code delete} needs no text): the answer is one code block per
     * change, in order. The blocks are applied to the file's text one after the other and the file is written once.
     * A change that doesn't apply stays pending with its reason in {@code problems}, for a call of its own.
     */
    private Edited editTogether(State state, String instructions, CodingPlan plan, Ledger ledger, List<Integer> group,
                                Map<Integer, String> problems) {
        List<Integer> needText = group.stream().filter(i -> plan.changes().get(i).action() != Action.DELETE).toList();
        List<String> blocks = List.of();
        if (!needText.isEmpty()) {
            String problem = "";
            for (int attempt = 1; attempt <= limits.editAttempts() && blocks.isEmpty(); attempt++) {
                AiMessage reply;
                try {
                    reply = askResponse(JobPhase.EDIT, state, instructions, togetherPrompt(state, plan, ledger, group, problem), WRITE_EFFORT, null, EditFunction.spec()).aiMessage();
                } catch (RuntimeException e) {
                    return new Edited(ledger, reason(MODEL_ERROR, e));
                }
                List<String> answer = EditFunction.texts(reply);
                if (answer.size() == needText.size()) {
                    blocks = answer;
                } else {
                    problem = "you wrote " + answer.size() + " texts for " + needText.size() + " changes. Call write_code with exactly"
                            + " one text per change, in the order listed.";
                }
            }
            if (blocks.isEmpty()) { // each change then has a call of its own
                group.forEach(i -> problems.put(i, "your answer to the changes together had the wrong number of code blocks."));
                return new Edited(ledger, null);
            }
        }
        Change first = plan.changes().get(group.getFirst());
        Ledger made = ledger;
        try {
            ProjectFiles files = files(state).fresh();
            Path file = WorkFolder.resolveInside(state.workFolder(), first.file());
            String text = files.readString(file);
            int next = 0;
            for (int i : group) {
                Change change = plan.changes().get(i);
                String block = change.action() == Action.DELETE ? "" : blocks.get(next++);
                try {
                    text = applyToText(file.getFileName().toString(), text, change, block);
                    made = made.with(i, Ledger.Status.APPLIED, "");
                } catch (ApplyFailure e) {
                    problems.put(i, e.getMessage());
                }
            }
            if (made != ledger) {
                text = withImports(files, first.file(), text, String.join("\n", blocks));
                files.writeString(file, text); // memory for a Java file of a Java project
                files.flush();
            }
        } catch (IOException | RuntimeException e) {
            group.forEach(i -> problems.put(i, "the file could not be changed: " + e.getMessage()));
            return new Edited(ledger, null); // nothing was written
        }
        return new Edited(made, null);
    }

    /** One change: one model call (a second when the answer has no code or doesn't apply), then it is applied. */
    private Edited editOne(State state, String instructions, CodingPlan plan, Ledger ledger, int index, String problem) {
        Change change = plan.changes().get(index);
        for (int attempt = 1; attempt <= limits.editAttempts(); attempt++) {
            String block = "";
            if (change.action() != Action.DELETE) {
                AiMessage reply;
                try {
                    reply = askResponse(JobPhase.EDIT, state, instructions, editPrompt(state, plan, ledger, index, problem), WRITE_EFFORT, null, EditFunction.spec()).aiMessage();
                } catch (RuntimeException e) {
                    return new Edited(ledger, reason(MODEL_ERROR, e));
                }
                Optional<String> code = EditFunction.texts(reply).stream().findFirst();
                if (code.isEmpty()) {
                    problem = "your answer had no code. Call write_code with one text: the complete new text.";
                    continue;
                }
                block = code.get();
            }
            try {
                apply(files(state), change, block);
                return new Edited(ledger.with(index, Ledger.Status.APPLIED, ""), null);
            } catch (ApplyFailure e) {
                problem = e.getMessage();
            }
        }
        return new Edited(failed(ledger, index, problem), null);
    }

    /** More pending changes → edit again; none left → verify; a model error → report. */
    public static String afterEdit(State state) {
        if (stopped(state)) return STOPPED;
        CodingPlan plan = state.codingPlan().orElse(null);
        if (plan == null) return "verify";
        Ledger ledger = state.codingLedger().resizedTo(plan.changes().size());
        return ledger.nextPending() >= 0 || !retryFailed(ledger).equals(ledger) ? "edit" : "verify";
    }

    private String editPrompt(State state, CodingPlan plan, Ledger ledger, int index, String problem) {
        StringBuilder prompt = new StringBuilder(describe(plan, index, files(state), true));
        String done = madeSoFar(plan, ledger);
        if (!done.isEmpty()) prompt.append("\n\nAlready made in this job:\n").append(done);
        if (!problem.isBlank()) prompt.append("\n\nYour last answer could not be applied: ").append(problem);
        return prompt.toString();
    }

    /** The request for several changes to one file: each change described, then one code block each is asked for. */
    private String togetherPrompt(State state, CodingPlan plan, Ledger ledger, List<Integer> group, String problem) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(group.size()).append(" changes to `").append(plan.changes().get(group.getFirst()).file())
                .append("`. Call write_code with one text per change, in the order listed.");
        String done = madeSoFar(plan, ledger);
        if (!done.isEmpty()) prompt.append("\n\nAlready made in this job:\n").append(done);
        for (int i : group) prompt.append("\n\n").append(describe(plan, i, files(state), i == group.getFirst()));
        if (state.codingPlanCheck().startsWith("problems:")) {
            prompt.append("\n\nA check of your previous plan found problems. Write the plan again so that it has none of them (change the plan itself;"
                    + " a reply that argues is not a plan):\n").append(state.codingPlanCheck().substring("problems:".length()).strip());
        }
        if (!problem.isBlank()) prompt.append("\n\nYour last answer could not be used: ").append(problem);
        return prompt.toString();
    }

    /** "Change 2 of 5: replace `file` · `section`", what it should achieve and the material the model needs. */
    private static String describe(CodingPlan plan, int index, ProjectFiles files, boolean withStyle) {
        Change change = plan.changes().get(index);
        StringBuilder text = new StringBuilder();
        text.append("Change ").append(index + 1).append(" of ").append(plan.changes().size()).append(": ")
                .append(change.action().json()).append(" `").append(change.file()).append('`');
        if (!change.section().isBlank()) text.append(" · `").append(change.section()).append('`');
        if (change.anchored()) text.append(" · ").append(change.action() == Action.BEFORE ? "before" : "after").append(" the statement `").append(change.anchor()).append('`');
        text.append("\nWhat it should achieve: ").append(change.intent());
        text.append("\n\n").append(sectionContext(files, change));
        String style = styleContext(files, change, withStyle);
        if (!style.isEmpty()) text.append("\n\n").append(style);
        return text.toString();
    }

    private static final int STYLE_SAMPLE_LINES = 30;

    /**
     * The style the new code must have: what the file shows ({@link StyleNotes}) and a part of it as an example (an
     * existing method of the class for {@code add}); for a new file the same from a file next to it of the same kind.
     * "" when there is nothing to learn from. The file's style wins over any guide.
     */
    private static String styleContext(ProjectFiles files, Change change, boolean withNotes) {
        try {
            Path folder = files.root();
            Path file = WorkFolder.resolveInside(folder, change.file());
            if (change.action() == Action.CREATE) {
                if (!withNotes) return "";
                Path neighbour = neighbourOf(file);
                if (neighbour == null) return "";
                String text = files.readString(neighbour);
                String notes = StyleNotes.of(text);
                String name = WorkFolder.resolveInside(folder, "").relativize(neighbour).toString().replace('\\', '/');
                return "Match the style of the code next to the new file (" + name + "):" + (notes.isEmpty() ? "" : "\n" + notes)
                        + "\nIts first lines, as an example:\n```\n" + head(text, STYLE_SAMPLE_LINES) + "\n```";
            }
            String text = files.readString(file);
            StringBuilder style = new StringBuilder();
            String notes = StyleNotes.of(text);
            if (withNotes && !notes.isEmpty()) style.append("Style of this file (match it exactly):\n").append(notes);
            if (change.action() == Action.ADD) {
                Outline outline = Outlines.of(file.getFileName().toString(), text.getBytes(StandardCharsets.UTF_8));
                List<Section> methods = outline.partsOf(outline.find(change.section())).stream().filter(s -> s.kind().equals("method")).toList();
                if (!methods.isEmpty()) {
                    Section last = methods.getLast();
                    List<String> lines = text.lines().toList();
                    String sample = String.join("\n", lines.subList(last.start() - 1, Math.min(last.end(), last.start() - 1 + STYLE_SAMPLE_LINES)));
                    style.append(style.isEmpty() ? "" : "\n").append("An existing method of the class, as an example of how this file writes code:\n```\n")
                            .append(sample).append("\n```");
                }
            }
            return style.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** A file of the same kind in the same folder, the first by name, to learn the style of a new file from; null if none. */
    private static Path neighbourOf(Path file) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot);
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            return siblings.filter(Files::isRegularFile).filter(p -> !p.equals(file) && p.getFileName().toString().endsWith(extension))
                    .filter(p -> p.toFile().length() < 200_000).sorted().findFirst().orElse(null);
        }
    }

    private static String head(String text, int lines) {
        return String.join("\n", text.lines().limit(lines).toList());
    }

    /** What the model needs to see for the change: the section's text, the class's parts, or a note for a new file. */
    private static String sectionContext(ProjectFiles files, Change change) {
        if (change.action() == Action.CREATE) return "This is a new file: write its complete content.";
        try {
            Path file = WorkFolder.resolveInside(files.root(), change.file());
            byte[] bytes = files.readBytes(file);
            Outline outline = Outlines.of(file.getFileName().toString(), bytes);
            Section section = outline.find(change.section());
            if (change.action() == Action.ADD) {
                return "The parts of `" + section.id() + "` (it is where the new part goes; write only the new part, with its"
                        + " doc comment):\n" + outline.render(change.file(), section);
            }
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            String text = String.join("\n", lines.subList(section.start() - 1, section.end()));
            if (text.length() > MAX_SECTION_CHARS) text = text.substring(0, MAX_SECTION_CHARS) + "\n[... cut ...]";
            String visible = visibleVariables(new String(bytes, StandardCharsets.UTF_8), file.getFileName().toString(), section, change);
            return switch (change.action()) {
                case BEFORE, AFTER -> "The section the new text goes " + change.action().json() + " (write only the new part):\n```\n"
                        + text + "\n```" + visible;
                case START, END -> "The method (write only the statements to put at the " + change.action().json() + " of its body,"
                        + " not the signature, the braces or any of the code that is there):\n```\n" + text + "\n```" + visible;
                default -> "The current text of the section (write its complete new text):\n```\n" + text + "\n```" + visible;
            };
        } catch (IOException | RuntimeException e) {
            return "(the section could not be read: " + e.getMessage() + ")";
        }
    }

    /**
     * For a change inside a method of a Java file: the variables the new statements can use at that place (the parameters, what is
     * declared above and still in scope, the fields), from the parsed statements. "" elsewhere.
     */
    private static String visibleVariables(String text, String fileName, Section section, Change change) {
        if (!fileName.endsWith(".java") || !Set.of("method", "constructor").contains(section.kind())) return "";
        int line;
        try {
            line = switch (change.action()) {
                case START -> section.start() + 1;
                case END -> section.end();
                case BEFORE, AFTER -> change.anchored() ? JavaAnchors.find(text, section.start(), section.end(), change.anchor())
                        + (change.action() == Action.AFTER ? 1 : 0) : section.start() + 1;
                case REPLACE -> section.start() + 1;
                default -> 0;
            };
        } catch (RuntimeException e) {
            return "";
        }
        if (line <= 0) return "";
        String visible = JavaBlockEdit.visibleAt(text, line);
        return visible.isEmpty() ? "" : "\n" + visible;
    }

    /** "- replace `file` · `section`: intent" for the changes made so far. */
    private static String madeSoFar(CodingPlan plan, Ledger ledger) {
        StringBuilder done = new StringBuilder();
        for (int i : ledger.indexes(Ledger.Status.APPLIED)) {
            Change change = plan.changes().get(i);
            done.append("- ").append(change.action().json()).append(" `").append(change.file()).append('`')
                    .append(change.section().isBlank() ? "" : " · `" + change.section() + "`")
                    .append(change.intent().isBlank() ? "" : ": " + change.intent()).append('\n');
        }
        return done.toString().strip();
    }

    /** Why a change could not be applied; the model is told once. */
    private static final class ApplyFailure extends Exception {
        ApplyFailure(String message) {
            super(message);
        }
    }

    /**
     * Applies one change to the file: {@code create} writes the new file, the others replace, delete or add next to
     * the section named by the change, found by its id in the file's current outline (an earlier change may have moved
     * it). Nothing is written when the section can't be found.
     */
    private static void apply(ProjectFiles files, Change change, String newText) throws ApplyFailure {
        String label = change.file() + (change.section().isBlank() ? "" : " · " + change.section()) + " (" + change.action().json() + ")";
        try {
            applyChange(files, change, newText);
            // Written through: the file on disk is what the model reads next and what an interrupted job leaves behind
            JavaWorkspace.FlushResult flushed = files.flush();
            if (!flushed.ok()) throw new ApplyFailure("the file could not be written: " + flushed.conflicts());
            AgentLog.current().line("✎ " + label + ": applied" + (change.file().endsWith(".java") ? ", structure checked, written" : "")
                    + ", " + newText.lines().count() + " lines written");
        } catch (ApplyFailure e) {
            AgentLog.current().line("✎✗ " + label + ": refused, nothing written: " + e.getMessage());
            throw e;
        }
    }

    private static void applyChange(ProjectFiles files, Change change, String newText) throws ApplyFailure {
        try {
            Path file = WorkFolder.resolveInside(files.root(), change.file());
            if (change.action() == Action.CREATE) {
                if (files.exists(file)) throw new ApplyFailure("the file already exists.");
                String created = withImports(files, change.file(), newText, newText);
                files.writeString(file, created.endsWith("\n") ? created : created + "\n");
                return;
            }
            if (change.action() == Action.DELETE && change.section().isBlank()) {
                // No section: the whole file goes (the baseline copy of the job brings it back when the job is rolled back)
                if (!files.isRegularFile(file)) throw new ApplyFailure("the file does not exist.");
                files.deleteFile(file);
                return;
            }
            String text = files.readString(file);
            String changed = applyToText(file.getFileName().toString(), text, change, newText);
            if (change.action() != Action.DELETE) changed = withImports(files, change.file(), changed, newText);
            files.writeString(file, changed);
        } catch (IOException e) {
            throw new ApplyFailure("the file could not be written: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApplyFailure(e.getMessage());
        }
    }

    /**
     * The imports a Java change needs, added without a model ({@code JavaImports}), so the order of a plan's changes does not matter for
     * them. What still cannot be told (two classes with the name, or none known: a library the project doesn't have yet) is
     * noted in the log; the build reports what is really missing.
     */
    private static String withImports(ProjectFiles files, String fileName, String fileText, String written) {
        if (!fileName.endsWith(".java") || !files.holdsJava()) return fileText;
        String cleaned = net.rka.ai.tools.code.java.JavaImports.dropInvented(fileText, files.qualifiedTypeNames());
        if (!cleaned.equals(fileText)) AgentLog.current().line("   - imports of classes that do not exist taken out of " + fileName);
        String with = net.rka.ai.tools.code.java.JavaImports.add(cleaned, written, files.qualifiedTypeNames());
        if (!with.equals(cleaned)) {
            AgentLog.current().line("   + imports added to " + fileName + ": " + with.lines().filter(l -> l.startsWith("import ") && !cleaned.contains(l))
                    .map(l -> l.substring(7, l.length() - 1)).collect(java.util.stream.Collectors.joining(", ")));
        }
        List<String> unknown = net.rka.ai.tools.code.java.JavaUnknownTypes.unknown(with, written, files.typeNames());
        if (!unknown.isEmpty()) {
            AgentLog.current().line("   ⚠ " + fileName + " uses " + String.join(", ", unknown) + ": not imported, not in the project, not in java.lang");
        }
        return with;
    }

    /** Whether the text declares a method or constructor (read as the body of a class): statements and local variables don't. */
    static boolean declaresMethod(String text) {
        if (JavaStructure.readsAsStatements(text)) return false; // a call like System.out.println(x); reads as a method to the lenient reader
        try {
            return JavaSourceReader.read("class X {\n" + text + "\n}\n").stream()
                    .anyMatch(t -> !t.methods().isEmpty() || !t.constructors().isEmpty());
        } catch (RuntimeException e) {
            return false; // statements are not members
        }
    }

    private static final java.util.regex.Pattern STATEMENT_KEYWORD = java.util.regex.Pattern.compile(
            "^(if|else|for|while|do|switch|try|return|throw|synchronized|break|continue|yield|assert)\\b.*");
    private static final java.util.regex.Pattern CALL_OR_ASSIGNMENT = java.util.regex.Pattern.compile(
            "^(?:[\\w.\\[\\]]+\\s*(?:[-+*/|&^]?=(?!=)).*|[\\w.]+\\s*\\(.*\\)\\s*;)$");

    /**
     * The first code line of a Java text when it is a statement, not the start of a member: a block's keyword
     * ({@code if (...)}, {@code return}), a call ({@code foo(x);}) or an assignment ({@code x = 1;}). A member starts
     * with modifiers, a type or a name followed by a body; comments and annotations before it are skipped. Null when
     * the text can be a member. The model writes such statements where a member belongs when it forgets that the
     * change is a whole method; they would land outside any method and break the file.
     */
    static String startsWithStatement(String text) {
        boolean inComment = false;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (inComment) {
                inComment = !line.contains("*/");
                continue;
            }
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("@")) continue;
            if (line.startsWith("/*")) {
                inComment = !line.contains("*/");
                continue;
            }
            return STATEMENT_KEYWORD.matcher(line).matches() || CALL_OR_ASSIGNMENT.matcher(line).matches()
                    ? "`" + (line.length() > 40 ? line.substring(0, 40) + "…" : line) + "`" : null;
        }
        return null;
    }

    /**
     * Statements put before or after one of the statements of a method: the anchor names its first line, the graph finds where the
     * statement ends and indents the new statements like it. The method's declarations must stay as they are.
     */
    private static String anchored(String fileName, String text, Section section, Change change, String newText) throws ApplyFailure {
        if (!fileName.endsWith(".java")) throw new ApplyFailure("an anchor works in Java files only.");
        if (declaresMethod(newText)) {
            throw new ApplyFailure("your text declares a method, but this change puts statements into the method `" + section.id()
                    + "`: write only the statements, without a signature and braces of their own.");
        }
        if (newText.isBlank()) throw new ApplyFailure("write the statements to put " + change.action().json() + " the anchor.");
        String changed = JavaAnchors.insert(text, section.start(), section.end(), change.anchor(), change.action() == Action.AFTER, newText);
        String problem = JavaStructure.problem(text, changed, section.start(), section.end(), "", JavaStructure.Effect.BODY);
        if (problem == null) problem = JavaBlockEdit.memberProblem(text, changed, section.start(), section.end());
        if (problem != null) throw new ApplyFailure(problem);
        return changed;
    }

    /** The file's text with the (not {@code create}) change applied; the section is found in the text's own outline. */
    private static String applyToText(String fileName, String text, Change change, String newText) throws ApplyFailure {
        if (change.action() == Action.PATCH) return patched(fileName, text, change);
        try {
            Outline outline = Outlines.of(fileName, text.getBytes(StandardCharsets.UTF_8));
            Section section = outline.find(change.section());
            // An anchor names a statement; text that declares a member is a new member next to the section, whatever the anchor says
            if (change.anchored() && !(fileName.endsWith(".java") && declaresMethod(newText))) return anchored(fileName, text, section, change, newText);
            SectionEdit.Mode mode = switch (change.action()) {
                case ADD -> SectionEdit.Mode.ADD;
                case BEFORE -> SectionEdit.Mode.BEFORE;
                case AFTER -> SectionEdit.Mode.AFTER;
                case START -> SectionEdit.Mode.START;
                case END -> SectionEdit.Mode.END;
                default -> SectionEdit.Mode.REPLACE; // replace, and delete (empty text)
            };
            // Statements "before" or "after" a method can only mean inside it: the start or the end of its body
            if (change.action() != Action.DELETE && fileName.endsWith(".java") && (mode == SectionEdit.Mode.BEFORE || mode == SectionEdit.Mode.AFTER)
                    && Set.of("method", "constructor").contains(section.kind()) && startsWithStatement(newText) != null) {
                mode = mode == SectionEdit.Mode.BEFORE ? SectionEdit.Mode.START : SectionEdit.Mode.END;
            }
            boolean inBody = mode == SectionEdit.Mode.START || mode == SectionEdit.Mode.END;
            if (inBody && fileName.endsWith(".java") && declaresMethod(newText)) {
                throw new ApplyFailure("your text declares a method, but start and end put statements into the body of a method that"
                        + " exists: write only the statements, without a signature and braces of their own.");
            }
            if (change.action() != Action.DELETE && !inBody && fileName.endsWith(".java") && (mode != SectionEdit.Mode.REPLACE
                    || Set.of("method", "constructor", "field").contains(section.kind()))) {
                String statement = startsWithStatement(newText);
                if (statement != null) {
                    throw new ApplyFailure("your text starts with a statement (" + statement + "), but a change writes a complete member of the"
                            + " class: a method with its signature and braces, a field, a constructor. To change statements inside a"
                            + " method, replace that whole method.");
                }
            }
            String changed = SectionEdit.apply(text, outline, section, mode, change.action() == Action.DELETE ? "" : newText);
            if (changed.equals(text)) {
                throw new ApplyFailure("your text changes nothing: the file would be the same. Write the new text of this change.");
            }
            // A part that is in the file already, written again (the model rewrote a neighbour with the new part):
            // the outline of the result has it twice (`#2`), which no change of the plan wants
            // (a Java file is checked by its parsed structure below: the outline reads statements of a long method as members)
            Set<String> known = new LinkedHashSet<>();
            outline.sections().forEach(s -> known.add(s.id()));
            for (Section after : fileName.endsWith(".java") ? List.<Section>of() : Outlines.of(fileName, changed.getBytes(StandardCharsets.UTF_8)).sections()) {
                if (after.id().matches(".*#\\d+$") && !known.contains(after.id())) {
                    throw new ApplyFailure("your text declares `" + after.id().replaceAll("#\\d+$", "") + "` again, but it is in the file"
                            + " already. Write only what this change is about, not the code around it.");
                }
            }
            if (fileName.endsWith(".java")) {
                // the file's structure decides: the result must read as Java and hold what it held, minus the part, plus what is written
                String problem = JavaStructure.problem(text, changed, section.start(), section.end(), change.action() == Action.DELETE ? "" : newText,
                        JavaStructure.effectOf(mode, section.container()));
                if (problem == null) problem = JavaBlockEdit.memberProblem(text, changed, section.start(), section.end());
                if (problem != null) throw new ApplyFailure(problem);
            }
            return changed;
        } catch (IllegalArgumentException e) {
            throw new ApplyFailure(e.getMessage());
        }
    }

    /** The text with a patch change applied: found by its context in the section (or the file), then checked like every Java change. */
    private static String patched(String fileName, String text, Change change) throws ApplyFailure {
        try {
            boolean crlf = text.contains("\r\n");
            String normal = text.replace("\r\n", "\n");
            List<String> lines = List.of(normal.split("\n", -1));
            int from = 1;
            int to = lines.size();
            if (!change.section().isBlank()) {
                Section section = Outlines.of(fileName, normal.getBytes(StandardCharsets.UTF_8)).find(change.section());
                from = section.start();
                to = Math.min(section.end(), lines.size());
            }
            net.rka.ai.tools.TextPatch.Result result = net.rka.ai.tools.TextPatch.apply(lines.subList(from - 1, to), from, change.patch());
            String part = String.join("\n", result.lines());
            String changed = JavaStructure.splice(normal, from, to, part);
            if (fileName.endsWith(".java")) {
                String problem = JavaStructure.problem(normal, changed, from, to, part, JavaStructure.Effect.REPLACES);
                if (problem == null) problem = JavaBlockEdit.memberProblem(normal, changed, from, to);
                if (problem != null) throw new ApplyFailure(problem);
            }
            return crlf ? changed.replace("\n", "\r\n") : changed;
        } catch (IllegalArgumentException e) {
            throw new ApplyFailure(e.getMessage());
        }
    }

    // ---- verify ----

    /** runCommand's result starts with its exit code (see TerminalTools). */
    private static boolean ranWithExitCodeZero(String result) {
        return result.startsWith("exit code: 0\n") || result.equals("exit code: 0");
    }

    /** Runs one approved command through the tool runner with the job's timeout; its working directory must be inside the folder. */
    private String run(State state, CodingPlan.Command command, String id) {
        try {
            WorkFolder.resolveInside(state.workFolder(), command.workingDirectory());
        } catch (IOException | RuntimeException e) {
            return "Not run: the working directory is outside the trusted folder.";
        }
        // A build reads the files: the Java changes held in memory are written first
        JavaWorkspace.FlushResult flushed = files(state).flush();
        if (!flushed.ok()) {
            return "Not run: these Java files changed on disk while the job had changes in memory for them, so they were not written: "
                    + String.join(", ", flushed.conflicts()) + ".";
        }
        ToolExecutionRequest call = ToolExecutionRequest.builder().id(id).name(RUN_COMMAND)
                .arguments(toJson(Map.of("command", command.command(), "workingDirectory", command.workingDirectory()))).build();
        FactBook facts = new FactBook(state.facts()); // runCommand doesn't use it, but every run passes the facts
        return ((ToolExecutionResultMessage) toolCalls.run(List.of(call), state.workFolder(), facts, timeoutSeconds).getFirst()).text();
    }

    /** What one run of the verify commands printed. */
    private record Run(List<String> outputs, int failed, String failedOutput, boolean missingTool, int missing) {
        /** Every failed command failed because its tool is not there: the code was not shown to be wrong. */
        boolean onlyMissingTools() {
            return failed > 0 && missing == failed;
        }
    }

    /** Runs every verify command once; a command that can't be run at all throws. */
    private Run runVerify(State state, CodingPlan plan, String id) {
        List<String> outputs = new ArrayList<>();
        StringBuilder failedOutput = new StringBuilder();
        int failed = 0;
        boolean missingTool = false;
        int missing = 0;
        for (int i = 0; i < plan.verify().size(); i++) {
            CodingPlan.Command command = plan.verify().get(i);
            String result = run(state, command, id + "-" + (i + 1));
            if (!ranWithExitCodeZero(result)) {
                failed++;
                failedOutput.append(result).append('\n');
                // Only failed commands: a passing build may well print "not found" somewhere in its log
                boolean gone = EnvironmentInfo.looksLikeMissingTool(result);
                missingTool |= gone;
                if (gone) missing++;
            }
            outputs.add("$ " + CodingPlan.describe(command).replace("`", "") + "\n" + result);
        }
        return new Run(outputs, failed, failedOutput.toString(), missingTool, missing);
    }

    /**
     * Runs every verify command of the approved plan (no model) and stores what they printed in
     * {@code codingVerifyOutput}: a first line written here ({@link #VERIFY_PASSED} or {@link #VERIFY_FAILED}), then
     * each command with its output. A command passes when its result starts with {@code exit code: 0}. When it
     * fails, the positions in the output are mapped to the sections of the files that hold them ({@code codingFixes}).
     * <p>
     * A design fix can't make things worse: when the build fails right after one, the files go back to the copies
     * taken when the build passed ({@code codingGood}) and the commands run once more, so the job ends with code that
     * builds and says the design issues are still open.
     */
    public Map<String, Object> verify(State state) {
        String unapproved = notApproved(state);
        if (unapproved != null) return Map.of(State.CODING_STOP_REASON, unapproved); // nothing runs, the job reports
        CodingPlan plan = state.codingPlan().orElseThrow();
        int attempt = state.codingAttempts() + 1;
        Run run;
        boolean undone = false;
        try {
            run = runVerify(state, plan, "verify-" + attempt);
            if (run.failed() > 0 && !state.codingGood().isBlank()) {
                restoreGood(state, plan);
                undone = true;
                run = runVerify(state, plan, "verify-" + attempt + "-undone");
            }
        } catch (RuntimeException e) {
            // Not a failing command (that is a result) but the run itself breaking: the job reports
            return Map.of(State.CODING_STOP_REASON, reason(TOOL_ERROR, e), State.CODING_ATTEMPTS, attempt);
        }
        if (run.missingTool()) environment.markStale(); // a tool the summary listed may be gone (or never was there)
        String summary = plan.verify().isEmpty() ? VERIFY_PASSED + " (the plan has no verify commands)."
                : run.failed() == 0 ? VERIFY_PASSED + "."
                : run.onlyMissingTools() ? VERIFY_UNAVAILABLE + ": " + run.failed() + " of " + plan.verify().size()
                + " commands use a tool that is not installed, so the changes are not checked."
                : VERIFY_FAILED + ": " + run.failed() + " of " + plan.verify().size() + " commands failed.";
        List<String> outputs = new ArrayList<>(run.outputs());
        outputs.addFirst(summary);
        // After the summary: its first line is what the routers read
        if (undone) outputs.add(1, "The design fix broke the build, so its changes were undone (the files are as they were"
                + " when the build passed); the output below is of the code after that.");
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_VERIFY_OUTPUT, String.join("\n\n", outputs));
        updates.put(State.CODING_ATTEMPTS, attempt);
        updates.put(State.CODING_FIXES, run.failed() == 0 || run.onlyMissingTools() ? "" : toJson(findingsFromBuild(files(state), run.failedOutput())));
        if (!state.codingGood().isBlank()) {
            deleteFolder(state.codingGood()); // the design fix was either kept (the build passes) or undone
            updates.put(State.CODING_GOOD, "");
        }
        if (undone) updates.put(State.CODING_DESIGN_UNDONE, 1);
        return updates;
    }

    /**
     * Passed → design review; failed with places to repair and attempts left → fix; otherwise (nothing to repair, or
     * the last attempt failed) → report.
     */
    public static String afterVerify(State state, Limits limits) {
        if (stopped(state)) return STOPPED;
        if (verifyPassed(state)) return "passed";
        return state.codingAttempts() < limits.verifyAttempts() && !state.codingFixes().isBlank()
                && !state.codingFixes().equals("[]") ? "fix" : "failing";
    }

    /** The most characters of a failed build's output that go into the variable {@code check}. */
    private static final int MAX_CHECK_CHARS = 3_000;
    /** A build that failed, but not on anything this job changed. */
    private static final String VERIFY_UNRELATED = "Verify failed elsewhere";

    /** The tools of the free route that write a file: the check looks at the files they changed. */
    private static final Set<String> WRITING_TOOLS = Set.of("editSection", "patchFile", "writeFile", "javaAdd", "javaReplace", "javaDelete",
            "javaImport", "javaCreate", "javaPatch");

    /**
     * The files a tool-using step changed, relative to the trusted folder: the {@code path} of each call of a writing tool that did not fail,
     * from the skill's transcript. (The free route has no plan and no ledger; the transcript is what it did.)
     */
    static List<String> changedPaths(State state) {
        Map<String, ToolExecutionRequest> calls = new LinkedHashMap<>();
        Set<String> failed = new java.util.HashSet<>();
        for (dev.langchain4j.data.message.ChatMessage message : state.skillMessages()) {
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().stream().filter(c -> WRITING_TOOLS.contains(c.name())).forEach(c -> calls.put(c.id(), c));
            } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage result
                    && (Boolean.TRUE.equals(result.isError()) || result.text() == null || result.text().startsWith("Error"))) {
                failed.add(result.id());
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        for (ToolExecutionRequest call : calls.values()) {
            if (failed.contains(call.id())) continue;
            String path = argument(call, "path");
            if (path.isBlank()) continue;
            try {
                Path folder = WorkFolder.resolveInside(state.workFolder(), "");
                paths.add(folder.relativize(WorkFolder.resolveInside(state.workFolder(), path)).toString().replace('\\', '/'));
            } catch (IOException | RuntimeException e) {
                // a path outside the folder was refused by the tool itself
            }
        }
        return List.copyOf(paths);
    }

    /** Whether a failed build's output names one of the changed files (by its file name): then the work may have caused it. */
    static boolean namesChangedFile(String output, List<String> changed) {
        for (String file : changed) {
            String name = file.substring(file.lastIndexOf('/') + 1);
            if (!name.isBlank() && output.contains(name)) return true;
        }
        return false;
    }

    /**
     * The free route's check (no plan, no model): runs the build command of the project the work changed ({@link VerifyDefaults#detectFor}:
     * the wrapper, gradle, mvn, npm, cargo, go, or a compile of the Java files of a small plain project) and says how it went in the skill
     * variable {@code check}: "passed", "failed: …" with the end of the output, or why nothing was checked. The work step is told to check its
     * work itself, but a graph step is what guarantees that a broken build is never reported as done. Only what the job changed is checked:
     * no changed file means no check, and a build that fails without naming a changed file is reported, not repaired (it was broken
     * before, or by something else). The commands are the program's own, never the model's, so they need no approval.
     */
    public Map<String, Object> check(State state) {
        Map<String, Object> updates = new HashMap<>();
        List<String> changed = changedPaths(state);
        if (changed.isEmpty()) {
            return checked(state, updates, VERIFY_UNAVAILABLE + ": no file was changed.", "no file was changed, so there was nothing to build.");
        }
        List<CodingPlan.Command> commands = VerifyDefaults.normalize(state.workFolder(), VerifyDefaults.detectFor(state.workFolder(), changed));
        if (commands.isEmpty()) {
            return checked(state, updates, VERIFY_UNAVAILABLE + ": no build command is known for the changed files.",
                    "no build command is known for the changed files, so the work was not checked by a build.");
        }
        String where = commands.stream().map(c -> CodingPlan.describe(c).replace("`", "")).collect(java.util.stream.Collectors.joining("; "));
        try {
            Run run = runVerify(state, new CodingPlan("checking the work", List.of(), List.of(), List.of(), commands), "check-" + state.skillSteps());
            String output = String.join("\n\n", run.outputs());
            if (run.failed() == 0) return checked(state, updates, VERIFY_PASSED + ".\n\n" + output, "passed (" + where + ").");
            if (run.onlyMissingTools()) {
                return checked(state, updates, VERIFY_UNAVAILABLE + ".\n\n" + output, "could not run: the tool for the build command is not installed.");
            }
            String tail = run.failedOutput().length() > MAX_CHECK_CHARS
                    ? "[... cut ...]\n" + run.failedOutput().substring(run.failedOutput().length() - MAX_CHECK_CHARS) : run.failedOutput();
            if (!namesChangedFile(run.failedOutput(), changed)) {
                return checked(state, updates, VERIFY_UNRELATED + ".\n\n" + output, "failed (" + where + "), but its messages name none of the changed files ("
                        + String.join(", ", changed) + "), so it was left alone:\n" + tail.strip());
            }
            return checked(state, updates, VERIFY_FAILED + ".\n\n" + output, "failed (" + where + "):\n" + tail.strip());
        } catch (RuntimeException e) {
            return checked(state, updates, VERIFY_UNAVAILABLE + ": " + e.getMessage(), "could not run: " + e.getMessage());
        }
    }

    private static Map<String, Object> checked(State state, Map<String, Object> updates, String verifyOutput, String variable) {
        updates.put(State.CODING_VERIFY_OUTPUT, verifyOutput);
        updates.put(State.SKILL_VARS, net.rka.ai.agent.skills.GenericKinds.withVar(state, "check", variable));
        return updates;
    }

    /** Passed → passed; nothing to check, a build that could not run, or a failure of something the job did not change → none; failed on the job's files → failing. */
    public static String afterCheck(State state) {
        if (verifyPassed(state)) return "passed";
        String output = state.codingVerifyOutput();
        return verifyUnavailable(state) || output.startsWith(VERIFY_UNRELATED) ? "none" : "failing";
    }

    /** The messages of a failed run, grouped by the section that holds the line they point at. */
    private static List<Finding> findingsFromBuild(ProjectFiles files, String output) {
        Path folder = files.root();
        Map<String, Outline> outlines = new HashMap<>();
        Map<String, StringBuilder> bySection = new LinkedHashMap<>();
        Map<String, String[]> keys = new HashMap<>();
        for (BuildErrors.Position position : BuildErrors.parse(output, folder)) {
            try {
                Outline outline = outlines.computeIfAbsent(position.file(), f -> {
                    try {
                        Path file = WorkFolder.resolveInside(folder, f);
                        return Outlines.of(file.getFileName().toString(), files.readBytes(file));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Section section = innermost(outline, position.line());
                if (section == null) continue;
                String key = position.file() + "\u0000" + section.id();
                keys.putIfAbsent(key, new String[]{position.file(), section.id()});
                bySection.computeIfAbsent(key, k -> new StringBuilder())
                        .append("line ").append(position.line()).append(": ").append(position.message()).append('\n');
            } catch (RuntimeException e) {
                // an unreadable file: its messages can't be placed
            }
        }
        List<Finding> findings = new ArrayList<>();
        bySection.forEach((key, text) -> findings.add(new Finding(keys.get(key)[0], keys.get(key)[1],
                text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) + "\n[... cut ...]" : text.toString().strip())));
        return findings;
    }

    /** The smallest section that holds the line, never a class header or a whole class when a part holds it. */
    private static Section innermost(Outline outline, int line) {
        Section best = null;
        for (Section section : outline.sections()) {
            if (section.start() <= line && line <= section.end()
                    && (best == null || section.lines() < best.lines())) {
                best = section;
            }
        }
        return best;
    }

    /** The verify commands could not run (their tool is missing): nothing shows the code is wrong, so nothing is taken back. */
    private static boolean verifyUnavailable(State state) {
        return state.codingVerifyOutput().startsWith(VERIFY_UNAVAILABLE);
    }

    private static boolean verifyPassed(State state) {
        return state.codingVerifyOutput().startsWith(VERIFY_PASSED);
    }

    // ---- fix ----

    /**
     * Repairs what verify or the design review found: one model call per section (at most {@value #MAX_FIX_SECTIONS}),
     * the section's text and its messages in, its new text out, applied like an edit; the answer {@code SKIP} leaves a
     * section alone (the failing line is a symptom). A finding in a file the plan doesn't change is never edited
     * silently: the plan gets a change for it and goes back to the user (at most {@value #MAX_REVISIONS} times). When
     * nothing could be changed the job reports, so the same failure isn't verified again.
     */
    public Map<String, Object> fix(State state, String instructions) {
        String unapproved = notApproved(state);
        if (unapproved != null) return Map.of(State.CODING_STOP_REASON, unapproved);
        CodingPlan plan = state.codingPlan().orElseThrow();
        List<Finding> findings = parseFindings(state.codingFixes());
        Set<String> planned = new LinkedHashSet<>(plan.changedFiles());
        List<Finding> inPlan = perSection(findings.stream().filter(f -> planned.contains(f.file())).toList());
        List<Finding> outside = findings.stream().filter(f -> !planned.contains(f.file())).toList();

        int changedSections = 0;
        List<String> notes = new ArrayList<>();
        for (Finding finding : inPlan.stream().limit(MAX_FIX_SECTIONS).toList()) {
            Change change = new Change(finding.file(), finding.section(), Action.REPLACE, "fix: " + oneLine(finding.text(), 300));
            AiMessage reply;
            try {
                reply = askResponse(JobPhase.FIX, state, instructions, fixPrompt(state, finding, change), WRITE_EFFORT, null, EditFunction.spec()).aiMessage();
            } catch (RuntimeException e) {
                return Map.of(State.CODING_STOP_REASON, reason(MODEL_ERROR, e), State.CODING_FIXES, "");
            }
            Optional<String> code = EditFunction.texts(reply).stream().findFirst();
            if (code.isEmpty() || code.get().strip().equalsIgnoreCase(SKIP)) {
                notes.add(finding.file() + " · " + finding.section() + ": left as it is");
                continue;
            }
            try {
                try {
                    apply(files(state), change, code.get());
                } catch (ApplyFailure same) {
                    if (!same.getMessage().startsWith("your text changes nothing")) throw same;
                    // The answer was the code that is there: asking again alone gives it again, so the second ask shows what the
                    // message is about (the methods it names, their code) and says the first answer changed nothing
                    String extra = calleeSources(files(state), finding);
                    AiMessage again = askResponse(JobPhase.FIX, state, instructions, fixPrompt(state, finding, change) + extra
                            + "\n\nYour answer was the same code as the section has now, which fixes nothing. Change the code so the"
                            + " message is gone, or call write_code with no texts if the cause is not in this section.",
                            WRITE_EFFORT, null, EditFunction.spec()).aiMessage();
                    Optional<String> second = EditFunction.texts(again).stream().findFirst();
                    if (second.isEmpty() || second.get().strip().equalsIgnoreCase(SKIP)) {
                        notes.add(finding.file() + " · " + finding.section() + ": left as it is");
                        continue;
                    }
                    apply(files(state), change, second.get());
                }
                changedSections++;
            } catch (ApplyFailure | RuntimeException e) {
                notes.add(finding.file() + " · " + finding.section() + ": " + e.getMessage());
            }
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_FIXES, "");
        if (!outside.isEmpty()) return outsideThePlan(state, plan, outside, updates);
        if (changedSections == 0) {
            updates.put(State.CODING_STOP_REASON, "the fix step could not change anything"
                    + (notes.isEmpty() ? "." : " (" + String.join("; ", notes) + ")."));
        }
        return updates;
    }

    private static final java.util.regex.Pattern NAMED_METHOD = java.util.regex.Pattern.compile(
            "(?:method|constructor)\\s+(\\w+)\\s+in\\s+(?:class|interface|enum|record)|symbol:\\s+method\\s+(\\w+)");

    /** The code of the methods that a build message names (in the same file), so a repair sees what it calls. "" when none is named. */
    private static String calleeSources(ProjectFiles files, Finding finding) {
        java.util.Set<String> names = new LinkedHashSet<>();
        java.util.regex.Matcher m = NAMED_METHOD.matcher(finding.text());
        while (m.find()) names.add(m.group(1) != null ? m.group(1) : m.group(2));
        if (names.isEmpty()) return "";
        try {
            Path file = WorkFolder.resolveInside(files.root(), finding.file());
            byte[] bytes = files.readBytes(file);
            Outline outline = Outlines.of(file.getFileName().toString(), bytes);
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Section s : outline.sections()) {
                if (shown >= 3 || !Set.of("method", "constructor").contains(s.kind())) continue;
                boolean named = names.stream().anyMatch(n -> s.id().contains("." + n + "(") || s.id().startsWith(n + "("));
                if (!named || s.id().equals(finding.section())) continue;
                String text = String.join("\n", lines.subList(s.start() - 1, Math.min(s.end(), lines.size())));
                if (text.length() > 1500) text = text.substring(0, 1500) + "\n[... cut ...]";
                out.append("\n\nThe code of `").append(s.id()).append("`, which the message is about:\n```\n").append(text).append("\n```");
                shown++;
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The findings of one section as one finding (the model repairs a section once, with every problem in front of it). */
    private static List<Finding> perSection(List<Finding> findings) {
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding finding : findings) grouped.computeIfAbsent(finding.file() + "\u0000" + finding.section(), k -> new ArrayList<>()).add(finding);
        List<Finding> merged = new ArrayList<>();
        for (List<Finding> group : grouped.values()) {
            String text = group.size() == 1 ? group.get(0).text()
                    : String.join("\n", group.stream().map(f -> "- " + f.text().replaceAll("\\s+", " ")).toList());
            merged.add(new Finding(group.get(0).file(), group.get(0).section(), text));
        }
        return merged;
    }

    /** Fixed → verify again; a change outside the plan → the user; nothing changed or a model error → report. */
    public static String afterFix(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingRevisionReason().isBlank() ? "verify" : "revise";
    }

    /**
     * The findings are in files the plan doesn't change: a revised plan with a change for each goes to the user, who
     * decides. The job stops when it has already come back this way {@value #MAX_REVISIONS} times.
     */
    private Map<String, Object> outsideThePlan(State state, CodingPlan plan, List<Finding> outside, Map<String, Object> updates) {
        int revisions = state.codingRevisions() + 1;
        String why = "the build reports problems in files the plan doesn't change: "
                + String.join("; ", outside.stream().map(f -> f.file() + " · " + f.section()).toList());
        if (revisions > MAX_REVISIONS) {
            updates.put(State.CODING_REVISIONS, revisions);
            updates.put(State.CODING_STOP_REASON, why + ". The job already asked about this " + MAX_REVISIONS + " times.");
            return updates;
        }
        if (outside.size() > MAX_OUTSIDE_FINDINGS) {
            // Problems in many files the job never touched: the verify command does not fit the project, not a thing a plan repairs
            updates.put(State.CODING_STOP_REASON, "the verify command reports problems in " + outside.size() + " files the plan doesn't change"
                    + " (" + String.join("; ", outside.stream().limit(3).map(f -> f.file()).toList()) + ", …): it does not build this project as"
                    + " the project is built. Name the project's own build command and run the job again.");
            return updates;
        }
        List<Change> changes = new ArrayList<>(plan.changes());
        for (Finding finding : outside) {
            changes.add(new Change(finding.file(), finding.section(), Action.REPLACE, "fix: " + oneLine(finding.text(), 300)));
        }
        updates.put(State.CODING_PLAN, plan.with(List.of(), plan.setup(), changes, plan.verify()).toJson());
        updates.put(State.CODING_REVISION_REASON, why + ".");
        updates.put(State.CODING_DECISION, "");
        updates.put(State.CODING_REVISIONS, revisions);
        return updates;
    }

    private String fixPrompt(State state, Finding finding, Change change) {
        return "Repair `" + finding.file() + "` · `" + finding.section() + "`.\nWhat is wrong:\n" + finding.text()
                + "\n\n" + sectionContext(files(state), change) + existingMembers(files(state), finding)
                + "\n\nThe place that is reported may only show the symptom: change this section only if the cause is in it;"
                + " otherwise call write_code with no texts.";
    }

    /**
     * For a "cannot find symbol" the repair needs to know what does exist: the file's outline (its members with their
     * signatures), so a name the model invented can become a real one. "" for any other message.
     */
    private static String existingMembers(ProjectFiles files, Finding finding) {
        String text = finding.text();
        if (!text.contains("cannot find symbol") && !text.contains("cannot be resolved") && !text.contains("does not exist")
                && !text.contains("cannot be applied") && !text.contains("no suitable") && !text.contains("incompatible types")) {
            return "";
        }
        try {
            Path file = WorkFolder.resolveInside(files.root(), finding.file());
            Outline outline = Outlines.of(file.getFileName().toString(), files.readBytes(file));
            StringBuilder out = new StringBuilder("\n\nWhat exists in this file (use only these members, or ones the build message names):\n")
                    .append(outline.render(finding.file(), null));
            if (finding.file().endsWith(".java")) {
                // A class that is used but missing: its imports show what is available, and the build's wording "symbol: class X" is
                // fixed by an import or by another name. A call with wrong arguments: the signatures above show the right ones.
                String imports = files.readString(file).lines().filter(l -> l.startsWith("import ")).collect(java.util.stream.Collectors.joining("\n"));
                if (!imports.isBlank()) out.append("\n\nThe imports of this file (a class that is used but not imported needs one):\n").append(imports);
                out.append("\n\nIf the build says a class or method does not exist anywhere (nothing in the project or the JDK has that name),"
                        + " do not invent it: write the code with what exists.");
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    // ---- designReview ----

    /**
     * Once the build passes: the model reads what the job changed (a diff for a file that existed, a new file whole)
     * and checks it against the design guide, answering with a list of issues. The issues go to the fix step (once,
     * {@code maxDesignRounds}), then verify runs again. No review runs when no design guide applies or nothing in the
     * changed files belongs to one. A review that can't run or be read never fails the job: it is noted in the report.
     */
    public Map<String, Object> designReview(State state, String instructions) {
        if (state.codingDesignRounds() >= limits.designRounds()) return Map.of(); // reviewed already: what it said stays
        CodingPlan plan = state.codingPlan().orElseThrow();
        Set<String> languages = guideLanguages(state);
        String guide = guides.text(languages);
        Map<String, String> files = filesToReview(state, plan, languages);
        if (guide.isBlank() || files.isEmpty() || changedLines(files.values()) < MIN_REVIEW_LINES) {
            return Map.of(State.CODING_DESIGN_ISSUES, "", State.CODING_FIXES, ""); // nothing to review, or too little
        }

        Ledger ledger = state.codingLedger().resizedTo(plan.changes().size());
        StringBuilder prompt = new StringBuilder("Sections the job changed:\n").append(madeSoFar(plan, ledger))
                .append("\n\nThe code to review. A file that existed before shows only what changed, as a unified diff (lines"
                        + " starting with + are new, - removed, the rest is context); review the new code. A new file is shown whole.\n");
        files.forEach((path, code) -> prompt.append("\n### ").append(path).append("\n```\n").append(code).append("\n```\n"));
        AiMessage reply;
        try {
            reply = ask(JobPhase.DESIGN_REVIEW, state, instructions, prompt.toString());
        } catch (RuntimeException e) {
            // The job's work is done and verified; a review that can't run is only noted
            return Map.of(State.CODING_DESIGN_ROUNDS, limits.designRounds(),
                    State.CODING_DESIGN_ISSUES, "The design review could not run: " + reason(MODEL_ERROR, e), State.CODING_FIXES, "");
        }
        List<Finding> issues = designFindings(textOf(reply), plan, ledger);
        if (issues == null) {
            // Not a list of issues: don't claim the code is fine, and don't change working code on a guess
            return Map.of(State.CODING_DESIGN_ROUNDS, limits.designRounds(),
                    State.CODING_DESIGN_ISSUES, "The design review's answer could not be read, so the code wasn't checked.",
                    State.CODING_FIXES, "");
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_DESIGN_ROUNDS, state.codingDesignRounds() + 1);
        updates.put(State.CODING_DESIGN_ISSUES, issues.isEmpty() ? "" : markdown(issues));
        updates.put(State.CODING_FIXES, issues.isEmpty() ? "" : toJson(issues));
        // The build passes now: keep this state, so a fix that breaks it can be undone
        if (!issues.isEmpty()) updates.put(State.CODING_GOOD, keepGood(state, plan));
        return updates;
    }

    /**
     * Once the build passes: does the change do what the user asked? One check at high effort, from the request and the diff of every
     * changed file (a build only shows the code compiles). The answer is {@code ok}, {@code doubt: …} or, when the model cannot be
     * reached or does not answer in that form, nothing: the report then says the build passes and does not claim more.
     */
    public Map<String, Object> intent(State state, String instructions) {
        // A final verdict (ok, decided, doubt) stands whatever a repair changed after; a gap or a question is checked again once
        // the revised plan has been carried out
        if (!state.codingIntent().isBlank() && !state.codingIntent().startsWith(GAP) && !state.codingIntent().startsWith(ASK)) return Map.of();
        CodingPlan plan = state.codingPlan().orElseThrow();
        Map<String, String> code = changedCode(state, plan, relative -> true);
        if (code.isEmpty()) return Map.of(State.CODING_INTENT, "");
        StringBuilder prompt = new StringBuilder("The user's request:\n").append(state.codingGoal()).append("\n\nThe job's changes:\n")
                .append(madeSoFar(plan, state.codingLedger().resizedTo(plan.changes().size())))
                .append("\n\nThe changed code (a file that existed before shows what changed as a unified diff: lines starting with + are new,"
                        + " - removed, the rest is context; a new file is shown whole):\n");
        code.forEach((path, text) -> prompt.append("\n### ").append(path).append("\n```\n").append(text).append("\n```\n"));
        String verdict = "";
        try {
            String answer = textOf(ask(JobPhase.INTENT, state, instructions, prompt.toString(), DECIDING_EFFORT)).strip();
            String lower = answer.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("ok")) verdict = "ok";
            else {
                String said = answer.substring(answer.indexOf(':') >= 0 ? answer.indexOf(':') + 1 : Math.min(answer.length(), 4)).strip();
                if (lower.startsWith("gap")) verdict = GAP + " " + said;
                else if (lower.startsWith("ask")) verdict = ASK + " " + said;
                else if (lower.startsWith("doubt")) verdict = "doubt: " + said;
            }
        } catch (RuntimeException e) {
            // not checked: the report says only that the build passes
        }
        boolean gap = verdict.startsWith(GAP);
        boolean ask = verdict.startsWith(ASK);
        if (!gap && !ask) return Map.of(State.CODING_INTENT, verdict);
        int revisions = state.codingRevisions() + 1;
        if (revisions > MAX_REVISIONS) {
            // The job has already come back this way as often as it may: the doubt is the end result, and the report says it
            return Map.of(State.CODING_INTENT, "doubt: " + verdict.substring((gap ? GAP : ASK).length()).strip(), State.CODING_REVISIONS, revisions);
        }
        String said = verdict.substring((gap ? GAP : ASK).length()).strip();
        Map<String, Object> updates = new HashMap<>();
        updates.put(State.CODING_INTENT, verdict);
        updates.put(State.CODING_REVISIONS, revisions);
        updates.put(State.CODING_DECISION, "");
        updates.put(State.CODING_COMMENT, "");
        updates.put(State.CODING_REVISION_REASON, gap
                ? "the build passes, but the review found the request is not met yet: " + said
                : "the build passes, but the review needs a decision from you: " + said
                        + " Approve to keep the plan as it is, or send a comment to change it.");
        return updates;
    }

    /** What the intent check found: the request is not met yet, and the job can plan what is missing. */
    static final String GAP = "gap:";
    /** What the intent check found: the request leaves a decision that only the user can make. */
    static final String ASK = "ask:";

    /**
     * After the intent check: a gap goes back to the plan step (the revised plan is shown to the user), a question to the user
     * with the plan as it is; anything else goes on to the design review.
     */
    public static String afterIntent(State state) {
        if (stopped(state)) return STOPPED;
        String intent = state.codingIntent();
        return intent.startsWith(GAP) ? "gap" : intent.startsWith(ASK) ? "ask" : "done";
    }

    /** The lines a review would look at: the added and removed lines of a diff, all lines of a new file. */
    static int changedLines(java.util.Collection<String> codes) {
        int lines = 0;
        for (String code : codes) {
            if (code.startsWith("---")) {
                lines += (int) code.lines().filter(l -> (l.startsWith("+") && !l.startsWith("+++"))
                        || (l.startsWith("-") && !l.startsWith("---"))).count();
            } else {
                lines += (int) code.lines().count();
            }
        }
        return lines;
    }

    /** Issues and a fix round left → fix; otherwise (or after the review's fixes) → report. */
    public static String afterDesignReview(State state) {
        if (stopped(state)) return STOPPED;
        return state.codingDesignIssues().startsWith("- ") && !state.codingFixes().isBlank() ? "fix" : "done";
    }

    /**
     * The review's answer (a JSON array of issues, possibly in a code fence or with text around it) as findings, each
     * in a section the job changed: the issue's own section when it names one, else the first changed section of its
     * file. Empty when it found none; null when the answer isn't a JSON array (then nothing is known about the code).
     */
    static List<Finding> designFindings(String answer, CodingPlan plan, Ledger ledger) {
        int start = answer.indexOf('[');
        int end = answer.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode issues = JSON.readTree(answer.substring(start, end + 1));
            if (!issues.isArray()) return null;
            List<Finding> findings = new ArrayList<>();
            for (JsonNode issue : issues) {
                if (findings.size() >= MAX_DESIGN_ISSUES) break;
                String problem = issue.path("problem").asText("").strip();
                String file = issue.path("file").asText("").strip();
                if (problem.isEmpty()) continue;
                String named = issue.path("section").asText("").strip();
                String section = !named.isEmpty() && plan.changes().stream()
                        .anyMatch(c -> c.file().equals(file) && c.section().equals(named))
                        ? named : changedSectionOf(plan, ledger, file);
                if (section == null) continue; // an issue in code the job didn't change
                String fix = issue.path("fix").asText("").strip();
                String rule = issue.path("rule").asText("").strip();
                findings.add(new Finding(file, section, problem + (fix.isEmpty() ? "" : " → " + fix)
                        + (rule.isEmpty() ? "" : " (" + rule + ")")));
            }
            return findings;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** The section of the first applied change in the file, or null when the job didn't change it. */
    private static String changedSectionOf(CodingPlan plan, Ledger ledger, String file) {
        for (int i : ledger.indexes(Ledger.Status.APPLIED)) {
            Change change = plan.changes().get(i);
            if (change.file().equals(file) && !change.section().isBlank()) return change.section();
        }
        return null;
    }

    private static String markdown(List<Finding> findings) {
        return String.join("\n", findings.stream()
                .map(f -> "- `" + f.file() + "` · `" + f.section() + "`: " + f.text().replaceAll("\\s+", " ")).toList());
    }

    private List<Finding> parseFindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<Finding>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private final JavaWorkspaces workspaces;
    private static final int MAX_BASELINE_FILES = 500;
    private static final long MAX_BASELINE_BYTES = 5_000_000;

    /**
     * Copies the existing files the plan changes into a folder outside the project, once per file per job, so the
     * design review can compare. A revision's new files are added to the same folder; files copied before stay as they
     * were before the job. Returns the folder ("" if nothing could be copied: then whole files are reviewed).
     */
    private String snapshot(State state, CodingPlan plan) {
        files(state).flush(); // the copies are made from the files: write what is in memory first
        try {
            Path folder = state.workFolder();
            Path baseline = state.codingBaseline().isBlank()
                    ? Files.createTempDirectory("coding-job-baseline-") : Path.of(state.codingBaseline());
            int files = 0;
            long bytes = 0;
            // A file the job creates has no "before": at a later approval (a revised plan) it already exists, and copying it then would make
            // a rollback "restore" the job's own file instead of removing it
            Set<String> created = new java.util.HashSet<>();
            for (Change change : plan.changes()) if (change.action() == Action.CREATE) created.add(change.file());
            for (String relative : plan.changedFiles()) {
                if (created.contains(relative)) continue;
                Path file;
                try {
                    file = WorkFolder.resolveInside(folder, relative);
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                if (!Files.isRegularFile(file) || files >= MAX_BASELINE_FILES || bytes >= MAX_BASELINE_BYTES) continue;
                Path copy = baseline.resolve(relative);
                if (Files.exists(copy)) continue; // copied at an earlier approval: that is the "before"
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
                files++;
                bytes += Files.size(file);
            }
            return baseline.toString();
        } catch (IOException | RuntimeException e) {
            return state.codingBaseline(); // no (new) copies: the review falls back to whole files
        }
    }

    /** Copies the existing files the plan changes into a new temporary folder; "" when that fails (then no fix is undone). */
    private String keepGood(State state, CodingPlan plan) {
        files(state).flush();
        try {
            Path good = Files.createTempDirectory("coding-job-good-");
            for (String relative : plan.changedFiles()) {
                Path file = WorkFolder.resolveInside(state.workFolder(), relative);
                if (!Files.isRegularFile(file)) continue;
                Path copy = good.resolve(relative);
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
            return good.toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Puts the copies of {@link #keepGood} back over the plan's files. */
    private void restoreGood(State state, CodingPlan plan) {
        files(state).discardAll(); // what is in memory is what is being undone
        Path good = Path.of(state.codingGood());
        for (String relative : plan.changedFiles()) {
            Path copy = good.resolve(relative);
            if (!Files.isRegularFile(copy)) continue;
            try {
                Files.copy(copy, WorkFolder.resolveInside(state.workFolder(), relative),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                System.getLogger(CodingGraph.class.getName()).log(System.Logger.Level.WARNING, "Could not restore " + relative, e);
            }
        }
        files(state).sync(); // the workspace takes in the restored files
    }

    /** The change from {@code before} to {@code after} as a unified diff with a few lines of context. */
    static String changes(String path, String before, String after) {
        List<String> from = before.lines().toList();
        List<String> to = after.lines().toList();
        com.github.difflib.patch.Patch<String> patch = com.github.difflib.DiffUtils.diff(from, to);
        return String.join("\n", com.github.difflib.UnifiedDiffUtils.generateUnifiedDiff(path, path, from, patch, 4));
    }

    /** Deletes the job's copies (best effort: they are in the system's temporary folder anyway). */
    private static void deleteBaseline(State state) {
        deleteFolder(state.codingBaseline());
        deleteFolder(state.codingGood());
    }

    private static void deleteFolder(String folder) {
        if (folder.isBlank()) return;
        try (Stream<Path> walk = Files.walk(Path.of(folder))) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // left for the system to clean up
        }
    }

    /**
     * The files the plan changed that a guide is about, read from the trusted folder: a diff against the baseline for
     * a file that existed, the whole file for a new one, nothing for a file the job didn't change; up to
     * {@link #MAX_REVIEW_FILES} files and {@link #MAX_REVIEW_CHARS} characters in total.
     */
    private Map<String, String> filesToReview(State state, CodingPlan plan, Set<String> languages) {
        return changedCode(state, plan, relative -> DesignGuides.isAbout(relative, languages));
    }

    /** The changed code of the files the filter takes, as {@link #filesToReview} shows it. */
    private Map<String, String> changedCode(State state, CodingPlan plan, java.util.function.Predicate<String> wanted) {
        Map<String, String> files = new LinkedHashMap<>();
        Path folder = state.workFolder();
        Path baseline = state.codingBaseline().isBlank() ? null : Path.of(state.codingBaseline());
        int chars = 0;
        for (String relative : plan.changedFiles()) {
            if (!wanted.test(relative)) continue;
            if (files.size() >= MAX_REVIEW_FILES || chars >= MAX_REVIEW_CHARS) break;
            try {
                String code = files(state).readString(WorkFolder.resolveInside(folder, relative)) /* memory is the truth: Java changes are written later */;
                Path before = baseline == null ? null : baseline.resolve(relative);
                if (before != null && Files.isRegularFile(before)) {
                    String original = Files.readString(before);
                    if (original.equals(code)) continue; // unchanged by the job: nothing to review
                    String diff = changes(relative, original, code);
                    if (diff.length() < code.length()) code = diff; // a big file with a small change: just the change
                }
                if (code.length() > MAX_REVIEW_CHARS - chars) {
                    code = code.substring(0, MAX_REVIEW_CHARS - chars) + "\n[... the rest of the file is cut ...]";
                }
                chars += code.length();
                files.put(relative, code);
            } catch (IOException | RuntimeException e) {
                // unreadable (binary, other encoding): not reviewed
            }
        }
        return files;
    }

    // ---- report ----

    /**
     * Ends the job: builds the Markdown report (outcome, the approved plan with its changes marked, the design
     * review, the last verify output) and answers the conversation's {@code startCodingJob} call with it, so the report
     * is saved with the conversation and the tool chip shows it. Anything but "done" is flagged as an error, so the
     * model sees the job didn't succeed.
     * <p>
     * Every early end comes here through a "stopped" route with its reason in {@code codingStopReason}, or in
     * {@code skillStopReason} when a step threw something else (the skill graph catches it and takes the stopped route).
     */
    public Map<String, Object> report(State state) {
        Optional<CodingPlan> approved = state.codingApprovedPlan();
        Ledger ledger = approved.map(p -> state.codingLedger().resizedTo(p.changes().size())).orElse(state.codingLedger());
        Set<Integer> applied = ledger.indexes(Ledger.Status.APPLIED);
        Set<Integer> failed = ledger.indexes(Ledger.Status.FAILED);
        String stopReason = !state.codingStopReason().isBlank() ? state.codingStopReason() : state.skillStopReason();
        // A folder that doesn't build is worse than the one the user started with: whatever ended the job (a build that
        // still fails, a repair that could change nothing, an error), changes whose build was not shown to pass are
        // taken back. Changes whose build passed stay, whatever happened after.
        boolean approvedRun = approved.isPresent() && APPROVE.equals(state.codingDecision());
        String restored = approvedRun && ledger.anyApplied() && !verifyPassed(state) && !verifyUnavailable(state) ? rollBack(state, approved.get()) : "";
        boolean rolledBack = !restored.isEmpty();
        // At the end the Java files held in memory are written; a file that changed on disk meanwhile is left alone and named
        JavaWorkspace.FlushResult written = rolledBack ? null : files(state).flush();
        String unwritten = written == null || written.ok() ? ""
                : " Not written, because they changed on disk meanwhile: " + String.join(", ", written.conflicts()) + ".";
        String changed = rolledBack ? restored : ledger.anyApplied() ? applied.size() + " of " + ledger.entries().size()
                + " changes were made and stay in the folder." : "Nothing was changed.";
        String outcome;
        boolean success = false;
        if (!stopReason.isBlank()) {
            outcome = "Stopped: " + sentence(stopReason) + " " + changed;
        } else if (!APPROVE.equals(state.codingDecision())) {
            // Like afterPlanApproval: anything but approve counts as deny
            outcome = "Denied: the user denied the plan. " + changed;
        } else if (verifyUnavailable(state) && failed.isEmpty()) {
            outcome = "Done, but not verified: every change is made; the verify commands could not run because their tool is not installed"
                    + " (output below).";
        } else if (verifyPassed(state)) {
            boolean noVerify = approved.map(p -> p.verify().isEmpty()).orElse(false);
            if (!failed.isEmpty()) {
                outcome = "Done with failed changes: " + failedChanges(approved, ledger) + ". The other changes are made"
                        + (noVerify ? " (the plan has no verify commands)." : " and the verify commands pass.");
            } else if (state.codingIntent().startsWith("doubt")) {
                // The build passes, but a check of the diff against the request found something wrong or missing: no "done"
                outcome = "Built, but the review doubts the change does what was asked: " + state.codingIntent().substring("doubt:".length()).strip()
                        + " The changes are made and stay in the folder.";
            } else {
                String built = noVerify ? "every change is made (the plan has no verify commands)"
                        : "every change is made and the build passes";
                outcome = "Done: " + built + (state.codingIntent().equals("ok") ? ", and the review agrees it does what was asked."
                        : state.codingIntent().equals(DECIDED) ? ", and the review's open question was settled by you." : ".");
                success = true;
            }
        } else {
            outcome = "Still failing after " + state.codingAttempts() + " verify attempts: "
                    + (failed.isEmpty() ? "every change was made" : "the changes were made except the failed ones ("
                    + failedChanges(approved, ledger) + ")") + ", but the verify commands fail (output below). "
                    + (rolledBack ? restored : ledger.anyApplied() ? "The files could not be restored: they keep the changes." : "");
        }

        StringBuilder report = new StringBuilder(outcome + unwritten).append('\n');
        Optional<CodingPlan> current = state.codingPlan();
        if (approved.isPresent()) {
            // The plan the user approved last, which is what the job worked by
            // After a rollback no change is in the files any more, so none is marked made
            report.append("\n## Plan\n\n").append(approved.get().toMarkdown(rolledBack ? Set.of() : applied, failed));
            // The job ended on a revision that was denied, or never became valid
            if (current.isPresent() && !current.get().equals(approved.get())) {
                report.append("\n## Revised plan (not approved)\n\n").append(current.get().toMarkdown(rolledBack ? Set.of() : applied, failed));
            }
        } else {
            current.ifPresent(plan -> report.append("\n## Plan (not approved)\n\n").append(plan.toMarkdown()));
        }
        if (state.codingDesignRounds() > 0) {
            // What the design review said: issues it found were fixed once; what is left is worth a look
            String issues = state.codingDesignIssues();
            report.append("\n## Design review\n\n").append(issues.isBlank()
                    ? "The changed code follows the design guide."
                    : (issues.startsWith("- ") ? (state.codingDesignUndone()
                    ? "Found, but fixing them broke the build, so the fix was undone and these are still open:\n\n"
                    : "Found, and fixed once (the build was checked again):\n\n") : "") + issues).append('\n');
        }
        String verifyOutput = state.codingVerifyOutput();
        if (!verifyOutput.isBlank()) {
            String cut = verifyOutput.length() <= MAX_REPORT_OUTPUT_CHARS ? verifyOutput
                    : verifyOutput.substring(0, MAX_REPORT_OUTPUT_CHARS)
                            + "\n[... " + (verifyOutput.length() - MAX_REPORT_OUTPUT_CHARS) + " more characters cut ...]";
            report.append("\n## Last verify output\n\n```\n").append(cut).append("\n```\n");
        }

        writePlanFile(state, report.toString()); // the file ends as the job's report: outcome, plan with marks
        deleteBaseline(state); // the copies were only for the design review's diffs
        // No code changed (nothing was applied, or it was all taken back), and the user did not decide that themselves by denying the plan:
        // the explain step tells them why and what they can do. The answer to the call is the answer step's.
        boolean denied = stopReason.isBlank() && !APPROVE.equals(state.codingDecision());
        boolean unchanged = !denied && (!ledger.anyApplied() || rolledBack);
        return Map.of(
                State.CODING_REPORT, report.toString(),
                State.CODING_OUTCOME, success ? SUCCESS : unchanged ? UNCHANGED : FAILED);
    }

    /** Where the job ends: no code changed → the user is told why; anything else → the answer. */
    public static String afterReport(State state) {
        return UNCHANGED.equals(state.codingOutcome()) ? UNCHANGED : "done";
    }

    /**
     * Tells the user why the job changed no code and what they can do about it (a model call, from the report and the request).
     * The explanation joins the answer; a model that cannot be reached leaves it out, and the answer then carries the report alone.
     */
    public Map<String, Object> explain(State state, String instructions) {
        String explanation = "";
        try {
            String prompt = "The user's request:\n" + state.codingGoal() + "\n\nThe job's report (no code was changed):\n" + state.codingReport();
            explanation = textOf(ask(JobPhase.EXPLAIN, state, instructions, prompt)).strip();
        } catch (RuntimeException e) {
            // the report alone answers the call
        }
        return Map.of(State.CODING_EXPLANATION, explanation);
    }

    /** Answers the job's call: the report, and the explanation when no code was changed. Agent saves it with the other messages. */
    public Map<String, Object> answer(State state) {
        boolean success = SUCCESS.equals(state.codingOutcome());
        String explanation = state.codingExplanation();
        String closing = success
                ? ""
                : !explanation.isBlank()
                ? "\n## Why nothing was changed, and what the user can do\n\n" + explanation
                        + "\n\nTell the user this explanation shortly in your own words and ask what they want to do. Don't try to do the"
                        + " work yourself with other tools."
                : state.codingIntent().startsWith("doubt") && verifyPassed(state)
                ? "\nThe build passes, but the review doubts the change does what was asked (the outcome's first line). Start your reply"
                        + " with that doubt, say what is left, and ask whether to go on. Don't call the job done, and don't change the"
                        + " code yourself."
                : "\nThe job did not finish. Tell the user why and what is left, and ask what to do. Don't try to do the work yourself"
                        + " with other tools.";
        ToolExecutionResultMessage answer = ToolExecutionResultMessage.builder()
                .id(state.skillCallId()) // the skill layer's call: set by every run's start node
                .toolName(startTool)
                .text(state.codingReport() + closing)
                .isError(!success)
                .build();
        // A job that is done tells the user itself: the outcome and what changed. The agent does not read the report to word it again
        // (a model call that re-reads the whole conversation, about 40 s, to say the same); any other end goes to the agent to discuss.
        if (success) return Map.of(State.MESSAGES, List.of(answer, AiMessage.from(doneText(state))));
        return Map.of(State.MESSAGES, List.of(answer));
    }

    /** What the user is told when the job is done: the outcome line and the changes, one line each. */
    static String doneText(State state) {
        StringBuilder text = new StringBuilder(state.codingReport().lines().findFirst().orElse("Done."));
        state.codingApprovedPlan().ifPresent(plan -> {
            text.append("\n");
            for (Change change : plan.changes()) {
                text.append("\n- `").append(change.file()).append('`')
                        .append(change.section().isBlank() ? "" : " · `" + change.section() + "`")
                        .append(": ").append(change.intent());
            }
        });
        return text.toString();
    }

    /**
     * Puts the plan's files back as they were before the job, from the baseline copies made when the plan was approved,
     * and removes the files the job created ({@code create} changes whose file has no copy). Returns the sentence for the
     * report, or "" when it could not be done (no copies, or a file could not be written: the files then keep the job's
     * changes, which the report says). Only files of the approved plan are touched.
     */
    private String rollBack(State state, CodingPlan plan) {
        if (state.codingBaseline().isBlank()) return "";
        files(state).discardAll(); // the changes in memory go first; a file the job only made in memory is simply gone
        Path copies = Path.of(state.codingBaseline());
        Set<String> created = new java.util.HashSet<>();
        for (Change change : plan.changes()) if (change.action() == Action.CREATE) created.add(change.file());
        int restored = 0;
        int removed = 0;
        try {
            for (String relative : plan.changedFiles()) {
                Path file = WorkFolder.resolveInside(state.workFolder(), relative);
                Path copy = copies.resolve(relative);
                if (Files.isRegularFile(copy)) {
                    Files.copy(copy, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    restored++;
                } else if (created.contains(relative) && Files.deleteIfExists(file)) {
                    removed++;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.getLogger(CodingGraph.class.getName()).log(System.Logger.Level.WARNING, "Could not restore the files", e);
            return "";
        }
        files(state).sync(); // the workspace takes in the restored files
        return "The files are back as they were before the job (" + restored + " restored, " + removed
                + " created file" + (removed == 1 ? "" : "s") + " removed), so the folder builds as it did. The plan below is what the job tried.";
    }

    /** "2 (`A.java` · `A.run()`: why), 3 (...)": the failed changes with the reason from the ledger. */
    private static String failedChanges(Optional<CodingPlan> plan, Ledger ledger) {
        List<String> parts = new ArrayList<>();
        for (int i : ledger.indexes(Ledger.Status.FAILED)) {
            String what = plan.filter(p -> i < p.changes().size())
                    .map(p -> p.changes().get(i).file() + (p.changes().get(i).section().isBlank() ? "" : " · " + p.changes().get(i).section()))
                    .orElse("");
            String note = ledger.entries().get(i).note();
            parts.add((i + 1) + " (" + what + (note.isBlank() ? "" : ": " + oneLine(note, 200)) + ")");
        }
        return String.join(", ", parts);
    }

    /**
     * The job's plan file: {@code plans/<date>-<time>-<goal words>.md}, or "" when no plans folder is set. The file is
     * rewritten as the job goes (the plan to approve, then the report), so it always shows the latest state; it is not
     * part of the conversation and never sent to the model.
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

    // ---- helpers ----

    /**
     * Why setup, edit, fix and verify may not run now, or null when they may: the current plan ({@code codingPlan}, what
     * the job works by) must be the one the user approved last ({@code codingApprovedPlan}). The built-in wiring always
     * asks about a new draft before these steps; this keeps a skill file that wires a second plan step around the
     * approval from running a draft the user never saw (the validator rejects such wiring too, this is the kind's own rule).
     */
    private static String notApproved(State state) {
        Optional<CodingPlan> approved = state.codingApprovedPlan();
        if (approved.isEmpty()) return "there is no approved plan to work by.";
        if (!approved.equals(state.codingPlan())) {
            return "the current plan is not the one the user approved; a changed plan must be approved before it runs.";
        }
        return null;
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

    private static String oneLine(String text, int max) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // maps and records of strings always serialize
        }
    }

    /** The parts of the job's opening, by kind: each phase sends the ones it uses (see {@link JobPhase}). */
    private Map<JobPhase.Part, String> openingParts(State state) {
        Map<JobPhase.Part, String> parts = new java.util.EnumMap<>(JobPhase.Part.class);
        parts.put(JobPhase.Part.GOAL, "Goal: " + state.codingGoal());
        parts.put(JobPhase.Part.FOLDER, "\nTrusted folder (every path is relative to it): " + state.workFolder().toAbsolutePath());
        parts.put(JobPhase.Part.ENVIRONMENT, "\n\nEnvironment:\n" + state.codingEnvironment());
        String knowledge = KnowledgeBook.shared().forGoal(state.codingGoal(), MAX_KNOWLEDGE_CHARS);
        if (!knowledge.isBlank()) {
            parts.put(JobPhase.Part.KNOWLEDGE, "\n\nKnow-how for this kind of work (from the client: more reliable than what you remember, follow it):\n" + knowledge);
        }
        if (!state.codingProjectMap().isBlank()) {
            parts.put(JobPhase.Part.MAP, "\n\nFiles in the trusted folder (folder: file lines, ...; build output and dependencies left out):\n"
                    + state.codingProjectMap());
        }
        parts.put(JobPhase.Part.GUIDE, guide(state));
        parts.put(JobPhase.Part.NOTES, notes(state));
        return parts;
    }

    /**
     * The research step's notes (the skill variable {@code brief}) as part of the opening: what the existing code
     * looks like, with its exact names, for the plan, the edits and the repairs. "" when no research was done.
     */
    private static String notes(State state) {
        String brief = "";
        try {
            if (!state.skillVars().isBlank()) brief = JSON.readTree(state.skillVars()).path("brief").asText("").strip();
        } catch (JsonProcessingException e) {
            return "";
        }
        if (brief.isEmpty()) return "";
        return "\n\nResearch notes about the existing code, written before the plan (use these names, signatures and"
                + " call paths exactly; anything not here or in the material of your task does not exist):\n\n"
                + (brief.length() > MAX_NOTES_CHARS ? brief.substring(0, MAX_NOTES_CHARS) + "\n[... cut ...]" : brief);
    }

    private static final int MAX_NOTES_CHARS = 6_000;
    /** The most characters of know-how a job is given ({@link KnowledgeBook}). */
    static final int MAX_KNOWLEDGE_CHARS = 4_500;

    /** The languages whose design guide applies to this job (named in the goal, or the picked and planned files). */
    private Set<String> guideLanguages(State state) {
        List<String> paths = new ArrayList<>(state.codingPicks());
        state.codingPlan().ifPresent(plan -> paths.addAll(plan.changedFiles()));
        return guides.languagesFor(state.codingGoal(), paths);
    }

    /** The design guide section of a prompt, or "" when no guide applies. */
    private String guide(State state) {
        String text = guides.text(guideLanguages(state));
        return text.isBlank() ? "" : "\n\nDesign guide, for new code where the file being changed shows no style of its own. The style of the existing code always wins over it:\n\n" + text;
    }

    /** One model call without tools: the opening with the parts this phase uses, then its instructions and material. */
    private AiMessage ask(JobPhase phase, State state, String instructions, String prompt) {
        return ask(phase, state, instructions, prompt, null);
    }

    /** The same at the given reasoning effort ("low", "medium", "high"), or the model's own when null. */
    private AiMessage ask(JobPhase phase, State state, String instructions, String prompt, String effort) {
        return askResponse(phase, state, instructions, prompt, effort, null).aiMessage();
    }

    /** The same, with the whole response (to see how many tokens it used) and a limit on the reply (null: the model's own). */
    private ChatResponse askResponse(JobPhase phase, State state, String instructions, String prompt, String effort, Integer maxTokens) {
        return askResponse(phase, state, instructions, prompt, effort, maxTokens, null);
    }

    /** The same, with a function the model must call (its arguments are the answer); null: an answer in text. */
    private ChatResponse askResponse(JobPhase phase, State state, String instructions, String prompt, String effort, Integer maxTokens,
                                     ToolSpecification function) {
        Map<JobPhase.Part, String> parts = openingParts(state);
        AgentLog.current().line(JobContext.describe(phase, parts));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(JobContext.compose(phase, parts)));
        if (phase.history()) messages.addAll(JobHistory.of(state.messages())); // what the user and the assistant said before the job
        messages.add(UserMessage.from(prompt.isBlank() ? instructions : instructions + "\n\n" + prompt));
        ChatRequest.Builder request = ChatRequest.builder()
                .messages(messages)
                .parameters(effort == null ? null : Effort.parameters(effort));
        if (maxTokens != null) request.maxOutputTokens(maxTokens);
        if (function != null) request.toolSpecifications(function).toolChoice(ToolChoice.REQUIRED);
        // Streams already: the model is a StreamingChatModelAdapter (MODEL_STREAMING=true), which shows the tokens as
        // they come and hands back the finished reply here
        return model.chat(request.build());
    }

    private static String textOf(AiMessage message) {
        return Objects.requireNonNullElse(message.text(), ""); // null when the reply only has tool calls
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
}
