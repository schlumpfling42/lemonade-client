package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.rka.ai.agent.skills.CodingKinds;
import net.rka.ai.agent.skills.GenericKinds;
import net.rka.ai.agent.skills.NodeKind;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillDiagram;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillGraphBuilder;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.agent.skills.SkillQuestion;
import net.rka.ai.agent.skills.SkillValidator;
import net.rka.ai.agent.skills.Templates;
import net.rka.ai.config.Env;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tracing.AgentLog;
import net.rka.ai.tracing.LoggingChatModelListener;
import net.rka.ai.tracing.Tracing;
import net.rka.ai.tracing.TracingChatModelListener;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Lemonade-backed LangGraph agent, shared by the CLI and the JavaFX UI.
 * Each {@link #run} call is one conversation turn and one LangSmith trace.
 *
 * <h2>How it fits together</h2>
 * <pre>
 *   ChatWindow / cli.Main
 *        │ run(history, threadId, settings)            (worker thread, may take minutes)
 *        ▼
 *   Agent ── builds: OpenAiChatModel (talks to Lemonade), ToolRegistry, compiled AgentGraph (again on reloadSkills)
 *        │ graph.stream(...)  ──►  agent → tools/trusted/skill-* (skill-coding too) → agent → ... → END
 *        │       ├─ pauses before "approval" ────────────► Approver.review ─────► updateState ─► resume
 *        │       ├─ pauses before "skill-coding-planApproval" ► Approver.reviewPlan ─► updateState ─► resume
 *        │       ├─ pauses before "skill-&lt;name&gt;-toolApproval" ─► Approver.review ─► updateState ─► resume
 *        │       ├─ pauses before a skill's askUser node ─► Approver.askUser ─► updateState ─► resume
 *        │       └─ pauses before "skillBuilder-review" ──► Approver.reviewSkill ─► updateState ─► resume
 *        │                                                  (Save: reloadSkills after the turn)
 *        ▼
 *   returns the new messages of this turn (tool calls, results, final answer)
 * </pre>
 * The caller keeps the conversation (the {@code history} list) and appends what {@link #run} returns;
 * the agent itself is stateless between turns. That's why one {@code Agent} serves all conversations.
 * <p>
 * Lemonade exposes an <em>OpenAI-compatible</em> API, so LangChain4j's {@link OpenAiChatModel} works
 * with it just by pointing {@code baseUrl} at the Lemonade server.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Cancelling a running turn (a Stop button): today a turn runs until the model answers.</li>
 *   <li>Streaming the answer token by token ({@code OpenAiStreamingChatModel}).</li>
 *   <li>Switching models at run time (Lemonade serves several; {@code /api/v1/models} lists them).</li>
 *   <li>Retries with back-off when Lemonade is busy loading a model or times out.</li>
 *   <li>Token usage and cost per turn shown in the UI (the trace spans already record token counts).</li>
 *   <li>Long-term memory across conversations (e.g. a vector store of facts the user told the agent).</li>
 * </ul>
 */
public final class Agent implements AutoCloseable {

    /**
     * Instructions sent as the first (system) message of every run. Each sentence fixes a
     * behavior seen with the local model: summarizing instead of listing, claiming actions it
     * never took.
     */
    public static final String SYSTEM_PROMPT = "You are a helpful assistant. The user's latest message is what you do now: earlier requests in the conversation are history, not tasks to pick up again, unless the latest message points back at them. Use tools when useful. "
            + "When the user asks you to list, show, read or find something, put the actual results in "
            + "your answer (for example every file and folder name, as a Markdown list), not just a "
            + "summary of them; the user can't see tool output unless you repeat it. Only shorten very "
            + "long results, and say how many items you left out. "
            + "Never say you did something (saved, wrote, ran, deleted) unless a tool call in this "
            + "conversation actually did it and succeeded; to save a file, call writeFile. "
            + "When the user asks you to build, add, fix, change or refactor something in a software project "
            + "(a feature, a button, a bug, a new project), call startCodingJob with their request as the goal "
            + "before reading or editing code yourself: the job reads the code, plans it with the user, builds and tests it. "
            + "To change an existing file, call outline for its parts and md5, readSection for the part, then "
            + "editSection with the part's id and its complete new text (or mode add with the class's id for a new method, field or constructor); never "
            + "write a whole existing file again.";

    private static final System.Logger LOG = System.getLogger(Agent.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String modelName;
    private final Tracing tracing;
    private final Approver approver;
    private final LiveChannel live;
    // What the graph is built from, kept so reloadSkills can build it again
    private final ChatModel model;
    private final ToolRegistry tools; // without skill tools; each Compiled adds the loaded skills' ones
    private final SkillLoader skillLoader;
    // The compiled graph with the skills it was built for; swapped as a whole by reloadSkills
    private volatile Compiled compiled;
    // Whether every turn starts by asking the model if the message is coding work (CodingTriage); see triage(boolean)
    private volatile boolean triage;

    /**
     * What a turn runs with, built together so they always match: the compiled graph (with a
     * {@code skill-<name>} subgraph per loaded skill), the tool registry the model is offered (the
     * tools plus the skills' tools), and the skills with the lookups the pause loop, progress and the
     * message feed use. {@link #run} takes one at the start of a turn and uses it to the end, so a
     * reload during a turn only affects the next one.
     *
     * @param nodes     every skill node by its id in the agent graph ({@link SkillLoader.Loaded#nodeIndex})
     * @param pauses    the skill node ids the graph pauses before ({@link AgentGraph#skillPauses})
     * @param skillWork labels of skill nodes whose streamed text shows as progress, by tracing name
     */
    private record Compiled(CompiledGraph<AgentGraph.State> graph, ToolRegistry tools, SkillLoader.Loaded skills,
                            Map<String, SkillLoader.NodeRef> nodes, Set<String> pauses, Map<String, String> skillWork) {
    }

    /**
     * @param model       the chat model (Lemonade in production, a scripted fake in tests)
     * @param environment the environment summary for coding jobs (cached next to the global settings)
     */
    Agent(String baseUrl, String modelName, ChatModel model, Tracing tracing, ToolRegistry tools, Approver approver,
          EnvironmentInfo environment) throws GraphStateException {
        this(baseUrl, modelName, model, tracing, tools, approver, environment, new LiveChannel(), null);
    }

    /**
     * @param live     the channel the streaming model and running commands send real-time output to
     * @param plansDir where coding jobs save their plans as Markdown for review; null for none (tests)
     */
    Agent(String baseUrl, String modelName, ChatModel model, Tracing tracing, ToolRegistry tools, Approver approver,
          EnvironmentInfo environment, LiveChannel live, Path plansDir) throws GraphStateException {
        this(baseUrl, modelName, model, tracing, tools, approver, environment, live, plansDir, DesignGuides.NONE);
    }

    /** @param guides the design guides coding jobs follow and get reviewed against ({@link DesignGuides#NONE}: none) */
    Agent(String baseUrl, String modelName, ChatModel model, Tracing tracing, ToolRegistry tools, Approver approver,
          EnvironmentInfo environment, LiveChannel live, Path plansDir, DesignGuides guides) throws GraphStateException {
        // No skill folder: only the built-in skills, i.e. the coding job
        this(baseUrl, modelName, model, tracing, tools, approver,
                new SkillLoader(null, BUILT_IN_SKILLS,
                        new SkillValidator(kinds(codingSetup(environment, plansDir, guides)), tools)), live);
    }

    /** The skills that ship with the app (resources {@code net/rka/ai/agent/skills/<name>.json}). */
    static final List<String> BUILT_IN_SKILLS = List.of(CodingGraph.SKILL);

    /**
     * Every node kind skill files can use: the generic ones ({@link GenericKinds}) and the coding job's
     * ({@link CodingKinds}), which run with {@code setup}.
     */
    static Map<String, NodeKind> kinds(CodingGraph.Setup setup) {
        Map<String, NodeKind> kinds = new LinkedHashMap<>(GenericKinds.all());
        kinds.putAll(CodingKinds.all(setup));
        return kinds;
    }

    /**
     * What coding jobs run with: the environment summary, the command timeout (builds and installs can
     * take minutes: {@code CODING_COMMAND_TIMEOUT_SECONDS}, default 600), the plan folder and the guides.
     */
    static CodingGraph.Setup codingSetup(EnvironmentInfo environment, Path plansDir, DesignGuides guides) {
        return new CodingGraph.Setup(environment, Integer.parseInt(Env.get("CODING_COMMAND_TIMEOUT_SECONDS", "600")),
                plansDir, guides);
    }

    /**
     * @param skills loads the skills, now and on {@link #reloadSkills}; each valid one becomes a subgraph
     *               of the agent graph and a tool the model can call. Its validator must check against
     *               {@code tools}, with the kinds the skills run with ({@link #kinds}). Coding jobs need
     *               the built-in skill {@code coding} among its built-ins. File tools refuse paths in its folder.
     */
    Agent(String baseUrl, String modelName, ChatModel model, Tracing tracing, ToolRegistry tools, Approver approver,
          SkillLoader skills, LiveChannel live) throws GraphStateException {
        this.live = live;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.tracing = tracing;
        this.approver = approver;
        this.model = model;
        this.tools = tools;
        this.skillLoader = skills;
        this.compiled = compile(skills.load()); // a bad skill file is listed in the status, never thrown
    }

    /**
     * Builds and compiles the agent graph for the loaded skills. Compiling validates and freezes the
     * graph; it's then reused for every run until the skills are reloaded.
     * <p>
     * A skill file never stops the app: a skill whose subgraph doesn't build is left out
     * ({@link AgentGraph#buildable}); if the graph still doesn't compile, it is compiled with the built-in
     * skills only, and if even that fails, with no skills. Each step lists why under the file names of the
     * skills it left out, so the status (the settings' skills list) shows it. Only a graph that fails
     * without any skill throws.
     */
    private Compiled compile(SkillLoader.Loaded loaded) throws GraphStateException {
        SkillLoader.Loaded skills = buildable(loaded);
        try {
            return compileWith(skills);
        } catch (GraphStateException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The agent graph could not be built with the skills; trying the built-in ones only", e);
            SkillLoader.Loaded builtIns = buildable(skillLoader.builtInsOnly());
            SkillLoader.Loaded fallback = skills.fallback(builtIns.skills(), builtIns.errors(),
                    "The agent graph could not be built with the skill files (" + e.getMessage()
                            + "); only the built-in skills are used.");
            try {
                return compileWith(fallback);
            } catch (GraphStateException | RuntimeException again) {
                LOG.log(System.Logger.Level.ERROR, "The agent graph could not be built with the built-in skills; using none", again);
                return compileWith(fallback.fallback(List.of(), Map.of(),
                        "The agent graph could not be built with any skill (" + again.getMessage() + "); no skills are used."));
            }
        }
    }

    /**
     * The skills whose subgraphs build ({@link AgentGraph#buildable}), with the tools and kinds they run with;
     * a user file replacing a built-in that doesn't build gives way to the built-in.
     */
    private SkillLoader.Loaded buildable(SkillLoader.Loaded skills) {
        // Building runs nothing, so any runner whose registry has the skills' tools will do
        ToolCallRunner runner = new ToolCallRunner(tracing.tracer(), tools.withSkills(skills.skills()), live, deniedFolders());
        return AgentGraph.buildable(skills, skillLoader.builtInsOnly().skills(), model, runner,
                new NodeTracing(tracing.tracer(), live), skillLoader.validator().kinds());
    }

    /** Skill files change only through the skill builder: file tools refuse the loader's folder too. */
    private List<Path> deniedFolders() {
        return skillLoader.folder() == null ? List.of() : List.of(skillLoader.folder());
    }

    /** {@link #compile} for skills that all build; throws when the graph doesn't compile with them. */
    private Compiled compileWith(SkillLoader.Loaded skills) throws GraphStateException {
        ToolRegistry offered = tools.withSkills(skills.skills()); // the skills' tools, never executed
        // The two numbers are the summary memory budgets in estimated tokens (see SummaryMemory); what coding
        // jobs run with (environment, command timeout, plans, guides) comes with the coding kinds
        Map<String, NodeKind> kinds = skillLoader.validator().kinds();
        CompiledGraph<AgentGraph.State> graph = new AgentGraph(model,
                new ToolCallRunner(tracing.tracer(), offered, live, deniedFolders()),
                new NodeTracing(tracing.tracer(), live),
                memorySummaryTokens(), memoryRecentTokens(), memoryIncrementalThreshold(),
                skills, skillLoader, triage).compile();
        return new Compiled(graph, offered, skills, skills.nodeIndex(), Set.copyOf(AgentGraph.skillPauses(skills, kinds)),
                skillWork(skills));
    }

    /**
     * The skill nodes whose streamed text shows as progress, by tracing name ({@link LiveChannel#skillWork}):
     * {@code model} nodes with {@code "thinking": "progress"} ("&lt;skill&gt;: &lt;node&gt;"), and every node whose
     * kind hides its text ({@link LiveChannel#hiddenWorkOfKind}: a coding job's plan JSON), found by kind
     * through the node index, so a skill file may call those nodes anything.
     */
    static Map<String, String> skillWork(SkillLoader.Loaded skills) {
        Map<String, String> skillWork = new HashMap<>();
        for (SkillLoader.NodeRef ref : skills.nodeIndex().values()) {
            String label = LiveChannel.hiddenWorkOfKind(ref.kind());
            if (label != null) skillWork.put(ref.tracingName(), label);
        }
        for (SkillFile skill : skills.skills()) {
            for (SkillFile.NodeSpec node : skill.nodes()) {
                if ("progress".equals(node.fields().get("thinking"))) {
                    skillWork.put(SkillGraphBuilder.tracingName(skill.name(), node.id()), skill.name() + ": " + node.id());
                }
            }
        }
        return Map.copyOf(skillWork);
    }

    /**
     * Loads the skills again (the folder may have new, changed or removed files) and rebuilds the agent
     * graph and the tool list with them. Call it between turns: a turn in progress keeps the graph it
     * started with. A skill that can't be added is left out and listed ({@link #compile}); only if the
     * graph fails without any skill, the skills that were loaded before stay in use.
     *
     * @return the new status: the skills in use and the files with errors
     */
    public SkillLoader.Loaded reloadSkills() {
        SkillLoader.Loaded loaded = skillLoader.load();
        try {
            compiled = compile(loaded);
        } catch (GraphStateException e) {
            throw new IllegalStateException("The skills could not be added to the agent graph: " + e.getMessage(), e);
        }
        return compiled.skills();
    }

    /** The skills in use: the loaded ones, and the files that couldn't be loaded with their errors. */
    public SkillLoader.Loaded skillStatus() {
        return compiled.skills();
    }

    /** Where the skill with this name comes from (built-in or the user's file), for the skills list. */
    public SkillLoader.Origin skillOrigin(String name) {
        return skillLoader.origin(compiled.skills(), name);
    }

    /** Whether a skill of this name ships with the app (a user file of that name replaces it). */
    public boolean isBuiltInSkill(String name) {
        return skillLoader.builtInNames().contains(name);
    }

    /** The tool that starts this skill (its own, a replaced built-in's pinned one, or {@code skill_<name>}). */
    /**
     * How long one model reply may take ({@code MODEL_TIMEOUT_MINUTES}, default 15). The HTTP client counts
     * the whole reply, including streaming, so writing a long file on a local model needs minutes.
     */
    static Duration modelTimeout() {
        return Duration.ofMinutes(Long.parseLong(Env.get("MODEL_TIMEOUT_MINUTES", "15")));
    }

    /**
     * When summary memory folds older messages into the summary, in estimated tokens
     * ({@code MEMORY_SUMMARY_TOKENS}): by default a quarter of the model's context ({@code MODEL_CONTEXT_TOKENS}),
     * at least 6000. A summary changes the start of every later request, so the model server can't reuse its
     * cache for that turn: summarizing rarely, in big chunks, is much faster than summarizing every turn.
     */
    static int memorySummaryTokens() {
        return Integer.parseInt(Env.get("MEMORY_SUMMARY_TOKENS",
                String.valueOf(Math.max(6000, contextTokens() / 4))));
    }

    /** How much of the newest conversation stays word for word when summarizing ({@code MEMORY_RECENT_TOKENS}): by default a sixteenth of the context, at least 2000. */
    static int memoryRecentTokens() {
        return Integer.parseInt(Env.get("MEMORY_RECENT_TOKENS",
                String.valueOf(Math.max(2000, contextTokens() / 16))));
    }

    /** Messages of unsummarized text that trigger incremental summary updates instead of a full summary ({@code MEMORY_INCREMENTAL_THRESHOLD}). Default 20. */
    static int memoryIncrementalThreshold() {
        return Integer.parseInt(Env.get("MEMORY_INCREMENTAL_THRESHOLD", "20"));
    }

    /**
     * The longest reply the model may write, in tokens ({@code MODEL_MAX_OUTPUT_TOKENS}, default 8192). At about
     * 30 tokens a second that is roughly 4–5 minutes: enough for any edit or a new file of several hundred
     * lines, while a model trying to write a whole 2,000-line file in one call stops early (the tool call then
     * fails and the model can write the file in parts with append, or edit instead).
     */
    static int maxOutputTokens() {
        return Integer.parseInt(Env.get("MODEL_MAX_OUTPUT_TOKENS", "8192"));
    }

    /**
     * How hard a reasoning model thinks ({@code MODEL_REASONING_EFFORT}: low, medium or high; default low; empty sends
     * nothing), sent as {@code chat_template_kwargs.reasoning_effort}. Low keeps the thinking short, which is most of the
     * time and tokens of a local model.
     */
    static java.util.Map<String, Object> reasoningParameters() {
        String effort = Env.get("MODEL_REASONING_EFFORT", "low").strip();
        return Sampling.with(effort.isEmpty() ? java.util.Map.of() : java.util.Map.of("chat_template_kwargs", java.util.Map.of("reasoning_effort", effort)));
    }

    /** The model server's context window ({@code MODEL_CONTEXT_TOKENS}, default 16384). */
    /** The model server's context size in tokens: what the conversation's health is measured against. */
    public static int contextWindowTokens() {
        return contextTokens();
    }

    static int contextTokens() {
        return Integer.parseInt(Env.get("MODEL_CONTEXT_TOKENS", "16384"));
    }

    public String skillTool(SkillFile skill) {
        return skillLoader.toolOf(skill);
    }

    /**
     * @param approver asked before tools marked {@code @RequiresApproval} run (terminal, SSH), and about coding job plans
     */
    public static Agent fromEnv(Approver approver) throws GraphStateException {
        return fromEnv(approver, null);
    }

    /**
     * @param gate when not null, every call to the model is held for a person ({@link ModelGate}): they see the request before it is sent
     *             and the answer before the application uses it, and can change both. The gate is in front of the model client and
     *             below the fitting of requests to the context window, so what they see is what the server receives.
     */
    public static Agent fromEnv(Approver approver, ModelGate gate) throws GraphStateException {
        String baseUrl = Env.get("LEMONADE_BASE_URL", "http://127.0.0.1:13305/api/v1");
        String modelName = Env.get("LEMONADE_MODEL", "user.gpt-oss-20b");
        Tracing tracing = Tracing.fromEnv();
        LiveChannel live = new LiveChannel();
        String apiKey = Env.get("LEMONADE_API_KEY", "lemonade"); // Lemonade ignores it unless configured
        // The listener turns every model call into an "llm" span with prompts, answer and tokens
        // A readable log of model requests and replies and of tool calls (AGENT_LOG, see AgentLog)
        AgentLog.configure(AgentLog.fromEnv());
        List<ChatModelListener> listeners = List.of(new TracingChatModelListener(tracing.tracer(), "lemonade"),
                new LoggingChatModelListener(live::node));
        ChatModel model;
        if (Boolean.parseBoolean(Env.get("MODEL_STREAMING", "true"))) {
            // Streaming: tokens (thinking, text, tool call arguments) reach the window as the server
            // generates them; the graph still gets each complete reply (see StreamingChatModelAdapter)
            model = new StreamingChatModelAdapter(OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(Sampling.temperature()) // a low value makes the model likelier to repeat itself and get stuck
                    .topP(Sampling.topP())
                    .returnThinking(true)           // a reasoning model's thinking arrives as it's generated
                    .timeout(modelTimeout())         // local models can be slow: long prompts, long files to write
                    .maxTokens(maxOutputTokens())    // a runaway reply (a whole big file) stops instead of running for minutes
                    .customParameters(reasoningParameters())
                    .listeners(listeners)
                    .build(), live);
        } else {
            // MODEL_STREAMING=false: one blocking request per reply, for servers that can't stream tool calls
            model = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(Sampling.temperature())
                    .topP(Sampling.topP())
                    .returnThinking(true)           // a reasoning model's thinking comes back as AiMessage.thinking()
                    .timeout(modelTimeout())
                    .maxTokens(maxOutputTokens())
                    .customParameters(reasoningParameters())
                    .listeners(listeners)
                    .build();
        }
        if (gate != null) model = new GatedChatModel(model, gate);
        // Every request is shortened to fit the server's context window (llama-server's n_ctx), so a
        // turn that reads big files doesn't fail with context_length_exceeded (see ContextFittingChatModel)
        model = new ContextFittingChatModel(model,
                contextTokens());
        // Design guides: the built-in ones plus yours in ~/chat/guides (a starter java.md is created once)
        DesignGuides guides = new DesignGuides(Settings.globalFile().resolveSibling("guides"));
        guides.createStarterFiles();
        ToolRegistry tools = ToolRegistry.defaultTools();
        List<String> unnamed = tools.toolsWithUnnamedParameters();
        if (!unnamed.isEmpty()) {
            String warning = unnamed.size() + " tools offer arg0, arg1 ... to the model instead of named parameters, so it has to guess what each is:"
                    + " the classes were compiled without -parameters. Start the app through Gradle (./gradlew run) or the \"Launcher\""
                    + " launch configuration, which build with it.";
            AgentLog.current().line("⚠ " + warning);
            System.err.println("[tools] " + warning);
        }
        // Skills: the built-in coding job, plus the user's files in ~/chat/skills (a coding.json there replaces it)
        CodingGraph.Setup coding = codingSetup(EnvironmentInfo.fromSettings(),
                Settings.globalFile().resolveSibling("plans"), // ~/chat/plans, next to settings.json
                guides);
        SkillLoader skills = new SkillLoader(Settings.skillsFolder(), BUILT_IN_SKILLS, new SkillValidator(kinds(coding), tools));
        Agent agent = new Agent(baseUrl, modelName, model, tracing, tools, approver, skills, live);
        agent.triage(!"false".equalsIgnoreCase(Env.get("CODING_TRIAGE", "true")));
        return agent;
    }

    /**
     * Switches the start-of-turn question "is this coding work?" ({@link CodingTriage}) on or off; it is on in the
     * app ({@code CODING_TRIAGE}, default true) and off by default here, so a model that is scripted with the answers
     * of a turn isn't asked an extra question. The graph is built again to apply it.
     */
    void triage(boolean on) {
        this.triage = on;
        reloadSkills();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String modelName() {
        return modelName;
    }

    /** The model the agent talks to (for the routing check of the evaluation framework). */
    public ChatModel model() {
        return model;
    }

    /** Where traces go, e.g. "LangSmith project 'x' + files in /path/traces", or "off". */
    public String tracingDescription() {
        return tracing.description();
    }

    /** Starts a new conversation history containing the system prompt. */
    public static List<ChatMessage> newConversation() {
        return new ArrayList<>(List.of(SystemMessage.from(systemPrompt(null, List.of()))));
    }

    /**
     * The system prompt for a run: instructions, today's date, the trusted folder (if known) and the
     * conversation's long-term facts. Facts live here, not in the messages, so they're sent on every
     * turn and never summarized away.
     */
    static String systemPrompt(Path workFolder, List<FactBook.Fact> facts) {
        // Models don't know the date; without it they guess a date from their training data
        String prompt = SYSTEM_PROMPT + " Today is " + LocalDate.now() + " ("
                + LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ").";
        if (workFolder != null) {
            prompt += " The conversation's trusted working folder is " + workFolder
                    + "; file tools only work inside it and take paths relative to it.";
        }
        prompt += " Use the remember tool to keep durable facts for the rest of this conversation "
                + "(things the user asks you to remember, preferences, names, decisions).";
        if (!facts.isEmpty()) {
            StringBuilder list = new StringBuilder("\n\nFacts remembered in this conversation:");
            for (FactBook.Fact fact : facts) list.append("\n- [").append(fact.id()).append("] ").append(fact.text());
            prompt += list;
        }
        return prompt;
    }

    /**
     * The history with its leading system message replaced by this run's prompt, so saved
     * conversations pick up prompt changes, today's date, the current trusted folder and facts.
     */
    private static List<ChatMessage> withCurrentSystemPrompt(List<ChatMessage> history, Path workFolder,
                                                             List<FactBook.Fact> facts) {
        List<ChatMessage> messages = new ArrayList<>(history); // a copy: the caller's list stays untouched
        SystemMessage prompt = SystemMessage.from(systemPrompt(workFolder, facts));
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            messages.set(0, prompt);
        } else {
            messages.add(0, prompt);
        }
        return messages;
    }

    /**
     * Runs the graph over the whole conversation and returns only the messages
     * produced in this turn (tool calls, tool results and the final answer).
     * When the graph pauses before the approval subgraph, before a coding job's plan approval, or before
     * a skill's tool approval or question, or before the skill builder's review, asks the {@link Approver},
     * stores the answer in the saved state and resumes, as often as needed. A skill saved by the builder is
     * loaded after the turn ({@link #reloadSkills}).
     *
     * @param threadId groups the turns of one conversation into a LangSmith thread
     */
    public List<ChatMessage> run(List<ChatMessage> history, String threadId) {
        Settings global = Settings.loadGlobal();
        return run(history, threadId, Settings.effective(global, Settings.newConversation(global)),
                ConversationSummary.EMPTY, List.of()).messages();
    }

    /**
     * What one turn produced: the new messages, plus the conversation's memory after the turn.
     *
     * @param messages the messages produced in this turn (tool calls, tool results, final answer)
     * @param summary  the summary memory, possibly extended at the start of the turn
     * @param facts    the long-term facts, possibly changed by remember / forget
     */
    public record Turn(List<ChatMessage> messages, ConversationSummary summary, List<FactBook.Fact> facts) {
    }

    /**
     * A turn that failed after it had already done something (run tools, written files): the cause, plus
     * {@link #partial()}, what to keep in the conversation. Its message is the cause's message.
     */
    public static final class TurnFailedException extends RuntimeException {
        private final transient Turn partial;

        TurnFailedException(Exception error, Turn partial) {
            this(rootCause(error), partial);
        }

        private TurnFailedException(Throwable cause, Turn partial) {
            super(cause.getMessage(), cause);
            this.partial = partial;
        }

        /**
         * The error itself, without the wrappers the graph runner adds around a node's exception
         * ({@code GraphRunnerException}, {@code ExecutionException}, {@code CompletionException}), so the
         * window says "Connection reset" rather than three class names.
         */
        /**
         * The error as one line for the window: a model reply that took longer than
         * {@code MODEL_TIMEOUT_MINUTES} (the HTTP client reports it only as "closed") says so and how to fix
         * it; anything else is "Type: message" of {@link #rootCause}.
         */
        public static String describe(Throwable error) {
            for (Throwable c = error; c != null; c = c.getCause() == c ? null : c.getCause()) {
                if (c instanceof java.net.http.HttpTimeoutException) {
                    return "The model server didn't finish its reply within " + modelTimeout().toMinutes()
                            + " minutes (MODEL_TIMEOUT_MINUTES in .env). Writing a long file on a local model can"
                            + " take that long: raise the timeout, or ask for smaller changes (editSection).";
                }
            }
            Throwable cause = rootCause(error);
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }

        public static Throwable rootCause(Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause
                    && (cause instanceof org.bsc.langgraph4j.GraphRunnerException
                    || cause instanceof java.util.concurrent.ExecutionException
                    || cause instanceof java.util.concurrent.CompletionException)) {
                cause = cause.getCause();
            }
            return cause;
        }

        /** The messages the turn added before it failed (open calls answered with an error), and its memory. */
        public Turn partial() {
            return partial;
        }
    }

    /**
     * Like {@link #run(List, String)}, with the conversation's settings and memory:
     * <ul>
     *   <li>{@code settings} ({@link Settings#effective}): file tools work only inside its trusted folder,
     *       terminal commands start there, and its trusted commands run without asking;</li>
     *   <li>{@code summary}: older messages already summarized (the model sees the summary instead);</li>
     *   <li>{@code facts}: long-term facts, listed in the system prompt and editable by the memory tools.</li>
     * </ul>
     * The caller stores the returned summary and facts with the conversation.
     */
    /** Stops the turn in progress for good: its nodes are interrupted and no further node starts (the Stop button). */
    public void stop() {
        live.cancel();
    }

    public Turn run(List<ChatMessage> history, String threadId, Settings settings,
                    ConversationSummary summary, List<FactBook.Fact> facts) {
        return run(history, threadId, settings, summary, facts, Progress.NONE);
    }

    /**
     * Like the overload without a listener; while a coding job runs, {@code listener} gets a
     * {@link Progress} after each of the job's nodes (on the thread that calls this method).
     */
    public Turn run(List<ChatMessage> history, String threadId, Settings settings,
                    ConversationSummary summary, List<FactBook.Fact> facts, Progress.ProgressListener listener) {
        Path workFolder = settings.workFolderOrDefault();
        // The root span of this turn's trace; every node, model call and tool call nests under it.
        // The "langsmith.metadata.*" attributes show up as metadata in LangSmith; thread_id groups turns.
        Span root = tracing.tracer().spanBuilder("LangGraph Agent")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("langsmith.metadata.thread_id", threadId)
                .setAttribute("langsmith.metadata.work_folder", workFolder.toString())
                .setAttribute("langsmith.metadata.trusted_commands", String.join(", ", settings.trustedCommands()))
                .setAttribute("gen_ai.prompt", String.valueOf(history.get(history.size() - 1)))
                .startSpan();
        // The whole turn uses the graph and skills as they are now, even if the skills are reloaded meanwhile
        Compiled current = compiled;
        // Real-time output (model tokens, running command output) goes to this turn's listener
        live.begin();
        live.set(listener);
        live.skillWork(current.skillWork());
        long turnStart = System.nanoTime();
        AgentLog.current().line("■ turn: " + AgentLog.oneLine(history.getLast() instanceof UserMessage u && u.hasSingleText()
                ? u.singleText() : String.valueOf(history.getLast()), 200));
        MessageFeed feed = null; // outside the try: on a failure it still knows what the turn did
        // Set when the user saved a skill draft: the skills are reloaded once the turn is over, never during it
        boolean reloadAfterTurn = false;
        try (Scope ignored = root.makeCurrent()) {
            // The checkpointer keeps one thread per turn: the full history is passed in each time.
            // (A LangGraph "thread" is a checkpoint key, unrelated to Java threads.)
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId + "/" + UUID.randomUUID())
                    .putMetadata(NodeTracing.TRACE_CONTEXT, Context.current()) // lets NodeTracing find the root span
                    .build();
            List<ChatMessage> input = withCurrentSystemPrompt(history, workFolder, facts);
            // Messages are passed to the listener as soon as a node adds them (tool calls, results, answers)
            feed = new MessageFeed(input.size(), listener, live, current.nodes());
            // Start the graph with the initial state. It runs until END, or until it pauses (tool approval, plan approval).
            AgentGraph.State result = streamToPause(
                    GraphInput.args(Map.of(
                            AgentGraph.State.MESSAGES, List.copyOf(input),
                            AgentGraph.State.WORK_FOLDER, workFolder.toAbsolutePath().toString(),
                            AgentGraph.State.TRUSTED_COMMANDS, String.join("\n", settings.trustedCommands()),
                            AgentGraph.State.SUMMARY, summary.text(),
                            AgentGraph.State.SUMMARY_COVERED, summary.covered(),
                            AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(facts))),
                    config, listener, feed, current);

            // Each stream ends at END or at a pause (an interrupt); the latest checkpoint says which:
            // its next node is the one the graph paused before ("approval-review" for the approval
            // subgraph, "skill-<name>-<node>" for a skill's tool approval, question or a coding job's
            // plan, e.g. "skill-coding-planApproval"). A finished run has no checkpoints left (the graph
            // is compiled with releaseThread), so "no snapshot" means done.
            RunnableConfig thread = config;
            Pause previous = null;
            while (true) {
                String next = current.graph().lastStateOf(thread).map(StateSnapshot::nextNodeId).orElse(StateGraph.END);
                boolean skillPause = current.pauses().contains(next);
                boolean skillReview = next.equals(AgentGraph.SKILL_REVIEW_PAUSE);
                boolean askPause = next.equals(AgentGraph.ASK_PAUSE);
                if (!next.equals(AgentGraph.APPROVAL_PAUSE) && !skillPause && !skillReview && !askPause) break; // done
                // Safety net: if resuming paused again at the same place with nothing changed, stop
                // instead of asking forever. A revised plan adds job messages, a skill's next round or
                // question adds skill messages or steps, so those count as progress.
                Pause pause = new Pause(next, result.unansweredToolCalls(), result.skillUnansweredToolCalls(),
                        codingProgress(result), result.allSkillMessages().size(), result.skillSteps());
                if (pause.equals(previous)) {
                    throw new IllegalStateException("Resuming before " + next + " made no progress on "
                            + (skillPause ? pause.skillOpenCalls() : pause.openCalls()));
                }
                previous = pause;
                // The user's answer, as state updates: tool call decisions, the plan decision and comment,
                // the answer to a skill's question, or the decision about a skill draft
                Map<String, Object> answer;
                if (next.equals(AgentGraph.APPROVAL_PAUSE)) {
                    answer = Map.of(AgentGraph.State.APPROVALS, askApproval(result.unansweredToolCalls(), result, current.tools()));
                } else if (askPause) {
                    answer = askAnalysisQuestion(result);
                } else if (skillReview) {
                    answer = askSkillReview(result, current.tools());
                    // Save writes the file once the graph resumes; the new skill joins after this turn
                    if (SkillBuilderGraph.SAVE.equals(answer.get(AgentGraph.State.SKILL_DECISION))) reloadAfterTurn = true;
                } else {
                    // A skill's pause: looked up, not split ("skill-a-b-c" could be skill "a-b", node "c" or skill "a", node "b-c")
                    SkillLoader.NodeRef node = current.nodes().get(next);
                    if (SkillGraphBuilder.TOOL_APPROVAL.equals(node.node())) {
                        // The same approval as the main graph's, for the calls the skill's tool step left open;
                        // the skill's toolApproval node reads them from the same key
                        answer = Map.of(AgentGraph.State.APPROVALS, askApproval(result.skillUnansweredToolCalls(), result, current.tools()));
                    } else if (CodingKinds.PLAN_APPROVAL.equals(node.kind())) {
                        // A coding job's plan ("skill-coding-planApproval"), whatever the node is called
                        answer = askPlanApproval(result);
                    } else {
                        answer = askSkillQuestion(node, result, current.skills());
                    }
                }
                // Update via the thread (no checkpoint id) so a second pause in this turn
                // writes to the latest checkpoint, not the one the previous resume started from
                RunnableConfig updated = current.graph().updateState(thread, answer);
                config = RunnableConfig.builder(updated)
                        .putMetadata(NodeTracing.TRACE_CONTEXT, Context.current())
                        .build();
                // Resume from the checkpoint: the paused node now runs, then the graph goes on (agent → ...)
                result = streamToPause(GraphInput.resume(), config, listener, feed, current);
            }

            // Everything after the input is new in this turn; that's what the caller appends to its history
            List<ChatMessage> all = result.messages();
            List<ChatMessage> produced = new ArrayList<>(all.subList(input.size(), all.size()));
            // A turn that was stopped can end the graph with a call still open (a coding job that was running): answer it, so the saved
            // conversation stays valid and the next request is not refused for a call without a result
            for (ToolExecutionRequest open : result.unansweredToolCalls()) {
                produced.add(ToolExecutionResultMessage.builder().id(open.id()).toolName(open.name())
                        .text("Error: the turn was stopped before this call finished, so it may have run only partly.").isError(true).build());
            }
            produced = List.copyOf(produced);
            root.setAttribute("gen_ai.completion", String.valueOf(result.lastMessage()));
            // Hand back the memory as the graph left it: both are state keys
            AgentLog.current().line("■ turn done in " + AgentLog.duration(System.nanoTime() - turnStart)
                    + ", " + produced.size() + " new messages");
            return new Turn(produced, result.summary(), result.facts());
        } catch (Exception e) {
            // The window shows only the message; the full stack trace goes to the console (stderr)
            // and, through recordException, into the turn's span in traces/traces-*.jsonl
            LOG.log(System.Logger.Level.ERROR, "Turn failed", e);
            root.recordException(e);
            root.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            // Tool calls may have run (files written, commands executed) before the failure: hand them back so
            // the conversation keeps them, with any call left open answered by an error
            Turn partial = feed == null ? null : feed.partial(e);
            AgentLog.current().line("■ turn failed after " + AgentLog.duration(System.nanoTime() - turnStart) + ": "
                    + TurnFailedException.describe(e));
            if (partial != null) throw new TurnFailedException(e, partial);
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        } finally {
            writeJavaChanges();
            live.clear();
            live.skillWork(Map.of());
            root.end(); // the trace is complete; the exporter sends it in the background
            if (reloadAfterTurn) {
                // Also after a failed turn: the file may have been written before the failure
                try {
                    reloadSkills();
                } catch (RuntimeException e) {
                    // The saved file stays; the skills in use stay too until the next successful reload
                    LOG.log(System.Logger.Level.ERROR, "Reloading the skills after a saved skill failed", e);
                }
            }
        }
    }

    /**
     * Streams the graph from {@code input} (the initial state, or a resume) until it ends or pauses,
     * reports the coding job's nodes to {@code listener}, and returns the state after the last node.
     * Streaming runs the same graph as {@code invoke} would; it only hands out each node's output as it
     * finishes.
     */
    private AgentGraph.State streamToPause(GraphInput input, RunnableConfig config, Progress.ProgressListener listener,
                                          MessageFeed feed, Compiled current) {
        AgentGraph.State last = null;
        for (NodeOutput<AgentGraph.State> output : current.graph().stream(input, config)) {
            last = output.state();
            feed.update(output.node(), last);
            feed.stepDone();
            Progress progress = progressOf(output.node(), last, current.nodes(), current.skills());
            if (progress != null) {
                try {
                    listener.onProgress(progress);
                } catch (RuntimeException e) {
                    // Progress is only a display: a broken listener must not abort the run
                    LOG.log(System.Logger.Level.WARNING, "Progress listener failed", e);
                }
            }
        }
        if (last == null) throw new IllegalStateException("The graph produced no output");
        return last;
    }

    /**
     * Hands the listener every message as soon as a node added it, by remembering how many it has
     * already passed on: the main conversation ({@code messages}, starting after the turn's input) and
     * the skills' transcript ({@code skillMessages}, which only grows during a turn). A coding job keeps no
     * transcript: it answers in data (the plan, the ledger), and its phases come as progress.
     * <p>
     * From the skills only the model's replies and the tool results are passed on; their own
     * instructions to the model (goal, step texts, prompts, reminders) are prompts, not something to show.
     */
    static final class MessageFeed {
        private final Progress.ProgressListener listener;
        private final int inputSize;
        private final LiveChannel live;
        private final Map<String, SkillLoader.NodeRef> skillNodes;
        private int mainSeen;
        private int skillSeen;
        private AgentGraph.State last; // the state after the latest node

        MessageFeed(int inputSize, Progress.ProgressListener listener) {
            this(inputSize, listener, new LiveChannel(), Map.of());
        }

        /**
         * @param live       decides which nodes' text is hidden ({@link LiveChannel#work}), as for streaming
         * @param skillNodes the skills' nodes by their id in the agent graph, to name them like their tracing spans
         */
        MessageFeed(int inputSize, Progress.ProgressListener listener, LiveChannel live,
                    Map<String, SkillLoader.NodeRef> skillNodes) {
            this.inputSize = inputSize;
            this.mainSeen = inputSize;
            this.listener = listener;
            this.live = live;
            this.skillNodes = skillNodes;
        }

        /**
         * What the turn did before it failed, as a turn to keep: the messages nodes added after the input
         * (tool calls that ran, their results, a coding job's report), with every call that is still open
         * answered by an error result, so the conversation stays valid and the model sees what happened.
         * Null when the turn added nothing.
         */
        Turn partial(Exception error) {
            if (last == null || last.messages().size() <= inputSize) return null;
            List<ChatMessage> produced = new ArrayList<>(last.messages().subList(inputSize, last.messages().size()));
            String why = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            for (ToolExecutionRequest call : last.unansweredToolCalls()) {
                produced.add(ToolExecutionResultMessage.builder()
                        .id(call.id())
                        .toolName(call.name())
                        .text("Error: the turn failed before this call finished, so it may have run only partly: " + why)
                        .isError(true)
                        .build());
            }
            return new Turn(List.copyOf(produced), last.summary(), last.facts());
        }

        /**
         * @param node the node that just finished (its id in the main graph, e.g. "skill-coding-plan"); the
         *             same rule as for streaming ({@link LiveChannel#hiddenWork}) decides what of its
         *             replies is shown: the plan steps' JSON is not, their thinking is
         */
        void update(String node, AgentGraph.State state) {
            last = state;
            List<ChatMessage> main = state.messages();
            for (; mainSeen < main.size(); mainSeen++) send(main.get(mainSeen), false);
            // The node's tracing name: a skill's from the lookup ("skill/coding/plan" for "skill-coding-plan",
            // names contain dashes), else "approval/review" for "approval-review"
            SkillLoader.NodeRef skillNode = skillNodes.get(node);
            String traced = skillNode != null ? skillNode.tracingName() : node.replaceFirst("-", "/");
            boolean textHidden = live.work(traced) != null;
            // Who wrote the new transcript messages: the skill of the node that just ran, or the builder, whose messages are in the skills' transcript but which isn't a skill
            String source = skillNode != null ? skillNode.skill()
                    : node.startsWith(AgentGraph.SKILL_BUILDER + "-") ? Progress.SKILL_BUILDER : state.skillName();
            List<ChatMessage> skill = state.allSkillMessages();
            for (; skillSeen < skill.size(); skillSeen++) sendJobMessage(skill.get(skillSeen), textHidden, false, source);
            // A job saved its plan (once it is valid) or its report: point to the file
            boolean savesPlan = skillNode != null
                    && (CodingKinds.PLAN.equals(skillNode.kind()) || CodingKinds.REPORT.equals(skillNode.kind()));
            if (savesPlan && !state.codingPlanFile().isBlank() && Files.exists(Path.of(state.codingPlanFile()))) {
                try {
                    listener.onPlanFile(state.codingPlanFile());
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Message listener failed", e);
                }
            }
        }

        /** Passes on a job's or skill's model reply (without its text when hidden) or tool result; not its prompts. */
        private void sendJobMessage(ChatMessage message, boolean textHidden, boolean codingTranscript, String source) {
            ChatMessage shown = null;
            if (message instanceof AiMessage ai && textHidden) {
                // The plan goes to its file and the plan dialog; keep only what's worth seeing here
                AiMessage visible = AiMessage.builder().thinking(ai.thinking())
                        .toolExecutionRequests(ai.toolExecutionRequests()).build();
                if (visible.thinking() != null || visible.hasToolExecutionRequests()) shown = visible;
            } else if (message instanceof AiMessage || message instanceof ToolExecutionResultMessage) {
                shown = message;
            }
            if (shown == null) return;
            try {
                listener.onJobMessage(shown, codingTranscript, source);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Message listener failed", e);
            }
        }

        void stepDone() {
            try {
                listener.onStepDone();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Message listener failed", e);
            }
        }

        private void send(ChatMessage message, boolean codingJob) {
            try {
                listener.onMessage(message, codingJob);
            } catch (RuntimeException e) {
                // Like progress, live messages are only a display: a broken listener must not abort the run
                LOG.log(System.Logger.Level.WARNING, "Message listener failed", e);
            }
        }
    }

    /**
     * The progress after a skill node finished (a coding job's nodes included), or null for other nodes
     * and for nodes that aren't worth showing (the plan approval, where the user is the one working).
     * <p>
     * A coding node (kind {@code coding.*}) reports the job's phase ("Planning", "Step 2/5", ...) with its
     * last command as the detail. Any other skill node reports "&lt;skill&gt; · &lt;node&gt;" as the phase and
     * the skill's last command as the detail; the tool step and tool approval report the node whose calls
     * they ran ({@code skillNode}); the start and the automatic stopped finish report nothing.
     *
     * @param state      the state after the node ran: verify has already counted its attempt, and edit has
     *                   already recorded the change it made in the ledger
     * @param skillNodes the skills' nodes by their id in the agent graph ({@link SkillLoader.Loaded#nodeIndex})
     * @param skills     the loaded skills, for a coding job's limits (verify attempts, design rounds)
     */
    static Progress progressOf(String node, AgentGraph.State state, Map<String, SkillLoader.NodeRef> skillNodes,
                               SkillLoader.Loaded skills) {
        SkillLoader.NodeRef skillNode = skillNodes.get(node);
        if (skillNode == null) {
            // The skill builder's own nodes ("skillBuilder-draft", ...): its steps are worth showing, except the
            // review (the user is the one working) and the closing nodes
            String prefix = AgentGraph.SKILL_BUILDER + "-";
            if (!node.startsWith(prefix)) return null;
            String step = node.substring(prefix.length());
            return switch (step) {
                case "draft", "validate", "save" -> new Progress("Skill builder · " + step, 0, 0, "");
                default -> null;
            };
        }
        if (CodingKinds.isCodingKind(skillNode.kind())) {
            CodingGraph.Limits limits = skills.skill(skillNode.skill())
                    .map(skill -> CodingGraph.Limits.of(skill.limits())).orElseThrow();
            return codingProgress(skillNode.kind(), state, limits);
        }
        String id = switch (skillNode.node()) {
            case SkillGraphBuilder.START, SkillGraphBuilder.STOPPED -> null;
            case SkillGraphBuilder.TOOL_STEP, SkillGraphBuilder.TOOL_APPROVAL -> state.skillNode();
            default -> skillNode.node();
        };
        if (id == null || id.isBlank()) return null;
        // The built-in coding job's own model steps, in words
        if (skillNode.skill().equals("coding")) {
            if (id.equals("study")) return new Progress("Researching the code", 0, 0, "");
            if (id.equals("work")) return new Progress("Working on it", 0, 0, "");
        }
        // How far the run is against its step limit: "release-notes · draft · step 3/40"
        int max = skills.skill(skillNode.skill()).map(skill -> skill.limits().maxSteps()).orElse(0);
        int step = state.skillSteps();
        return new Progress(skillNode.skill() + " · " + id + (max > 0 ? " · step " + step + "/" + max : ""),
                step, max, lastCommand(state.skillMessages()));
    }

    /** The progress after a coding job's node of the given kind ({@link CodingKinds}). */
    private static Progress codingProgress(String kind, AgentGraph.State state, CodingGraph.Limits limits) {
        String detail = "";
        return switch (kind) {
            case CodingKinds.ENVIRONMENT -> new Progress("Checking tools", 0, 0, detail);
            case CodingKinds.PICK_FILES, CodingKinds.GATHER -> new Progress("Finding the code", 0, 0, detail);
            case CodingKinds.PLAN -> new Progress("Planning", 0, 0, detail);
            case CodingKinds.REVISE -> new Progress("Reading your comment", 0, 0, detail);
            case CodingKinds.SETUP -> new Progress("Setting up", 0, 0, detail);
            case CodingKinds.EDIT -> {
                // The state is after the change: the changes that are done, applied or failed, and the one just made
                int total = state.codingPlan().map(p -> p.changes().size()).orElse(0);
                Ledger ledger = state.codingLedger().resizedTo(total);
                int done = total - ledger.indexes(Ledger.Status.PENDING).size();
                yield new Progress("Change " + done + "/" + total, done, total,
                        state.codingPlan().filter(p -> done > 0 && done <= p.changes().size())
                                .map(p -> p.changes().get(done - 1).file()).orElse(""));
            }
            case CodingKinds.VERIFY -> {
                int attempt = state.codingAttempts();
                int max = limits.verifyAttempts();
                yield new Progress("Verifying (attempt " + attempt + "/" + max + ")", attempt, max, detail);
            }
            case CodingKinds.FIX -> new Progress("Fixing", 0, 0, detail);
            case CodingKinds.DESIGN_REVIEW -> {
                int round = state.codingDesignRounds(); // already counted: the state is after the node
                if (round == 0) yield null;            // no guide applied: nothing was reviewed
                long issues = state.codingDesignIssues().lines().filter(l -> l.startsWith("- ")).count();
                yield new Progress("Reviewing design (round " + Math.max(round, 1) + "/" + limits.designRounds() + ")",
                        round, limits.designRounds(), issues == 0 ? "no issues" : issues + " issue" + (issues == 1 ? "" : "s"));
            }
            case CodingKinds.INTENT -> new Progress("Checking the change does what you asked", 0, 0, detail);
            case CodingKinds.REPORT -> new Progress("Writing report", 0, 0, detail);
            case CodingKinds.EXPLAIN -> new Progress("Explaining why nothing changed", 0, 0, detail);
            default -> null;
        };
    }

    /** The command of the last {@code runCommand} call in a job's or skill run's messages, or "" if there was none. */
    private static String lastCommand(List<ChatMessage> job) {
        for (int i = job.size() - 1; i >= 0; i--) {
            if (!(job.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            List<ToolExecutionRequest> calls = ai.toolExecutionRequests();
            for (int j = calls.size() - 1; j >= 0; j--) {
                if (!calls.get(j).name().equals("runCommand")) continue;
                try {
                    return JSON.readTree(calls.get(j).arguments()).path("command").asText("");
                } catch (JsonProcessingException e) {
                    return ""; // malformed arguments from the model: no detail rather than a failed display
                }
            }
        }
        return "";
    }

    /**
     * Asks the approver about the calls that need approval, as an "approval" span in the trace: the
     * conversation's open calls at the main approval pause, or a skill run's at its tool approval pause.
     *
     * @param registry the tools of the turn's graph, which say which calls need approval
     */
    private Map<String, Boolean> askApproval(List<ToolExecutionRequest> calls, AgentGraph.State state, ToolRegistry registry) {
        // Same rule as the graph: trusted commands, and file changes after web content in this turn
        List<ToolExecutionRequest> risky = calls.stream().filter(c -> state.needsApproval(registry, c)).toList();
        Span span = tracing.tracer().spanBuilder("human approval")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("gen_ai.prompt", risky.toString())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // Start from "everything denied", then overlay the approver's answers: anything it doesn't answer stays denied
            Map<String, Boolean> decisions = new HashMap<>(Approver.DENY_ALL.review(risky));
            try {
                decisions.putAll(approver.review(risky)); // blocks until the user has answered
            } catch (RuntimeException e) {
                span.recordException(e); // treat a failing approver as "denied"
            }
            span.setAttribute("gen_ai.completion", decisions.toString());
            return decisions;
        } finally {
            span.end();
        }
    }

    /**
     * Where a run paused, for the "no progress" check: the node, the open tool calls of the conversation
     * and of the skill run, how many coding job and skill messages exist, and the skill's steps. The
     * same pause twice in a row means resuming changed nothing.
     */
    private record Pause(String node, List<ToolExecutionRequest> openCalls, List<ToolExecutionRequest> skillOpenCalls,
                         String codingProgress, int skillMessages, int skillSteps) {
    }

    /**
     * What a coding job has done so far, as one comparable text: the plans made, the ledger of changes, the verify
     * attempts and the revisions. A pause that comes back with the same text as the one before it made no progress.
     */
    private static String codingProgress(AgentGraph.State state) {
        return state.codingPlanRounds() + "|" + state.codingLedger().toJson() + "|" + state.codingAttempts() + "|"
                + state.codingRevisions() + "|" + state.codingReadRounds();
    }

    /**
     * Asks the approver about the coding job's plan, as a "plan approval" span in the trace, and returns
     * the answer as state updates for the coding subgraph's router ({@code codingDecision}, {@code codingComment}).
     */
    private Map<String, Object> askPlanApproval(AgentGraph.State state) {
        // The graph only pauses here after review stored a valid plan
        CodingPlan plan = state.codingPlan()
                .orElseThrow(() -> new IllegalStateException("Paused for plan approval without a plan"));
        // Read while paused: the plan approval step clears the revision reason and the comment once it runs.
        // A revision is any plan after the first one the user saw: one was approved, or sent back with Revise
        Ledger ledger = state.codingLedger().resizedTo(plan.changes().size());
        PlanReview review = new PlanReview(plan, ledger.indexes(Ledger.Status.APPLIED), ledger.indexes(Ledger.Status.FAILED),
                state.codingRevisionReason(), state.codingComment(),
                state.codingApprovedPlan().isPresent() || CodingGraph.REVISE.equals(state.codingDecision()),
                state.codingPlanFile(), state.codingAnswer());
        Span span = tracing.tracer().spanBuilder("human plan approval")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("gen_ai.prompt", review.toMarkdown())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            PlanDecision decision = PlanDecision.DENIED; // anything that goes wrong denies the plan
            try {
                PlanDecision answer = approver.reviewPlan(review); // blocks until the user has answered
                if (answer != null) decision = answer;
            } catch (RuntimeException e) {
                span.recordException(e); // treat a failing approver as "denied"
            }
            span.setAttribute("gen_ai.completion", decision.toString());
            return Map.of(AgentGraph.State.CODING_DECISION, decision.stateValue(),
                    AgentGraph.State.CODING_COMMENT, decision.comment());
        } finally {
            span.end();
        }
    }

    /**
     * Asks the approver about the skill builder's draft (the pause before {@code skillBuilder-review}), as a
     * "human skill review" span in the trace, and returns the answer as state updates for the builder's
     * router ({@code skillDecision}, {@code skillComment}). Written on every resume from this pause, so an
     * older decision is never read for a new draft. Anything that goes wrong (the approver fails or returns
     * nothing) cancels: nothing is saved without the user's Save.
     *
     * @param registry the tools of the turn's graph, which say which of the draft's tools need approval
     */
    private Map<String, Object> askSkillReview(AgentGraph.State state, ToolRegistry registry) {
        // The graph only pauses here after validate stored a valid draft
        SkillBuilderGraph.Draft parsed = SkillBuilderGraph.Draft.parse(state.skillDraft());
        SkillFile skill = SkillFile.parse(parsed.json());
        SkillDraft draft = new SkillDraft(skill, parsed.json(), SkillDiagram.outline(skill),
                SkillDiagram.toolsNeedingApproval(skill, registry), parsed.replaces(),
                parsed.replaces() && skillLoader.builtInNames().contains(skill.name()), skillLoader.toolOf(skill));
        Span span = tracing.tracer().spanBuilder("human skill review")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("gen_ai.prompt", draft.toMarkdown())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            SkillDecision decision = SkillDecision.CANCELLED;
            try {
                SkillDecision answer = approver.reviewSkill(draft); // blocks until the user has answered
                if (answer != null) decision = answer;
            } catch (RuntimeException e) {
                span.recordException(e); // treat a failing approver as "cancel"
            }
            span.setAttribute("gen_ai.completion", decision.toString());
            return Map.of(AgentGraph.State.SKILL_DECISION, decision.stateValue(),
                    AgentGraph.State.SKILL_COMMENT, decision.comment());
        } finally {
            span.end();
        }
    }

    /**
     * Asks the approver a skill's question (the pause before an {@code askUser} node), as a "human skill
     * question" span in the trace, and returns the answer as the {@code skillAnswer} update the node
     * reads: {@code {"node", "choice", "comment"}} as JSON, or "" when the question was dismissed (also
     * when the approver fails or returns nothing), which the node treats as "cancelled by the user".
     * Written on every resume from such a pause, so an older answer is never read for a new question.
     */
    /**
     * Asks the user the question of the analysis (the pause before the {@code ask} node): its options are the choices, and what the user adds is
     * a comment. With no options there is one choice, "Go on", and the comment is the free answer. The answer, as one text, is written to
     * {@code askAnswer}; "" when the question was dismissed (the node then says "decide yourself").
     */
    private Map<String, Object> askAnalysisQuestion(AgentGraph.State state) {
        String question = Analysis.questionOf(state.askQuestion());
        List<String> options = Analysis.optionsOf(state.askQuestion());
        List<String> choices = options.isEmpty() ? List.of("Go on") : options;
        Span span = tracing.tracer().spanBuilder("human question")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("gen_ai.prompt", question)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            SkillAnswer answer = SkillAnswer.dismissed(); // anything that goes wrong dismisses the question
            try {
                SkillAnswer given = approver.askUser(new SkillQuestion("", AgentGraph.ASK_PAUSE, question, choices));
                if (given != null) answer = given;
            } catch (RuntimeException e) {
                span.recordException(e);
            }
            span.setAttribute("gen_ai.completion", answer.toString());
            if (answer.isDismissed()) return Map.of(AgentGraph.State.ASK_ANSWER, "");
            // The comment is what the user wrote; a click on an option alone is the option
            String text = !answer.comment().isBlank() ? (options.isEmpty() ? answer.comment() : answer.choice() + ": " + answer.comment()) : answer.choice();
            return Map.of(AgentGraph.State.ASK_ANSWER, text);
        } finally {
            span.end();
        }
    }

    private Map<String, Object> askSkillQuestion(SkillLoader.NodeRef ref, AgentGraph.State state, SkillLoader.Loaded skills) {
        SkillFile.NodeSpec node = skills.skill(ref.skill()).stream().flatMap(skill -> skill.nodes().stream())
                .filter(n -> n.id().equals(ref.node())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Paused before an unknown skill node " + ref));
        // The choices as the kind reads them (its outcomes); the question with the skill's variables filled in
        List<String> choices = List.copyOf(skillLoader.validator().kinds().get(node.kind()).outcomes(node));
        String text = Templates.render((String) node.fields().get("question"), GenericKinds.vars(state));
        SkillQuestion question = new SkillQuestion(ref.skill(), ref.node(), text, choices);
        Span span = tracing.tracer().spanBuilder("human skill question")
                .setAttribute("langsmith.span.kind", "chain")
                .setAttribute("gen_ai.prompt", question.toString())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            SkillAnswer answer = SkillAnswer.dismissed(); // anything that goes wrong dismisses the question
            try {
                SkillAnswer given = approver.askUser(question); // blocks until the user has answered
                if (given != null) answer = given;
            } catch (RuntimeException e) {
                span.recordException(e);
            }
            span.setAttribute("gen_ai.completion", answer.toString());
            if (answer.isDismissed()) return Map.of(AgentGraph.State.SKILL_ANSWER, "");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("node", ref.node()); // the node accepts only an answer for itself
            value.put("choice", answer.choice());
            value.put("comment", answer.comment());
            return Map.of(AgentGraph.State.SKILL_ANSWER, JSON.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The answer could not be stored", e); // plain strings: doesn't happen
        } finally {
            span.end();
        }
    }

    public static String newThreadId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The end of a turn: the Java changes the tools made in memory are written to the files, so nothing stays only in
     * memory. A file that changed on disk meanwhile is not overwritten; the log says which.
     */
    private void writeJavaChanges() {
        try {
            for (var result : JavaWorkspaces.shared().flushAll()) {
                if (!result.ok()) {
                    AgentLog.current().line("⚠ Java files not written, they changed on disk meanwhile: " + String.join(", ", result.conflicts()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Writing the Java changes failed", e);
        }
    }

    /** Flushes pending trace spans (LangSmith export is batched) before the app exits; writes and closes the Java workspaces. */
    @Override
    public void close() {
        writeJavaChanges();
        JavaWorkspaces.shared().close();
        tracing.close();
    }
}
