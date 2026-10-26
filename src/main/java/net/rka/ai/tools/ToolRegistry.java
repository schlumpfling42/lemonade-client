package net.rka.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import net.rka.ai.agent.skills.SkillFile;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The tools the agent can call: every {@code @Tool} method of the registered objects.
 * Tool names are method names (or {@code @Tool(name = ...)}) and must be unique.
 *
 * <h2>How tool calling works, end to end</h2>
 * <ol>
 *   <li>At startup, {@link #of} scans the tool objects with reflection. For each {@code @Tool} method
 *       it stores a {@link ToolSpecification} (name, description, JSON schema of the parameters) and
 *       a {@link ToolExecutor} that knows how to invoke the method.</li>
 *   <li>Every model request sends {@link #specifications()} along (see {@code AgentGraph.callModel}).
 *       The model may answer with "tool calls": a tool name plus JSON arguments
 *       ({@link ToolExecutionRequest}).</li>
 *   <li>The graph decides whether a call may run now, needs approval, or is trusted
 *       ({@link #needsApproval} / {@link #isTrusted}).</li>
 *   <li>{@link #execute} runs it: LangChain4j's {@link DefaultToolExecutor} parses the JSON arguments,
 *       converts them to the Java parameter types, calls the method, and turns the return value into
 *       text. The result goes back into the conversation as a {@link ToolExecutionResultMessage}, and
 *       the model is asked again with it.</li>
 * </ol>
 * The marker annotations ({@link RequiresApproval}, {@link WebContent}, {@link WritesFiles}) are
 * collected here too, so the rest of the code asks the registry instead of using reflection.
 *
 * <h2>The approval rule (in one place)</h2>
 * <pre>
 *   tool                       | no web content in turn        | web content in turn
 *   ---------------------------+-------------------------------+---------------------
 *   &#64;RequiresApproval, trusted command |  runs ("trusted" subgraph) |  asks
 *   &#64;RequiresApproval, other           |  asks                      |  asks
 *   &#64;WritesFiles, trusted tool:name    |  runs ("trusted" subgraph) |  asks
 *   &#64;WritesFiles, other                |  asks                      |  asks
 *   everything else (read-only)        |  runs                      |  runs
 * </pre>
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Enabling/disabling tools per conversation or in settings (today it's this hard-coded list).</li>
 *   <li>MCP (Model Context Protocol) servers as tool sources: LangChain4j has an MCP client
 *       ({@code langchain4j-mcp}) whose tools could be registered next to these.</li>
 *   <li>Parallel execution of independent calls (today {@code ToolCallRunner} runs them one by one).</li>
 *   <li>Per-tool timeouts and result size limits applied centrally instead of inside each tool.</li>
 *   <li>Fewer tools per request: small models choose better from a short list; a router could send
 *       only the tools relevant to the question.</li>
 * </ul>
 */
public final class ToolRegistry {

    /** The tools the agent uses. Register new tool classes here. */
    public static ToolRegistry defaultTools() {
        return of(
                new DateTimeTools(),
                new MathTools(),
                new FileTools(),
                new MemoryTools(),
                new WebTools(),
                new TerminalTools(),
                new SshTools(),
                new CodingTools(),  // startCodingJob: never runs, the graph routes it to the coding subgraph
                new SkillTools());  // buildSkill: never runs, the graph routes it to the skill builder
    }

    // LinkedHashMap keeps registration order, so the model sees the tools in a stable order
    private final Map<String, ToolSpecification> specifications = new LinkedHashMap<>();
    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();
    // Tool names per marker annotation, filled in of()
    private final Set<String> needApproval = new HashSet<>();
    private final Set<String> webContent = new HashSet<>();
    private final Set<String> writesFiles = new HashSet<>();
    // The tools that start a skill (withSkills): specified for the model, never executed
    private final Set<String> skillTools = new HashSet<>();
    // The registry without skill tools that withSkills starts from (this one, for a registry made by of())
    private ToolRegistry base = this;

    private ToolRegistry() {
    }

    public static ToolRegistry of(Object... toolObjects) {
        ToolRegistry registry = new ToolRegistry();
        for (Object tools : toolObjects) {
            // getDeclaredMethods: only methods declared in this class (not inherited ones)
            for (Method method : tools.getClass().getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) continue;
                // LangChain4j builds the description and the JSON schema from @Tool, @P and the parameter types.
                // Parameters of type InvocationParameters are left out of the schema: the model never sees them.
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
                if (registry.specifications.containsKey(spec.name())) {
                    throw new IllegalStateException("Duplicate tool name '" + spec.name() + "' in "
                            + tools.getClass().getSimpleName() + "; rename the method or set @Tool(name = ...)");
                }
                registry.specifications.put(spec.name(), spec);
                // The executor holds the object and the method; it does argument parsing and invocation
                registry.executors.put(spec.name(), new DefaultToolExecutor(tools, method));
                if (method.isAnnotationPresent(RequiresApproval.class)) registry.needApproval.add(spec.name());
                if (method.isAnnotationPresent(WebContent.class)) registry.webContent.add(spec.name());
                if (method.isAnnotationPresent(WritesFiles.class)) registry.writesFiles.add(spec.name());
            }
        }
        return registry;
    }

    /**
     * This registry plus one tool per skill, the one that starts it ({@link SkillFile#toolName()}): its
     * description is the skill's, its parameters the skill's inputs (strings; the required ones
     * required). The model sees them like any tool, but they never run: the agent graph's router sends
     * such a call to the skill's subgraph (like {@code startCodingJob}), and {@link #execute} answers one
     * that gets here anyway as an unknown tool. A skill tool with the name of a {@code @Tool} method
     * (a built-in skill that keeps an existing tool name) replaces it.
     * <p>
     * A new registry each time, from the tools of {@link #of} (skill tools of an earlier call are
     * dropped), so reloading skills swaps the whole list; the tool objects themselves are shared.
     * Inside skills and coding jobs, skill tools are never offered ({@link #isSkillTool}).
     */
    public ToolRegistry withSkills(List<SkillFile> skills) {
        ToolRegistry view = new ToolRegistry();
        view.base = base;
        view.specifications.putAll(base.specifications);
        view.executors.putAll(base.executors);
        view.needApproval.addAll(base.needApproval);
        view.webContent.addAll(base.webContent);
        view.writesFiles.addAll(base.writesFiles);
        for (SkillFile skill : skills) {
            String name = skill.toolName();
            view.specifications.put(name, skillSpecification(skill));
            view.executors.remove(name); // routed, never run
            view.needApproval.remove(name);
            view.webContent.remove(name);
            view.writesFiles.remove(name);
            view.skillTools.add(name);
        }
        return view;
    }

    /** The specification of a skill's tool: name, the skill's description, one string parameter per input. */
    private static ToolSpecification skillSpecification(SkillFile skill) {
        JsonObjectSchema.Builder parameters = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        for (SkillFile.Input input : skill.inputs()) {
            parameters.addStringProperty(input.name(), input.description());
            if (input.required()) required.add(input.name());
        }
        return ToolSpecification.builder()
                .name(skill.toolName())
                .description(skill.description())
                .parameters(parameters.required(required).build())
                .build();
    }

    /** Whether the tool starts a skill ({@link #withSkills}): never offered inside skills or coding jobs, never run. */
    public boolean isSkillTool(String toolName) {
        return skillTools.contains(toolName);
    }

    /** What the model is told about each tool: name, description and parameter schema. */
    public List<ToolSpecification> specifications() {
        return new ArrayList<>(specifications.values());
    }

    /**
     * Only the tools whose name passes the filter, in registration order. A coding job uses it to
     * offer each phase just the tools it may use (e.g. read-only tools while investigating), so the
     * model isn't tempted by calls that would be refused anyway.
     */
    public List<ToolSpecification> specifications(Predicate<String> toolName) {
        return specifications.values().stream().filter(spec -> toolName.test(spec.name())).toList();
    }

    public Set<String> names() {
        return specifications.keySet();
    }

    /** Whether the tool is marked {@link RequiresApproval}. Unknown tools don't need approval (they fail anyway). */
    public boolean requiresApproval(String toolName) {
        return needApproval.contains(toolName);
    }

    /** Whether the tool's result is internet text ({@link WebContent}). */
    public boolean readsWeb(String toolName) {
        return webContent.contains(toolName);
    }

    /** Whether the tool changes files ({@link WritesFiles}). */
    public boolean writesFiles(String toolName) {
        return writesFiles.contains(toolName);
    }

    /**
     * A call to a {@link RequiresApproval} tool whose command is on the run's trusted list: it runs
     * without asking. Never once web content is in the turn, since a page could have asked for it.
     */
    public boolean isTrusted(ToolExecutionRequest call, TrustedCommands trusted, boolean webContentInTurn) {
        if (webContentInTurn) return false; // after web content nothing consequential runs unasked
        if (requiresApproval(call.name())) return trusted.isTrusted(call);       // a trusted command
        if (writesFiles(call.name())) return trusted.trustsTool(call.name());    // a trusted file tool (tool:name)
        return false; // read-only tools never need trust
    }

    /**
     * A call the user must approve: a {@link RequiresApproval} or {@link WritesFiles} tool that
     * isn't trusted (see {@link #isTrusted}). Only tools without either mark (reading inside the
     * folder, search, date, math, ...) run without approval or trust.
     */
    public boolean needsApproval(ToolExecutionRequest call, TrustedCommands trusted, boolean webContentInTurn) {
        boolean guarded = requiresApproval(call.name()) || writesFiles(call.name());
        return guarded && !isTrusted(call, trusted, webContentInTurn);
    }

    /**
     * Runs one tool call. Failures (unknown tool, bad arguments, exceptions thrown by the
     * tool) come back as an error result instead of aborting the run, so the model can react.
     */
    public ToolExecutionResultMessage execute(ToolExecutionRequest call) {
        return execute(call, new InvocationParameters());
    }

    /**
     * Runs one tool call with per-run values, e.g. the conversation's {@link WorkFolder}.
     * Tool methods receive them by declaring an {@link InvocationParameters} parameter
     * (which the model doesn't see).
     */
    public ToolExecutionResultMessage execute(ToolExecutionRequest call, InvocationParameters parameters) {
        ToolExecutor executor = executors.get(call.name());
        if (skillTools.contains(call.name())) {
            // Only the graph starts skills (its router); a call that reaches a tool node is refused
            return error(call, "Not run: " + call.name() + " starts a skill, which can't be started from here.");
        }
        if (executor == null) {
            // Models sometimes invent tool names; tell them which ones exist so they can retry
            return error(call, "Unknown tool '" + call.name() + "'. Available tools: " + names());
        }
        try {
            // The context is how LangChain4j hands InvocationParameters to tool methods that declare them
            InvocationContext context = InvocationContext.builder()
                    .invocationId(UUID.randomUUID())
                    .invocationParameters(parameters)
                    .timestampNow()
                    .build();
            ToolExecutionResult result = executor.executeWithContext(call, context);
            return result.isError()
                    ? error(call, "Error: " + result.resultText())
                    : ToolExecutionResultMessage.from(call, result.resultText());
        } catch (Exception e) {
            // Reflection wraps the tool's own exception in InvocationTargetException; unwrap it so the
            // model sees "Cannot divide by zero", not "InvocationTargetException: null"
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
            return error(call, "Error: " + cause.getMessage());
        }
    }

    /** A result flagged {@code isError}, with the same id as the call so the model can pair them. */
    private static ToolExecutionResultMessage error(ToolExecutionRequest call, String message) {
        return ToolExecutionResultMessage.builder()
                .id(call.id())
                .toolName(call.name())
                .text(message)
                .isError(true)
                .build();
    }
}
