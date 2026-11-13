package net.rka.ai.agent;

import net.rka.ai.agent.skills.CodingKinds;

import java.util.Map;

/**
 * Where real-time output of the running turn goes: model tokens as they are generated (thinking,
 * text, tool call arguments) and command output while a command runs.
 * <p>
 * Why this isn't graph state: LangGraph4j state changes only when a node finishes, so it can carry
 * each finished reply and tool result ({@link Agent.MessageFeed} passes those on), but not the
 * tokens of a reply that is still being generated or the lines of a command that is still running.
 * This is display only: nothing here changes what the graph decides or stores, like tracing.
 * <p>
 * {@link Agent} points the channel at the turn's listener when a turn starts and back to
 * {@link Progress#NONE} when it ends; the streaming model ({@link StreamingChatModelAdapter}) and
 * the tool runner ({@link ToolCallRunner}) send to whatever listener is current. Turns of one
 * {@code Agent} run one at a time (the window and CLI both wait for a turn to end), so there is
 * only ever one current listener. Every call is guarded: a failing listener never breaks the turn.
 */
final class LiveChannel {

    // --- Stopping a turn: the graph runs its nodes on threads of its own, which interrupting the caller does not reach ---
    private volatile boolean cancelled;
    private final java.util.Set<Thread> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A new turn starts: nothing is cancelled. */
    void begin() {
        cancelled = false;
    }

    /** The user stopped the turn: no node starts any more, and the nodes that run are interrupted (a model request is cancelled, a command killed). */
    void cancel() {
        cancelled = true;
        running.forEach(Thread::interrupt);
    }

    boolean cancelled() {
        return cancelled;
    }

    void started(Thread node) {
        running.add(node);
    }

    void finished(Thread node) {
        running.remove(node);
        if (cancelled) Thread.interrupted(); // the interrupt was ours: the pool thread must not take it to its next task
    }


    private volatile Progress.ProgressListener listener = Progress.NONE;
    // The graph node whose model call is streaming now ("agent", "memory", "skill/coding/plan", ...), set by
    // NodeTracing when a node starts; it decides what of the stream is shown (see hiddenWork)
    private volatile String node = "";
    private int workChars; // characters of hidden text streamed in the current node
    // Skill nodes that show progress instead of text (thinking "progress", plan/review kinds), by tracing name
    // ("skill/<name>/<id>") → their label;
    // set by Agent at the start of a turn from the skills that turn runs with
    private volatile Map<String, String> skillWork = Map.of();

    /** The graph step running now (its tracing name), e.g. for the agent log. */
    String node() {
        return node;
    }

    /** Called by {@link NodeTracing} when a node starts. */
    void enter(String node) {
        this.node = node == null ? "" : node;
        this.workChars = 0;
    }

    /**
     * What to show instead of the text a graph node streams, or null to show its text as it comes: the
     * memory step writes the conversation summary, which is internal, and the skill builder's draft step
     * writes the skill's JSON, which the review shows readably, so only their progress shows. Skill
     * nodes are decided by their kind ({@link #hiddenWorkOfKind}, through {@link #skillWork}).
     * Everything else streams as it is: the agent's words, a job's findings and step summaries.
     */
    static String hiddenWork(String node) {
        return switch (node) {
            case "memory" -> "Updating memory";
            case "triage", "analyze" -> "Deciding how to handle this";
            case AgentGraph.SKILL_BUILDER + "/draft" -> "Drafting the skill";
            default -> null;
        };
    }

    /**
     * What a skill node of this kind shows instead of its streamed text, whatever the node is called:
     * <ul>
     *   <li>The plan and review steps of a coding job write the plan as JSON: that belongs in the plan
     *       file and the plan dialog, not in the chat, so only its progress is shown (their thinking
     *       still shows).</li>
     *   <li>The design review answers with a JSON list of issues: the report and the fix show them readably.</li>
     * </ul>
     * Null for the other kinds.
     */
    static String hiddenWorkOfKind(String kind) {
        return switch (kind) {
            case CodingKinds.PICK_FILES -> "Choosing files";
            case CodingKinds.PLAN -> "Writing the plan";
            case CodingKinds.EDIT -> "Writing code";
            case CodingKinds.FIX -> "Fixing code";
            case CodingKinds.DESIGN_REVIEW -> "Reviewing the design";
            default -> null;
        };
    }

    /**
     * The skill nodes whose streamed text is shown as progress, by tracing name, with the label to show:
     * a {@code model} node with {@code "thinking": "progress"} ("&lt;skill&gt;: &lt;node&gt;"), and every node
     * of a kind with {@link #hiddenWorkOfKind hidden work} (built by {@code Agent} from the skills' node index).
     */
    void skillWork(Map<String, String> labels) {
        this.skillWork = Map.copyOf(labels);
    }

    /** What to show instead of the node's streamed text: {@link #hiddenWork}, or a skill node's label; null to show the text. */
    String work(String node) {
        String work = hiddenWork(node);
        return work != null ? work : skillWork.get(node);
    }

    void set(Progress.ProgressListener listener) {
        this.listener = listener == null ? Progress.NONE : listener;
    }

    void clear() {
        listener = Progress.NONE;
    }

    void thinking(String delta) {
        if ("memory".equals(node) || "triage".equals(node)) return; // the summarizer's and the triage's thinking isn't about your question
        try {
            listener.onPartialThinking(delta);
        } catch (RuntimeException ignored) {
            // display only
        }
    }

    void text(String delta) {
        String work = work(node);
        try {
            if (work != null) {
                workChars += delta.length();
                listener.onPartialWork(work, workChars);
                return;
            }
            listener.onPartialText(delta);
        } catch (RuntimeException ignored) {
            // display only
        }
    }

    void toolCall(int index, String toolName, String argumentsDelta) {
        try {
            // The decision of a node with hidden work (the analysis calls route) is not shown as a tool: it is progress, like its text
            String work = work(node);
            if (work != null) {
                workChars += argumentsDelta.length();
                listener.onPartialWork(work, workChars);
                return;
            }
            listener.onPartialToolCall(index, toolName, argumentsDelta);
        } catch (RuntimeException ignored) {
            // display only
        }
    }

    void toolOutput(String callId, String chunk) {
        try {
            listener.onToolOutput(callId, chunk);
        } catch (RuntimeException ignored) {
            // display only
        }
    }
}
