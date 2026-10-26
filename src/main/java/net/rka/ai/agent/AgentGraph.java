package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.rka.ai.graph.annotations.Edge;
import net.rka.ai.graph.annotations.Graph;
import net.rka.ai.graph.annotations.Node;
import net.rka.ai.graph.annotations.NodeWrapper;
import net.rka.ai.graph.annotations.Route;
import net.rka.ai.graph.annotations.Router;
import net.rka.ai.graph.annotations.Subgraph;
import net.rka.ai.graph.annotations.Subgraphs;
import net.rka.ai.agent.skills.GenericKinds;
import net.rka.ai.agent.skills.NodeKind;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillGraphBuilder;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.agent.skills.SkillRuntime;
import net.rka.ai.agent.skills.SkillValidator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tools.TrustedCommands;
import net.rka.ai.tools.WorkFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A ReAct-style agent with approval and trusted-command steps for risky tools
 * (diagram: docs/agent-graph.md):
 *
 *   START -> memory -> agent                                   (memory: summarize old messages if needed)
 *            agent --(safe tool calls)-----> tools ----> agent
 *                  --(only trusted risky)--> trusted --> agent   (subgraph: verify -> execute)
 *                  --(needs approval)------> approval -> agent   (subgraph: review -> execute)
 *                  --(startCodingJob)------> skill-coding -> agent  (the built-in skill coding, see below)
 *                  --(buildSkill)----------> skillBuilder -> agent  (subgraph, see below)
 *                  --(skill_&lt;name&gt; call)--> skill-&lt;name&gt; -> agent  (one subgraph per loaded skill file)
 *                  --(no tool calls)-------> END                 (also after 12 tool rounds in a turn)
 *
 *   skillBuilder (subgraph, see {@link SkillBuilderGraph}): tools -> draft -> validate --(invalid, &lt; 3)--> draft
 *          validate --(valid)--> ⏸ review --(save)--> save -> finish;  --(revise)--> draft;  --(cancel)--> finish
 *          draft --(stopped)--> finish;  validate --(failed: 3rd invalid draft)--> finish
 *
 *   skill-&lt;name&gt; (subgraph, see {@link SkillGraphBuilder}): start -> the file's nodes -> finish
 *          + toolStep, ⏸ toolApproval (guarded tool calls), ⏸ before each askUser node, stopped
 *
 *   skill-coding (coding.json, steps in {@link CodingGraph}): start -> environment -> investigate (&lt;-&gt; investigateTools) -> plan
 *          -> review -> ⏸ planApproval -> implement (&lt;-&gt; implementTools) -> verify -> designReview -> report
 *          (planApproval --(revise)--> plan, implementTools --(revise)--> plan; every router's "stopped" -> report)
 *
 *   The nodes of a skill are inlined as "skill-&lt;name&gt;-&lt;id&gt;" and the routes "skill-&lt;name&gt;" are added by the
 *   {@link Subgraphs @Subgraphs} method {@link #skills()}; the whole diagram with the node table is in docs/agent-graph.md.
 *
 * The graph pauses before "approval", before the skill builder's review ("skillBuilder-review"), and before each skill's "skill-&lt;name&gt;-toolApproval", askUser
 * and coding.planApproval nodes ("skill-coding-planApproval") (LangGraph4j interrupts);
 * {@link Agent} asks the user, stores the decisions in the state and resumes. Trusted commands go through "trusted" and run
 * without asking. The wiring is declared with annotations; the graph processor checks it at
 * compile time and generates {@code AgentGraphDefinition}.
 *
 * <h2>What "ReAct" means here</h2>
 * The model alternates between <b>re</b>asoning and <b>act</b>ing: it answers, or asks for tools; the
 * tools run; their results are appended to the conversation; the model is asked again with them.
 * The loop {@code agent → tools → agent → ...} ends when the model replies without tool calls; after
 * {@link #MAX_AGENT_ROUNDS} rounds of tool calls in one turn the model is asked once more without
 * tools, so it has to.
 *
 * <h2>How LangGraph4j executes this class</h2>
 * <ol>
 *   <li>The annotations below describe nodes (steps), edges (fixed next steps) and routers (a method
 *       that picks the next step). At compile time, {@code graph-processor} turns them into
 *       {@code AgentGraphDefinition}, which builds a LangGraph4j {@code StateGraph}.</li>
 *   <li>{@link #compile} adds runtime options: a checkpointer (saves the state after each step, needed
 *       to pause and resume) and {@code interruptBefore(APPROVAL)}.</li>
 *   <li>Each node gets the current {@link State} and returns a map of <em>updates</em>. How an update is
 *       merged depends on the key's "channel": {@code MESSAGES} appends; other keys are replaced.</li>
 * </ol>
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Streaming: {@code model.chat} waits for the whole answer. A {@code StreamingChatModel} could show
 *       tokens as they arrive.</li>
 *   <li>Context management: the whole history is sent every time. Long conversations will hit the
 *       model's context limit; summarizing or trimming old messages would fix that.</li>
 *   <li>Planning or reflection nodes (e.g. "plan → act → check the result"), and multi-agent setups
 *       where a router hands the task to specialized sub-agents.</li>
 *   <li>A persistent checkpointer (SQLite/Postgres) so an interrupted approval survives an app restart.</li>
 * </ul>
 */
@Graph(name = "agent", state = AgentGraph.State.class, serializer = Graph.Serializer.LANGCHAIN4J)
@Edge(from = Graph.START, to = "memory")
@Edge(from = "memory", to = "agent")
@Edge(from = "tools", to = "agent")
@Edge(from = "trusted", to = "agent")
@Edge(from = "approval", to = "agent")
@Edge(from = AgentGraph.SKILL_BUILDER, to = "agent")
public class AgentGraph {

    /** The node the graph pauses before, to ask for approval. */
    static final String APPROVAL = "approval";
    /** Runs risky calls whose commands are all trusted, without pausing. */
    static final String TRUSTED = "trusted";
    /**
     * The next node of the saved state while the graph is paused before {@link #APPROVAL}. LangGraph4j
     * inlines a subgraph's nodes into the parent as "&lt;subgraph&gt;-&lt;node&gt;", so the pause before the
     * subgraph is a pause before its first node, "approval-review".
     */
    static final String APPROVAL_PAUSE = APPROVAL + "-" + ApprovalGraph.REVIEW;
    /**
     * Every skill runs as the node "skill-&lt;name&gt;" ({@link #skills()}); its nodes are inlined as
     * "skill-&lt;name&gt;-&lt;node id&gt;", e.g. the pause "skill-release-notes-toolApproval".
     */
    static final String SKILLS_PREFIX = "skill-";
    /**
     * The node the graph pauses before to ask about a coding job's plan: the planApproval node of the
     * built-in skill coding, inlined as "skill-coding-planApproval" (also the paused state's next node).
     * {@code Agent} recognizes the pause by the node's kind ({@code coding.planApproval}), not this id.
     */
    static final String PLAN_APPROVAL = SKILLS_PREFIX + CodingGraph.SKILL + "-planApproval";
    /** The skill builder's subgraph ({@link SkillBuilderGraph}), started by {@code buildSkill}. */
    static final String SKILL_BUILDER = "skillBuilder";
    /**
     * The node the graph pauses before to show the user a skill draft: the builder's review node, inlined as
     * "skillBuilder-review" (also the paused state's next node, which is how {@code Agent} recognizes it).
     */
    static final String SKILL_REVIEW_PAUSE = SKILL_BUILDER + "-" + SkillBuilderGraph.REVIEW;
    /**
     * Node steps one run (an invoke or a resume) may take before LangGraph4j stops it. Its default (25)
     * is too low for a coding job, which takes a few node steps per model turn; the job's own limits
     * (model turns per step, verify attempts) bound it, so this is only a backstop against a loop.
     * The plain agent ↔ tools loop has its own, much lower limit: {@link #MAX_AGENT_ROUNDS}.
     */
    static final int RECURSION_LIMIT = 1000;
    /**
     * Rounds of tool calls the model may make in one turn ({@link State#agentToolRounds}). The model
     * call after the last one gets no tools and must answer, so a model stuck calling tools ends the
     * turn with an answer instead of looping until {@link #RECURSION_LIMIT}. A coding job's own rounds
     * don't count (they have their own limits); its {@code startCodingJob} call is one round.
     */
    static final int MAX_AGENT_ROUNDS = 12;
    /** The text of a final reply that only had tool calls, which were stripped because of {@link #MAX_AGENT_ROUNDS}. */
    static final String TOO_MANY_ROUNDS = "Stopped: too many tool rounds in one turn.";

    /**
     * Graph state: an append-only list of chat messages, plus the user's approval decisions and
     * the run's settings. LangGraph4j stores it as a map; the typed getters below read from it.
     */
    public static class State extends AgentState {
        // Public: the skill subgraphs (net.rka.ai.agent.skills) read and write these too
        public static final String MESSAGES = "messages";
        /** Map of tool call id to approved (true) or denied (false); written by {@link Agent} on resume. */
        public static final String APPROVALS = "approvals";
        /** The conversation's trusted working folder (a path string); set by {@link Agent} at the start of a run. */
        public static final String WORK_FOLDER = "workFolder";
        /** Global plus conversation trusted commands, one per line; set by {@link Agent} at the start of a run. */
        public static final String TRUSTED_COMMANDS = "trustedCommands";
        /** The running summary of older messages (summary memory); "" until the first summary. */
        static final String SUMMARY = "summary";
        /** How many messages the summary replaces (see {@link ConversationSummary#covered()}). */
        static final String SUMMARY_COVERED = "summaryCovered";
        /** The conversation's long-term facts, one "id&lt;TAB&gt;text" per line; replaced by the tool nodes. */
        static final String FACTS = "facts";

        // Coding job keys, used only by the coding kinds' nodes (CodingGraph, the skill "coding"). Values are Strings and
        // Integers (the plan as JSON), so the LangChain4j serializer checkpoints them without help.
        // A job resets them in its first node; "" and 0 mean "not set".
        /** The goal from the {@code startCodingJob} call. */
        static final String CODING_GOAL = "codingGoal";
        /** The id of the {@code startCodingJob} call (a copy of {@link #SKILL_CALL_ID}, which the report answers). */
        static final String CODING_CALL_ID = "codingCallId";
        /** The environment summary (OS, shell, tool versions) for the job's prompts. */
        static final String CODING_ENVIRONMENT = "codingEnvironment";
        /** The coding jobs' own transcript (appender), kept out of the main {@link #MESSAGES}. */
        static final String CODING_MESSAGES = "codingMessages";
        /** Index into {@link #CODING_MESSAGES} where the current job begins (earlier jobs of the turn come before). */
        static final String CODING_START = "codingStart";
        /** What the investigation found (the model's summary text). */
        static final String CODING_FINDINGS = "codingFindings";
        /** The current plan as JSON ({@link CodingPlan#toJson()}). */
        static final String CODING_PLAN = "codingPlan";
        /** The user's answer to the plan: approve / revise / deny ({@link CodingGraph#APPROVE} ...); written by Agent on resume. */
        static final String CODING_DECISION = "codingDecision";
        /** The user's comment with a "revise" decision. */
        static final String CODING_COMMENT = "codingComment";
        /** Why the graph sent the job back to planning (e.g. an unplanned tool call). */
        static final String CODING_REVISION_REASON = "codingRevisionReason";
        /** How many plan steps are done (the index of the current step). */
        static final String CODING_STEP = "codingStep";
        /** Model turns used on the current step. */
        static final String CODING_STEP_TURNS = "codingStepTurns";
        /** Verify attempts used in this job. */
        static final String CODING_ATTEMPTS = "codingAttempts";
        /** The output of the last verify run. */
        static final String CODING_VERIFY_OUTPUT = "codingVerifyOutput";
        /** The job's final report. */
        static final String CODING_REPORT = "codingReport";
        /** Plan parse errors since the last valid plan, one per line ("plan: ..." / "review: ..."). */
        static final String CODING_PLAN_ERRORS = "codingPlanErrors";
        /** The plan the user approved last (JSON); a later revision only replaces it once approved. */
        static final String CODING_APPROVED_PLAN = "codingApprovedPlan";
        /**
         * The plan items that ran successfully, one "&lt;step number&gt;&lt;TAB&gt;&lt;item&gt;" per line, the item
         * as {@link CodingPlan#items} names it: what shows that a step was carried out, not just answered.
         */
        static final String CODING_DONE_ITEMS = "codingDoneItems";
        /** 1-based numbers of steps the job moved past although none of their items ran, comma-separated. */
        static final String CODING_SKIPPED_STEPS = "codingSkippedSteps";
        /** 1 once the current step was answered without running any of its items and the model was reminded. */
        static final String CODING_STEP_REMINDED = "codingStepReminded";
        /** Why the job stopped early (model or tool error, step turn limit, no valid plan); "" while it runs. */
        static final String CODING_STOP_REASON = "codingStopReason";
        /** The job's Markdown plan file (absolute path; "" when plans aren't written, e.g. in tests). */
        static final String CODING_PLAN_FILE = "codingPlanFile";
        /** The design review's latest findings as a Markdown list ("" = none, or not reviewed yet). */
        static final String CODING_DESIGN_ISSUES = "codingDesignIssues";
        /** How many design reviews ran in this job (at most {@code CodingGraph.MAX_DESIGN_ROUNDS}). */
        static final String CODING_DESIGN_ROUNDS = "codingDesignRounds";
        /** Verify attempts used before the last design review: attempts after its fix count from here (0 = none). */
        static final String CODING_VERIFY_BASE = "codingVerifyBase";

        // Skill keys, used only by the skill subgraphs (net.rka.ai.agent.skills.SkillGraphBuilder). Like the
        // coding keys: Strings and Integers (variables as a JSON object string), reset by a skill's start node.
        /** The running skill's name. */
        public static final String SKILL_NAME = "skillName";
        /** The id of the call that started the skill, answered with the skill's result by its finish. */
        public static final String SKILL_CALL_ID = "skillCallId";
        /** The skills' own transcript (appender): their model replies and tool results, kept out of {@link #MESSAGES}. */
        public static final String SKILL_MESSAGES = "skillMessages";
        /** Index into {@link #SKILL_MESSAGES} where the current skill run begins (earlier runs of the turn come before). */
        public static final String SKILL_START = "skillStart";
        /** The skill's variables as a JSON object string: {@code input}, the built-ins, node outputs and set values. */
        public static final String SKILL_VARS = "skillVars";
        /** The id (inside the skill) of the node running or last run; tool steps return to it. */
        public static final String SKILL_NODE = "skillNode";
        /** Node visits in this skill run, against its {@code maxSteps}. */
        public static final String SKILL_STEPS = "skillSteps";
        /** Rounds of tool calls in the current visit of a {@code model} node, against {@code maxToolRounds}. */
        public static final String SKILL_TOOL_ROUNDS = "skillToolRounds";
        /**
         * The user's answer to an {@code askUser} node, as a JSON object string
         * {@code {"node": <node id inside the skill>, "choice": ..., "comment": ...}}; written by {@link Agent}
         * on every resume from such a pause ("" when the question was dismissed). The node marks it
         * {@code "used": true}; an answer for another node or a used one counts as dismissed.
         */
        public static final String SKILL_ANSWER = "skillAnswer";
        /** The skill's result: the text its finish answered the call with. */
        public static final String SKILL_RESULT = "skillResult";
        /** Why the skill stopped early (model or tool error, cancelled question); "" while it runs. */
        public static final String SKILL_STOP_REASON = "skillStopReason";

        // Skill builder keys, used only by SkillBuilderGraph. It also uses SKILL_CALL_ID (the buildSkill call),
        // SKILL_MESSAGES / SKILL_START (its drafting transcript) and SKILL_STOP_REASON (a model error);
        // its tools node resets all of them
        /** The current draft: the skill's JSON as the model wrote it, normalized once valid (may hold {@code "replaces": true}). */
        static final String SKILL_DRAFT = "skillDraft";
        /**
         * The problems of the review round's invalid drafts, one per line as "&lt;attempt&gt;&lt;TAB&gt;&lt;problem&gt;";
         * "" once a draft is valid and when a Revise starts a new round.
         */
        static final String SKILL_DRAFT_ERRORS = "skillDraftErrors";
        /** The user's answer to the draft: save / revise / cancel ({@link SkillBuilderGraph#SAVE} ...); written by Agent on resume. */
        static final String SKILL_DECISION = "skillDecision";
        /** The user's comment with a "revise" decision. */
        static final String SKILL_COMMENT = "skillComment";

        /**
         * Channels define how updates merge. MESSAGES, CODING_MESSAGES and SKILL_MESSAGES append; keys not listed
         * here default to "last value wins". Strings are used for the folder and commands
         * (not Path / lists) because they serialize trivially when the state is checkpointed.
         * <p>
         * Both keep duplicates: LangGraph4j's plain appender drops a message equal to one already in
         * the list. A conversation repeats messages all the time (the same question asked again, "yes"
         * twice), and a job can too (the reviewer returning the plan unchanged, the same goal in a second
         * job). Dropping them would make the state shorter than the history {@link Agent} passed in,
         * breaking the "everything after the input is new" split, and {@link #CODING_START} indexes.
         */
        public static final Map<String, Channel<?>> SCHEMA = Map.of(
                MESSAGES, Channels.appenderWithDuplicate(ArrayList::new),
                CODING_MESSAGES, Channels.appenderWithDuplicate(ArrayList::new),
                SKILL_MESSAGES, Channels.appenderWithDuplicate(ArrayList::new));

        public State(Map<String, Object> init) {
            super(init);
        }

        public List<ChatMessage> messages() {
            return this.<List<ChatMessage>>value(MESSAGES).orElseGet(List::of);
        }

        public ChatMessage lastMessage() {
            List<ChatMessage> messages = messages();
            return messages.get(messages.size() - 1);
        }

        public Map<String, Boolean> approvals() {
            return this.<Map<String, Boolean>>value(APPROVALS).orElseGet(Map::of);
        }

        public Path workFolder() {
            return this.<String>value(WORK_FOLDER).map(Path::of).orElseGet(WorkFolder::defaultFolder);
        }

        /** The conversation's summary memory as stored in the state. */
        public ConversationSummary summary() {
            return new ConversationSummary(this.<String>value(SUMMARY).orElse(""),
                    this.<Integer>value(SUMMARY_COVERED).orElse(1));
        }

        /** The conversation's long-term facts as stored in the state. */
        public List<FactBook.Fact> facts() {
            List<FactBook.Fact> facts = new ArrayList<>();
            for (String line : this.<String>value(FACTS).orElse("").split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) facts.add(new FactBook.Fact(line.substring(0, tab), line.substring(tab + 1)));
            }
            return facts;
        }

        /** Facts as the state stores them: a plain string, so checkpointing needs no custom serializer. */
        static String encodeFacts(List<FactBook.Fact> facts) {
            StringBuilder out = new StringBuilder();
            for (FactBook.Fact fact : facts) {
                out.append(fact.id()).append('\t').append(fact.text().replaceAll("\\s+", " ")).append('\n');
            }
            return out.toString();
        }

        public TrustedCommands trustedCommands() {
            return TrustedCommands.of(this.<String>value(TRUSTED_COMMANDS).map(s -> s.lines().toList()).orElseGet(List::of));
        }

        // --- Coding job (see CodingGraph); missing keys read as "" / 0 ---

        public String codingGoal() {
            return text(CODING_GOAL);
        }

        public String codingCallId() {
            return text(CODING_CALL_ID);
        }

        public String codingEnvironment() {
            return text(CODING_ENVIRONMENT);
        }

        /** The current job's transcript: the part of {@link #CODING_MESSAGES} from {@link #codingStart()} on. */
        public List<ChatMessage> codingMessages() {
            List<ChatMessage> all = allCodingMessages();
            return all.subList(Math.min(codingStart(), all.size()), all.size());
        }

        /** Every coding job's messages in this run; the next job starts at its size. */
        List<ChatMessage> allCodingMessages() {
            return this.<List<ChatMessage>>value(CODING_MESSAGES).orElseGet(List::of);
        }

        public int codingStart() {
            return number(CODING_START);
        }

        public String codingFindings() {
            return text(CODING_FINDINGS);
        }

        /** The current plan, or empty before the first valid one. */
        public Optional<CodingPlan> codingPlan() {
            String json = text(CODING_PLAN);
            return json.isBlank() ? Optional.empty() : Optional.of(CodingPlan.parse(json)); // stored normalized, so it parses
        }

        public String codingDecision() {
            return text(CODING_DECISION);
        }

        public String codingComment() {
            return text(CODING_COMMENT);
        }

        public String codingRevisionReason() {
            return text(CODING_REVISION_REASON);
        }

        public int codingStep() {
            return number(CODING_STEP);
        }

        public int codingStepTurns() {
            return number(CODING_STEP_TURNS);
        }

        public int codingAttempts() {
            return number(CODING_ATTEMPTS);
        }

        public String codingVerifyOutput() {
            return text(CODING_VERIFY_OUTPUT);
        }

        public String codingReport() {
            return text(CODING_REPORT);
        }

        /** Plan parse errors since the last valid plan, oldest first. */
        public List<String> codingPlanErrors() {
            return text(CODING_PLAN_ERRORS).lines().filter(line -> !line.isBlank()).toList();
        }

        /** The plan the user approved last, or empty before the first approval. */
        public Optional<CodingPlan> codingApprovedPlan() {
            String json = text(CODING_APPROVED_PLAN);
            return json.isBlank() ? Optional.empty() : Optional.of(CodingPlan.parse(json));
        }

        /** The record of plan items that ran, as stored: one "step&lt;TAB&gt;item" line each. */
        public String codingDoneItems() {
            return text(CODING_DONE_ITEMS);
        }

        /** The items of the given step (1-based) that ran successfully in this job. */
        public Set<String> codingItemsDone(int stepNumber) {
            String prefix = stepNumber + "\t";
            Set<String> items = new HashSet<>();
            codingDoneItems().lines().filter(l -> l.startsWith(prefix)).forEach(l -> items.add(l.substring(prefix.length())));
            return items;
        }

        /** 1-based numbers of the skipped steps, in order. */
        public Set<Integer> codingSkippedSteps() {
            Set<Integer> steps = new TreeSet<>();
            for (String part : text(CODING_SKIPPED_STEPS).split(",")) {
                if (!part.isBlank()) steps.add(Integer.parseInt(part.strip())); // written by CodingGraph only
            }
            return steps;
        }

        public boolean codingStepReminded() {
            return number(CODING_STEP_REMINDED) > 0;
        }

        public String codingStopReason() {
            return text(CODING_STOP_REASON);
        }

        public String codingPlanFile() {
            return text(CODING_PLAN_FILE);
        }

        public String codingDesignIssues() {
            return text(CODING_DESIGN_ISSUES);
        }

        public int codingDesignRounds() {
            return number(CODING_DESIGN_ROUNDS);
        }

        public int codingVerifyBase() {
            return number(CODING_VERIFY_BASE);
        }

        // --- Skills (see net.rka.ai.agent.skills.SkillGraphBuilder); missing keys read as "" / 0 ---

        public String skillName() {
            return text(SKILL_NAME);
        }

        public String skillCallId() {
            return text(SKILL_CALL_ID);
        }

        /** The current skill run's transcript: the part of {@link #SKILL_MESSAGES} from {@link #skillStart()} on. */
        public List<ChatMessage> skillMessages() {
            List<ChatMessage> all = allSkillMessages();
            return all.subList(Math.min(skillStart(), all.size()), all.size());
        }

        /** Every skill run's messages in this run of the graph; the next skill run starts at its size. */
        public List<ChatMessage> allSkillMessages() {
            return this.<List<ChatMessage>>value(SKILL_MESSAGES).orElseGet(List::of);
        }

        public int skillStart() {
            return number(SKILL_START);
        }

        /** The variables as stored: a JSON object string, "" before the skill's start node ran. */
        public String skillVars() {
            return text(SKILL_VARS);
        }

        public String skillNode() {
            return text(SKILL_NODE);
        }

        public int skillSteps() {
            return number(SKILL_STEPS);
        }

        public int skillToolRounds() {
            return number(SKILL_TOOL_ROUNDS);
        }

        /** The user's answer to the last {@code askUser} pause as stored: a JSON object string, "" when dismissed. */
        public String skillAnswer() {
            return text(SKILL_ANSWER);
        }

        public String skillResult() {
            return text(SKILL_RESULT);
        }

        public String skillStopReason() {
            return text(SKILL_STOP_REASON);
        }

        // --- Skill builder (see SkillBuilderGraph); missing keys read as "" ---

        public String skillDraft() {
            return text(SKILL_DRAFT);
        }

        public String skillDraftErrors() {
            return text(SKILL_DRAFT_ERRORS);
        }

        public String skillDecision() {
            return text(SKILL_DECISION);
        }

        public String skillComment() {
            return text(SKILL_COMMENT);
        }

        /**
         * Rounds of tool calls the model made in this turn: AI messages with tool calls since the user's
         * last message. A coding job's own rounds are in {@link #CODING_MESSAGES}, so they don't count;
         * its {@code startCodingJob} call is one round.
         */
        int agentToolRounds() {
            List<ChatMessage> messages = messages();
            int rounds = 0;
            for (int i = messages.size() - 1; i >= 0 && !(messages.get(i) instanceof UserMessage); i--) {
                if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) rounds++;
            }
            return rounds;
        }

        private String text(String key) {
            return this.<String>value(key).orElse("");
        }

        private int number(String key) {
            return this.<Integer>value(key).orElse(0);
        }

        /**
         * Whether a result from a {@link net.rka.ai.tools.WebContent} tool (web search, fetched page)
         * came in since the user's last message: in the conversation, or in any coding job or skill of
         * this turn (their transcripts are in {@link #CODING_MESSAGES} and {@link #SKILL_MESSAGES}, which
         * a run starts empty, so all of it belongs to this turn). Until the next message, file changes
         * need approval and trusted commands don't count as trusted, also after the job or skill that
         * read the page has ended.
         */
        boolean webContentInTurn(ToolRegistry tools) {
            if (allCodingMessages().stream().anyMatch(m -> isWebResult(m, tools))) return true;
            if (allSkillMessages().stream().anyMatch(m -> isWebResult(m, tools))) return true;
            List<ChatMessage> messages = messages();
            // Walk backwards: the turn starts at the latest UserMessage
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                if (message instanceof UserMessage) return false;
                if (isWebResult(message, tools)) return true;
            }
            return false;
        }

        private static boolean isWebResult(ChatMessage message, ToolRegistry tools) {
            return message instanceof ToolExecutionResultMessage result && tools.readsWeb(result.toolName());
        }

        /**
         * Whether the call needs the user's approval: trusted commands, and web content in this turn
         * (the conversation's, coding jobs' and skills' transcripts). Skills' tool steps use it too.
         */
        public boolean needsApproval(ToolRegistry tools, ToolExecutionRequest call) {
            return tools.needsApproval(call, trustedCommands(), webContentInTurn(tools));
        }

        /** Whether the call is a risky one that may run without asking (a trusted command, no web content). */
        boolean isTrusted(ToolRegistry tools, ToolExecutionRequest call) {
            return tools.isTrusted(call, trustedCommands(), webContentInTurn(tools));
        }

        /**
         * Web content for a coding job: a {@link net.rka.ai.tools.WebContent} result in this job's or an
         * earlier job's transcript of the turn, in a skill run of the turn, or in the conversation's turn
         * that started it (the same as {@link #webContentInTurn}). From then on the user's trusted commands no longer run unasked
         * inside the job (only the fixed probes, and what an approved plan covers).
         */
        boolean codingWebContent(ToolRegistry tools) {
            return webContentInTurn(tools);
        }

        /** Whether a coding job may run the call as one of the user's trusted commands: the same rule as {@link #isTrusted}, with the job's web content. */
        boolean codingTrusted(ToolRegistry tools, ToolExecutionRequest call) {
            return tools.isTrusted(call, trustedCommands(), codingWebContent(tools));
        }

        /**
         * Tool calls of the latest AI message that have no result message yet.
         * This is how every node knows "what is still to do": results are matched to calls by id.
         */
        public List<ToolExecutionRequest> unansweredToolCalls() {
            return unansweredIn(messages());
        }

        /** Like {@link #unansweredToolCalls()}, for the current coding job's transcript. */
        public List<ToolExecutionRequest> codingUnansweredToolCalls() {
            return unansweredIn(codingMessages());
        }

        /** Like {@link #unansweredToolCalls()}, for the current skill run's transcript. */
        public List<ToolExecutionRequest> skillUnansweredToolCalls() {
            return unansweredIn(skillMessages());
        }

        /** The calls of the list's latest AI message without a result after it. */
        private static List<ToolExecutionRequest> unansweredIn(List<ChatMessage> messages) {
            Set<String> answered = new HashSet<>();
            // Walk backwards, collecting result ids, until the AI message that made the calls
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage message = messages.get(i);
                if (message instanceof ToolExecutionResultMessage result) {
                    answered.add(result.id());
                } else if (message instanceof AiMessage ai) {
                    return ai.hasToolExecutionRequests()
                            ? ai.toolExecutionRequests().stream().filter(c -> !answered.contains(c.id())).toList()
                            : List.of();
                }
            }
            return List.of();
        }
    }

    private final ChatModel model;
    private final ToolCallRunner toolCalls;
    private final NodeTracing tracing;
    // Summary memory budgets (estimated tokens): summarize when the unsummarized part exceeds
    // summaryTrigger, keeping about summaryRecent of the newest messages verbatim
    private final int summaryTrigger;
    private final int summaryRecent;
    // The loaded skills (one subgraph each; the coding job is the built-in skill "coding"), their kinds,
    // and skill name by the tool that starts it
    private final SkillLoader.Loaded skills;
    private final Map<String, NodeKind> kinds;
    private final Map<String, String> skillsByTool;
    // What the skill builder checks drafts with and saves them to
    private final SkillLoader skillLoader;

    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing) {
        this(model, toolCalls, tracing, 6000, 2000);
    }

    /** Without skills (so without coding jobs either), and a skill builder that has no folder to save to. */
    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, int summaryTrigger, int summaryRecent) {
        this(model, toolCalls, tracing, summaryTrigger, summaryRecent, new SkillLoader.Loaded(List.of(), Map.of()),
                new SkillLoader(null, List.of(), new SkillValidator(GenericKinds.all(), toolCalls.tools())));
    }

    /**
     * @param skills the loaded skills, each added as the subgraph {@code skill-<name>} ({@link #skills()}); their
     *               tools must be in {@code toolCalls}' registry ({@link ToolRegistry#withSkills}). The coding job
     *               is one of them: the built-in skill {@code coding}, started by {@link CodingGraph#START_TOOL}.
     * @param loader the loader {@code skills} came from: its validator's node kinds are the ones the skills
     *               run with (the coding kinds carry the coding job's environment summary, command timeout,
     *               plan folder and design guides), and the skill builder checks drafts by its rules and
     *               saves them to its folder
     */
    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, int summaryTrigger, int summaryRecent,
               SkillLoader.Loaded skills, SkillLoader loader) {
        this.skills = skills;
        this.skillLoader = loader;
        this.kinds = Map.copyOf(loader.validator().kinds());
        Map<String, String> byTool = new LinkedHashMap<>();
        for (SkillFile skill : skills.skills()) byTool.put(skill.toolName(), skill.name());
        this.skillsByTool = Map.copyOf(byTool);
        this.model = model;
        this.toolCalls = toolCalls;
        this.tracing = tracing;
        this.summaryTrigger = summaryTrigger;
        this.summaryRecent = summaryRecent;
    }

    /**
     * Compiled with an in-memory checkpointer, pausing before the approval subgraph, before the skill
     * builder's review ({@link #SKILL_REVIEW_PAUSE}) and before every skill's pause nodes
     * ({@link #skillPauses}), a coding job's plan approval among them.
     */
    CompiledGraph<State> compile() throws GraphStateException {
        List<String> interrupts = new ArrayList<>(List.of(APPROVAL, SKILL_REVIEW_PAUSE));
        interrupts.addAll(skillPauses(skills, kinds));
        return AgentGraphDefinition.compile(this, CompileConfig.builder()
                .checkpointSaver(new MemorySaver())  // keeps state between invoke() calls, in memory only
                // Stop and return before these nodes run; Agent asks the user, updates the state and resumes
                .interruptBefore(interrupts.toArray(String[]::new))
                .recursionLimit(recursionLimit(skills)) // a coding job takes far more than the default 25 node steps
                .releaseThread(true) // drop a run's checkpoints once it finishes
                .build());
    }

    /**
     * The ids the graph pauses before for the skills, as LangGraph4j inlines them ("skill-&lt;name&gt;-&lt;id&gt;"):
     * each skill's {@code toolApproval}, {@code askUser} and {@code coding.planApproval} nodes
     * ({@link SkillGraphBuilder#pauseNodes}). {@link Agent} finds out which skill and node a paused id is
     * through {@link SkillLoader.Loaded#nodeIndex}.
     *
     * @param kinds the node kinds the skills were validated with
     */
    static List<String> skillPauses(SkillLoader.Loaded skills, Map<String, NodeKind> kinds) {
        List<String> ids = new ArrayList<>();
        for (SkillFile skill : skills.skills()) {
            for (String id : SkillGraphBuilder.pauseNodes(skill, kinds)) ids.add(SKILLS_PREFIX + skill.name() + "-" + id);
        }
        return ids;
    }

    /**
     * {@link #RECURSION_LIMIT}, or more when a skill may take more node steps in one run: per step
     * ({@code maxSteps}), a {@code model} node can go through its tool step and back {@code maxToolRounds}
     * times, plus its own visit and one more; 100 on top for the agent's own nodes. So a skill always
     * stops at its own {@code maxSteps}, with an answer, before LangGraph4j stops the whole run.
     */
    static int recursionLimit(SkillLoader.Loaded skills) {
        int limit = RECURSION_LIMIT;
        for (SkillFile skill : skills.skills()) {
            SkillFile.Limits limits = skill.limits();
            limit = Math.max(limit, limits.maxSteps() * (2 * limits.maxToolRounds() + 2) + 100);
        }
        return limit;
    }

    /**
     * Summary memory, once per turn before the model is asked: if the messages after the current
     * summary have grown past the budget, summarize the older ones (see {@link SummaryMemory}).
     * Returns no updates when nothing needs summarizing.
     */
    @Node("memory")
    Map<String, Object> summarize(State state) {
        ConversationSummary current = state.summary();
        List<ChatMessage> messages = state.messages();
        int cutoff = SummaryMemory.cutoff(messages, current.covered(), summaryTrigger, summaryRecent);
        if (cutoff <= current.covered()) return Map.of(); // small enough: keep the summary as it is
        // One extra model call: merge the old summary with the messages that drop out of view
        String text = SummaryMemory.summarize(model, current.text(), messages.subList(current.covered(), cutoff));
        if (text.isBlank()) return Map.of(); // the model returned nothing: better to send more than lose context
        return Map.of(State.SUMMARY, text, State.SUMMARY_COVERED, cutoff);
    }

    /**
     * Asks the model for the next step: an answer or tool calls. After {@link #MAX_AGENT_ROUNDS} rounds
     * of tool calls in this turn it is asked once more without tools, and any calls it makes anyway are
     * stripped, so {@link #route} sees a plain answer and ends the turn with nothing left open.
     */
    @Node("agent")
    Map<String, Object> callModel(State state) {
        boolean lastRound = state.agentToolRounds() >= MAX_AGENT_ROUNDS;
        // The conversation (older messages replaced by the summary, if any) + the tool list;
        // the model decides whether to answer or call tools
        List<ChatMessage> messages = new ArrayList<>(SummaryMemory.view(state.messages(), state.summary()));
        ChatRequest.Builder request = ChatRequest.builder();
        if (lastRound) {
            // Only this request needs it, so it isn't stored in the state
            messages.add(UserMessage.from("You have used " + MAX_AGENT_ROUNDS + " rounds of tool calls in this turn."
                    + " Answer now without tool calls: say what is done and what is left."));
        } else {
            request.toolSpecifications(toolCalls.tools().specifications());
        }
        AiMessage reply = model.chat(request.messages(messages).build()).aiMessage(); // blocks until Lemonade answers
        if (lastRound && reply.hasToolExecutionRequests()) {
            // Calls despite no tools offered: keep only the text, so no call stays open in the conversation
            String text = reply.text() == null || reply.text().isBlank() ? TOO_MANY_ROUNDS : reply.text();
            reply = AiMessage.from(text);
        }
        return Map.of(State.MESSAGES, reply); // appended to the history by the MESSAGES channel
    }

    /** Runs tool calls that don't need approval. */
    @Node("tools")
    Map<String, Object> executeTools(State state) {
        return toolCalls.runUnanswered(state); // results + updated facts, as a state update
    }

    /** Reviews, then runs, tool calls that need approval (and any safe calls next to them). */
    @Subgraph(APPROVAL)
    StateGraph<State> approval() throws GraphStateException {
        return ApprovalGraphDefinition.stateGraph(new ApprovalGraph(toolCalls, tracing));
    }

    /** Verifies, then runs, risky calls that are all trusted (and any safe calls next to them). */
    @Subgraph(TRUSTED)
    StateGraph<State> trusted() throws GraphStateException {
        return TrustedGraphDefinition.stateGraph(new TrustedGraph(toolCalls, tracing));
    }

    /**
     * Drafts a skill from the {@code buildSkill} description, checks it, pauses for the user's review
     * (the pause before {@link #SKILL_REVIEW_PAUSE}) and saves it on Save; {@code Agent} reloads the
     * skills after the turn.
     */
    @Subgraph(SKILL_BUILDER)
    StateGraph<State> skillBuilder() throws GraphStateException {
        return SkillBuilderGraphDefinition.stateGraph(new SkillBuilderGraph(model, tracing, skillLoader));
    }

    /**
     * The skills, one subgraph each, built from their files ({@link SkillGraphBuilder}). The generated
     * graph adds each as the node {@code skill-<name>} with an edge back to "agent", and the route
     * {@code skill-<name>} to {@link #route}; with no skills loaded the map is empty and the graph is the
     * same as without skills. The coding job is the built-in skill {@code coding}: investigate, plan, the
     * user's plan approval (the pause before {@link #PLAN_APPROVAL}), implement, verify, and the report
     * that answers its {@code startCodingJob} call.
     */
    @Subgraphs(prefix = SKILLS_PREFIX, returnTo = "agent", router = "route")
    Map<String, StateGraph<State>> skills() throws GraphStateException {
        Map<String, StateGraph<State>> graphs = new LinkedHashMap<>();
        for (SkillFile skill : skills.skills()) {
            graphs.put(skill.name(), SkillGraphBuilder.build(new SkillRuntime(skill, model, toolCalls, tracing, kinds)));
        }
        return graphs;
    }

    /**
     * The skills whose subgraphs build ({@link SkillGraphBuilder#build}, as {@link #skills()} builds them); each
     * one that throws is left out and listed under its file name. A valid file can still fail here (a node kind
     * that fails while its action is made, a node id LangGraph4j rejects), and that must cost the one skill, not
     * the app: {@code Agent} builds the graph only from what this returns, so routes, pauses and the tool list
     * all match the subgraphs that are really there.
     *
     * @param toolCalls a runner whose registry has the skills' tools (building runs nothing)
     */
    static SkillLoader.Loaded buildable(SkillLoader.Loaded skills, ChatModel model, ToolCallRunner toolCalls,
                                        NodeTracing tracing, Map<String, NodeKind> kinds) {
        return buildable(skills, List.of(), model, toolCalls, tracing, kinds);
    }

    /**
     * Like {@link #buildable(SkillLoader.Loaded, ChatModel, ToolCallRunner, NodeTracing, Map)}, and a user
     * file that replaces a built-in but fails to build gives way to that built-in (when the built-in builds),
     * as an invalid replacement does in {@link SkillLoader}: the error is listed under the user's file, and
     * the built-in one (e.g. the coding job) stays available instead of disappearing with it.
     *
     * @param builtIns the built-in skills as they ship ({@link SkillLoader#builtInsOnly()}), to fall back to
     */
    static SkillLoader.Loaded buildable(SkillLoader.Loaded skills, List<SkillFile> builtIns, ChatModel model,
                                        ToolCallRunner toolCalls, NodeTracing tracing, Map<String, NodeKind> kinds) {
        List<SkillFile> kept = new ArrayList<>();
        Map<String, List<String>> errors = new LinkedHashMap<>(skills.errors());
        boolean changed = false;
        for (SkillFile skill : skills.skills()) {
            String problem = buildProblem(skill, model, toolCalls, tracing, kinds);
            if (problem == null) {
                kept.add(skill);
                continue;
            }
            changed = true;
            String message = "The skill can't be added to the agent graph: " + problem;
            // A replacement of a built-in that doesn't build: the built-in is used instead, if it builds itself
            SkillFile builtIn = builtIns.stream()
                    .filter(b -> b.name().equals(skill.name()) && !b.equals(skill))
                    .findFirst().orElse(null);
            if (builtIn != null) {
                String builtInProblem = buildProblem(builtIn, model, toolCalls, tracing, kinds);
                if (builtInProblem == null) {
                    kept.add(builtIn);
                    message += " The built-in " + skill.name() + " skill is used instead.";
                } else {
                    message += " The built-in " + skill.name() + " skill can't be added either: " + builtInProblem;
                }
            }
            errors.computeIfAbsent(skill.name() + ".json", k -> new ArrayList<>()).add(message);
        }
        return changed ? new SkillLoader.Loaded(kept, errors) : skills;
    }

    /** Why the skill's subgraph can't be built, or null when it builds (building runs nothing). */
    private static String buildProblem(SkillFile skill, ChatModel model, ToolCallRunner toolCalls,
                                       NodeTracing tracing, Map<String, NodeKind> kinds) {
        try {
            SkillGraphBuilder.build(new SkillRuntime(skill, model, toolCalls, tracing, kinds));
            return null;
        } catch (GraphStateException | RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * After the model: the coding skill's subgraph if it started a coding job, the skill builder if it
     * called {@code buildSkill}, the skill's subgraph if it started a skill, approval if any call needs it, trusted if every risky call is trusted, tools for
     * safe calls, else the answer is final.
     * <p>
     * The order matters: one reply can contain several calls of different kinds. A {@code startCodingJob}
     * call wins over everything: it goes to the skill whose tool it is ("skill-coding"), whose start node
     * answers the other calls with "Not run", so none of them runs (and {@code startCodingJob} itself
     * never reaches a node that would execute it). {@code buildSkill} comes next and goes to the skill
     * builder, whose first node answers the other calls the same way. Another skill's tool comes next, the
     * same way: the skill's start node answers the other calls (a second skill's call among them), and the
     * skill's tool never runs. Without a loaded coding skill, {@code startCodingJob} is an ordinary (failing) tool. Else,
     * if any call needs approval, the whole batch goes to "approval" (whose execute step also runs the
     * safe ones), so the user sees the risky call before anything from that batch runs.
     * <p>
     * The per-turn cap ({@link #MAX_AGENT_ROUNDS}) ends here too, through "done": a router can't change
     * the state, so {@link #callModel} makes the reply after the last allowed round a plain answer.
     */
    @Router(from = "agent", routes = {
            @Route(on = "approval", to = APPROVAL),
            @Route(on = "trusted", to = TRUSTED),
            @Route(on = "tools", to = "tools"),
            @Route(on = SKILL_BUILDER, to = SKILL_BUILDER),
            @Route(on = "done", to = Graph.END)})
    String route(State state) {
        List<ToolExecutionRequest> calls = state.unansweredToolCalls();
        if (calls.isEmpty()) return "done"; // a plain answer: the turn is over
        String coding = skillsByTool.get(CodingGraph.START_TOOL);
        if (coding != null && calls.stream().anyMatch(c -> CodingGraph.START_TOOL.equals(c.name()))) {
            return SKILLS_PREFIX + coding; // "skill-coding", a route the generated graph adds per skill
        }
        if (calls.stream().anyMatch(c -> SkillBuilderGraph.START_TOOL.equals(c.name()))) return SKILL_BUILDER;
        // The first skill call of the reply; "skill-<name>" is a route the generated graph adds per skill
        Optional<String> skill = calls.stream().map(c -> skillsByTool.get(c.name())).filter(Objects::nonNull).findFirst();
        if (skill.isPresent()) return SKILLS_PREFIX + skill.get();
        if (calls.stream().anyMatch(c -> state.needsApproval(toolCalls.tools(), c))) return "approval";
        if (calls.stream().anyMatch(c -> state.isTrusted(toolCalls.tools(), c))) return "trusted";
        return "tools";
    }

    /** Wraps every node of this graph in a tracing span (see {@link NodeTracing}). */
    @NodeWrapper
    NodeActionWithConfig<State> traced(String name, NodeActionWithConfig<State> action) {
        return tracing.wrap(name, action);
    }
}

