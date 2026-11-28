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
import net.rka.ai.graph.annotations.Offers;
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
import net.rka.ai.tracing.AgentLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A ReAct-style agent with approval and trusted-command steps for risky tools
 * (diagram: docs/agent-graph.md):
 *
 *   START -> memory -> analyze -> agent                        (memory: summarize old messages if needed;
 *                                                               analyze: the model returns the goal and what happens next:
 *                                                               an edge (agent or a job), a tool call, or a question for the user)
 *            analyze --(question)--> ⏸ ask -> analyze           (the answer joins the conversation; at most 2 questions a turn)
 *            tools | trusted | approval --(the analysis asked for the tool)--> analyze   (at most 4 tool calls a turn)
 *            agent --(safe tool calls)-----> tools ----> agent
 *                  --(only trusted risky)--> trusted --> agent   (subgraph: verify -> execute)
 *                  --(needs approval)------> approval -> agent   (subgraph: review -> execute)
 *                  --(startCodingJob)------> skill-coding -> agent  (the built-in skill coding, see below; a job that succeeded has answered the user itself, so agent makes no call and the turn ends;
 *                                                                   also when the reply edits existing code by hand)
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
 *   skill-coding (coding.json, steps in {@link CodingGraph}; the model never calls a tool in it): start -> environment
 *          -> pickFiles -> gather -> plan (&lt;-&gt; read more code) -> ⏸ planApproval -> setup (a failed command: plan again) -> edit (per file)
 *          -> verify (&lt;-&gt; fix) -> report -> (no code changed: explain) -> answer
 *          (planApproval --(revise)--> revise --(change)--> plan, revise --(question)--> planApproval;
 *          fix --(revise)--> planApproval; every router's "stopped" -> report;
 *          pickFiles --(study)--> study (read-only model, research notes) -> gather;
 *          open-ended work: pickFiles --(free)--> work (a model with tools) -> check (the build) -> [repair -> recheck] -> workDone, no plan)
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
@Edge(from = "memory", to = "analyze")
@Edge(from = "ask", to = "analyze")
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
    /** The node the graph pauses before to ask the user the analysis' question ({@link #ask}). */
    static final String ASK_PAUSE = "ask";
    /** The most questions the analysis may ask the user in one turn. */
    static final int MAX_ASKS = 2;
    /** The most tool calls the analysis may make in one turn. */
    static final int MAX_ANALYSIS_TOOLS = 4;
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
        /** The files the tools touched and the version the conversation has seen of each ({@link Knowledge}): "path TAB version" per line. */
        static final String KNOWLEDGE = "knowledge";

        // Coding job keys, used only by the coding kinds' nodes (CodingGraph, the skill "coding"). Values are Strings and
        // Integers (the plan as JSON), so the LangChain4j serializer checkpoints them without help.
        // A job resets them in its first node; "" and 0 mean "not set".
        /** The goal from the {@code startCodingJob} call. */
        static final String CODING_GOAL = "codingGoal";
        /** The id of the {@code startCodingJob} call (a copy of {@link #SKILL_CALL_ID}, which the report answers). */
        static final String CODING_CALL_ID = "codingCallId";
        /** The environment summary (OS, shell, tool versions) for the job's prompts. */
        static final String CODING_ENVIRONMENT = "codingEnvironment";
        /** A folder with copies of the plan's files as they were when the build passed, kept while a design fix is tried; "" = none. */
        static final String CODING_GOOD = "codingGood";
        /** 1 when a design fix broke the build and was undone (the files are as they were before it). */
        static final String CODING_DESIGN_UNDONE = "codingDesignUndone";
        /** How many plans the job made (a revision or a read round makes the next): what shows a resumed pause changed something. */
        static final String CODING_PLAN_ROUNDS = "codingPlanRounds";
        /** The files the model picked for the work, one relative path per line ("" = none: new files only). */
        static final String CODING_PICKS = "codingPicks";
        /** "free" when the model judged the work open-ended (found out as it goes: no plan, no approval of one), "study" when it needs the existing code understood first (research notes before the plan), "" = plan right away. */
        /** The question the analysis asks the user, as JSON {"question", "options"}; "" when there is none. Set by analyze, cleared by ask. */
        /** "1" while the analysis waits for the result of a tool call it asked for; the tool nodes then return to it, not to the agent. */
        static final String ANALYZING = "analyzing";
        /** How many tool calls the analysis made in this turn (at most {@code MAX_ANALYSIS_TOOLS}). */
        static final String ANALYSIS_TOOLS = "analysisTools";
        static final String ASK_QUESTION = "askQuestion";
        /** The user's answer to the question, written by Agent while the graph is paused before ask. */
        static final String ASK_ANSWER = "askAnswer";
        /** How many questions the analysis asked in this turn (at most {@code MAX_ASKS}). */
        static final String ASK_ROUNDS = "askRounds";
        static final String CODING_APPROACH = "codingApproach";
        /** How the analysis told the job to go: "plan", "study" or "free"; "" when it did not say (the job then decides itself). Not reset by the job. */
        static final String CODING_ROUTE = "codingRoute";
        /** The outlines of the picked files (section id, kind, lines), made by gather for the plan. */
        static final String CODING_CODE = "codingCode";
        /** Sections the plan asked to read, as text, collected over the read rounds. */
        static final String CODING_READ = "codingRead";
        /** How many times the plan asked to read more code. */
        static final String CODING_READ_ROUNDS = "codingReadRounds";
        /** 1 while the plan asked to read and waits to be asked again (the router sends it back), else 0. */
        static final String CODING_READ_PENDING = "codingReadPending";
        /** The current plan as JSON ({@link CodingPlan#toJson()}), valid and normalized; "" while the plan asks to read. */
        static final String CODING_PLAN = "codingPlan";
        /** The user's answer to the plan: approve / revise / deny ({@link CodingGraph#APPROVE} ...); written by Agent on resume. */
        static final String CODING_DECISION = "codingDecision";
        /** The user's comment with a "revise" decision. */
        static final String CODING_COMMENT = "codingComment";
        /** What the revise step made of the comment: "question" or "change". */
        static final String CODING_REVISE_KIND = "codingReviseKind";
        /** The user's points about the plan, one per line, from the revise step: what the next plan must deal with. */
        static final String CODING_POINTS = "codingPoints";
        /** The answer to the user's question about the plan, shown with the plan when it is asked about again. */
        static final String CODING_ANSWER = "codingAnswer";
        /** Why the graph sent the job back to planning (e.g. an unplanned tool call). */
        static final String CODING_REVISION_REASON = "codingRevisionReason";
        /** The ledger of the approved plan's changes as JSON ({@code Ledger}): pending, applied or failed each. */
        static final String CODING_LEDGER = "codingLedger";
        /** What verify or the design review found to repair, as JSON: file, section and what is wrong, one per section. */
        static final String CODING_FIXES = "codingFixes";
        /** 1 once the plan's setup commands ran (a revised plan doesn't run them again). */
        static final String CODING_SETUP_DONE = "codingSetupDone";
        /** Verify attempts used in this job. */
        static final String CODING_ATTEMPTS = "codingAttempts";
        /** The output of the last verify run. */
        static final String CODING_VERIFY_OUTPUT = "codingVerifyOutput";
        /** The job's final report. */
        static final String CODING_REPORT = "codingReport";
        /** How the job ended, from the report step: "success", "failed" (some change stays or the user denied) or "unchanged" (no code was changed). */
        static final String CODING_OUTCOME = "codingOutcome";
        /** What the explain step told the user about a job that changed no code, and what they can do about it. */
        static final String CODING_EXPLANATION = "codingExplanation";
        /** The plan the user approved last (JSON); a later revision only replaces it once approved. */
        static final String CODING_APPROVED_PLAN = "codingApprovedPlan";
        /** Why the job stopped early (model or tool error, no valid plan, a failed setup command); "" while it runs. */
        static final String CODING_STOP_REASON = "codingStopReason";
        /** The job's Markdown plan file (absolute path; "" when plans aren't written, e.g. in tests). */
        static final String CODING_PLAN_FILE = "codingPlanFile";
        /** The design review's latest findings as a Markdown list ("" = none, or not reviewed yet). */
        static final String CODING_DESIGN_ISSUES = "codingDesignIssues";
        /** What the intent check said of the change once the build passed: "ok", "gap: ...", "ask: ...", "decided", "doubt: ..." or "" (not checked). */
        static final String CODING_INTENT = "codingIntent";
        /** How many design reviews ran in this job (at most {@code CodingGraph.MAX_DESIGN_ROUNDS}). */
        static final String CODING_DESIGN_ROUNDS = "codingDesignRounds";
        /** Folder with copies of the files the approved plan may change, as they were before the job (for diffs); "" = none. */
        static final String CODING_BASELINE = "codingBaseline";
        /** The trusted folder's files with their line counts, made once at the start of a job and told to every phase. */
        static final String CODING_PROJECT_MAP = "codingProjectMap";
        /** How often the job went back to the user because a fix needs a file the plan doesn't change. */
        static final String CODING_REVISIONS = "codingRevisions";
        /** What the plan check said of the plan before the user sees it: "ok", "problems: ...", "ask: ..." or "" (not checked). */
        static final String CODING_PLAN_CHECK = "codingPlanCheck";
        /** How many plan checks ran since the user last answered (at most {@code CodingGraph.MAX_PLAN_CHECKS}). */
        static final String CODING_PLAN_CHECKS = "codingPlanChecks";

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
         * Channels define how updates merge. MESSAGES and SKILL_MESSAGES append; keys not listed
         * here default to "last value wins". Strings are used for the folder and commands
         * (not Path / lists) because they serialize trivially when the state is checkpointed.
         * <p>
         * Both keep duplicates: LangGraph4j's plain appender drops a message equal to one already in
         * the list. A conversation repeats messages all the time (the same question asked again, "yes"
         * twice). Dropping them would make the state shorter than the history {@link Agent} passed in,
         * breaking the "everything after the input is new" split.
         */
        public static final Map<String, Channel<?>> SCHEMA = Map.of(
                MESSAGES, Channels.appenderWithDuplicate(ArrayList::new),
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

        /** The ledger of the files the conversation has been shown, see {@link Knowledge}. */
        String knowledge() {
            return this.<String>value(KNOWLEDGE).orElse("");
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

        public String codingGood() {
            return text(CODING_GOOD);
        }

        public boolean codingDesignUndone() {
            return number(CODING_DESIGN_UNDONE) > 0;
        }

        public int codingPlanRounds() {
            return number(CODING_PLAN_ROUNDS);
        }

        public boolean analyzing() {
            return !text(ANALYZING).isBlank();
        }

        public int analysisTools() {
            return number(ANALYSIS_TOOLS);
        }

        public String askQuestion() {
            return text(ASK_QUESTION);
        }

        public String askAnswer() {
            return text(ASK_ANSWER);
        }

        public int askRounds() {
            return number(ASK_ROUNDS);
        }

        /** How the analysis told the job to go ("plan", "study", "free"), or "". */
        public String codingRoute() {
            return text(CODING_ROUTE);
        }

        /** Whether the work is open-ended: the job lets the model work with the tools instead of planning changes first. */
        public boolean codingFree() {
            return "free".equals(text(CODING_APPROACH));
        }

        /** Whether the work needs the existing code understood first: a research step writes notes before the plan. */
        public boolean codingStudy() {
            return "study".equals(text(CODING_APPROACH));
        }

        /** The picked files, relative, in the order the model named them. */
        public List<String> codingPicks() {
            return text(CODING_PICKS).lines().filter(line -> !line.isBlank()).toList();
        }

        public String codingCode() {
            return text(CODING_CODE);
        }

        public String codingRead() {
            return text(CODING_READ);
        }

        public int codingReadRounds() {
            return number(CODING_READ_ROUNDS);
        }

        public int codingReadPending() {
            return number(CODING_READ_PENDING);
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

        public String codingReviseKind() {
            return text(CODING_REVISE_KIND);
        }

        public String codingPoints() {
            return text(CODING_POINTS);
        }

        public String codingAnswer() {
            return text(CODING_ANSWER);
        }

        public String codingRevisionReason() {
            return text(CODING_REVISION_REASON);
        }

        /** The record of the approved plan's changes; empty before the approval. */
        Ledger codingLedger() {
            return Ledger.parse(text(CODING_LEDGER));
        }

        public String codingFixes() {
            return text(CODING_FIXES);
        }

        public int codingSetupDone() {
            return number(CODING_SETUP_DONE);
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

        public String codingIntent() {
            return text(CODING_INTENT);
        }

        public String codingOutcome() {
            return text(CODING_OUTCOME);
        }

        public String codingExplanation() {
            return text(CODING_EXPLANATION);
        }

        /** The plan the user approved last, or empty before the first approval. */
        public Optional<CodingPlan> codingApprovedPlan() {
            String json = text(CODING_APPROVED_PLAN);
            return json.isBlank() ? Optional.empty() : Optional.of(CodingPlan.parse(json));
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

        public String codingBaseline() {
            return text(CODING_BASELINE);
        }

        public String codingProjectMap() {
            return text(CODING_PROJECT_MAP);
        }

        public int codingRevisions() {
            return number(CODING_REVISIONS);
        }

        public String codingPlanCheck() {
            return text(CODING_PLAN_CHECK);
        }

        public int codingPlanChecks() {
            return number(CODING_PLAN_CHECKS);
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
         * last message. A coding job makes no tool calls of the model's; its {@code startCodingJob} call is one round.
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
         * came in since the user's last message: in the conversation, or in any skill of this turn (their
         * transcript is {@link #SKILL_MESSAGES}, which a run starts empty, so all of it belongs to this turn). A coding
         * job reads no web: its model has no tools. Until the next message, file changes
         * need approval and trusted commands don't count as trusted, also after the job or skill that
         * read the page has ended.
         */
        boolean webContentInTurn(ToolRegistry tools) {
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
         * (the conversation's and the skills' transcripts). Skills' tool steps use it too.
         */
        public boolean needsApproval(ToolRegistry tools, ToolExecutionRequest call) {
            return tools.needsApproval(call, trustedCommands(), webContentInTurn(tools));
        }

        /** Whether the call is a risky one that may run without asking (a trusted command, no web content). */
        boolean isTrusted(ToolRegistry tools, ToolExecutionRequest call) {
            return tools.isTrusted(call, trustedCommands(), webContentInTurn(tools));
        }

        /**
         * Tool calls of the latest AI message that have no result message yet.
         * This is how every node knows "what is still to do": results are matched to calls by id.
         */
        public List<ToolExecutionRequest> unansweredToolCalls() {
            return unansweredIn(messages());
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
    private final CodingRedirect codingRedirect = new CodingRedirect();
    // Decides at the start of a turn what happens with the message; null when the analysis is off (see Agent#triage)
    private final Analyzer analyzer;
    // Summary memory budgets (estimated tokens): summarize when the unsummarized part exceeds
    // summaryTrigger, keeping about summaryRecent of the newest messages verbatim
    private final int summaryTrigger;
    private final int summaryRecent;
    private final int incrementalThreshold;
    // The loaded skills (one subgraph each; the coding job is the built-in skill "coding"), their kinds,
    // and skill name by the tool that starts it
    private final SkillLoader.Loaded skills;
    private final Map<String, NodeKind> kinds;
    private final Map<String, String> skillsByTool;
    // What the skill builder checks drafts with and saves them to
    private final SkillLoader skillLoader;

    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing) {
        this(model, toolCalls, tracing, 6000, 2000, 20);
    }

    /** Without skills (so without coding jobs either), and a skill builder that has no folder to save to. */
    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, int summaryTrigger, int summaryRecent) {
        this(model, toolCalls, tracing, summaryTrigger, summaryRecent, 20);
    }

    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, int summaryTrigger, int summaryRecent,
               int incrementalThreshold) {
        this(model, toolCalls, tracing, summaryTrigger, summaryRecent, incrementalThreshold,
                new SkillLoader.Loaded(List.of(), Map.of()),
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
               int incrementalThreshold, SkillLoader.Loaded skills, SkillLoader loader) {
        this(model, toolCalls, tracing, summaryTrigger, summaryRecent, incrementalThreshold, skills, loader, false);
    }

    /** @param triage whether every turn starts by asking the model what to do with the message ({@link Analyzer}) */
    AgentGraph(ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, int summaryTrigger, int summaryRecent,
               int incrementalThreshold, SkillLoader.Loaded skills, SkillLoader loader, boolean triage) {
        this.analyzer = triage ? new Analyzer(model) : null;
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
        this.incrementalThreshold = incrementalThreshold;
    }

    /**
     * Compiled with an in-memory checkpointer, pausing before the approval subgraph, before the skill
     * builder's review ({@link #SKILL_REVIEW_PAUSE}) and before every skill's pause nodes
     * ({@link #skillPauses}), a coding job's plan approval among them.
     */
    CompiledGraph<State> compile() throws GraphStateException {
        List<String> interrupts = new ArrayList<>(List.of(APPROVAL, SKILL_REVIEW_PAUSE, ASK_PAUSE));
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
        List<ChatMessage> compacted = SummaryMemory.compactEarlierTurns(messages);
        int cutoff = SummaryMemory.cutoff(compacted, current.covered(), summaryTrigger, summaryRecent);
        if (cutoff <= current.covered()) return Map.of();
        // Incremental update when the conversation is small: cheaper than a full summary.
        if (current.isEmpty() || compacted.size() - current.covered() < incrementalThreshold) {
            String text = SummaryMemory.incrementalUpdate(model, current.text(), compacted.subList(current.covered(), cutoff));
            if (text.isBlank()) return Map.of();
            return Map.of(State.SUMMARY, text, State.SUMMARY_COVERED, cutoff);
        }
        String text = SummaryMemory.summarize(model, current.text(), compacted.subList(current.covered(), cutoff));
        if (text.isBlank()) return Map.of();
        return Map.of(State.SUMMARY, text, State.SUMMARY_COVERED, cutoff);
    }

    /**
     * What the agent step sends as the conversation: the older messages replaced by the summary, if any, and
     * the earlier turns compacted (old tool results and written file contents shortened), so the model server
     * doesn't re-read them on every request; the current turn is sent in full. {@link #triage} asks its question
     * with exactly these messages first, so the server's cache serves both.
     */
    private List<ChatMessage> agentMessages(State state) {
        return new ArrayList<>(ContextGroom.apply(SummaryMemory.view(state.messages(), state.summary())));
    }

    /** What the analysis may name from its node: read from the {@link Offers} annotation of {@link #analyze}. */
    private static final Offers OFFERS = offersOf("analyze");

    /** The edges the analysis may name, with the job edges only when {@code jobs}. */
    static List<Offers.Edge> offeredEdges(boolean jobs) {
        return Arrays.stream(OFFERS.edges()).filter(e -> jobs || !e.name().startsWith("job_")).toList();
    }

    private static Offers offersOf(String method) {
        for (java.lang.reflect.Method m : AgentGraph.class.getDeclaredMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(Offers.class)) return m.getAnnotation(Offers.class);
        }
        throw new IllegalStateException("no @Offers on " + method);
    }

    /** How much of the conversation the analysis reads: about 2,500 tokens. */
    static final int ANALYSIS_CHARS = 8_000;

    /**
     * The end of the conversation that the analysis gets: the newest messages that fit {@link #ANALYSIS_CHARS} (older tool results
     * shortened as for the agent), starting at a user message, and always the user's latest message with what follows it. The analysis only
     * chooses a route, and it was sent the whole conversation (132,000 characters: seven minutes for the server to read, with the
     * single slot busy with other requests).
     */
    static List<ChatMessage> analysisWindow(List<ChatMessage> all) {
        List<ChatMessage> groomed = ContextGroom.apply(all);
        int lastUser = -1;
        int previousUser = -1;
        for (int i = groomed.size() - 1; i >= 0 && previousUser < 0; i--) {
            if (!(groomed.get(i) instanceof UserMessage)) continue;
            if (lastUser < 0) lastUser = i;
            else previousUser = i;
        }
        if (lastUser < 0) return groomed;
        // Whatever the previous turn said is clipped, never cut away: "retry", "continue" and "go on" only mean something with it. (A window
        // that was only the user's latest message, because the last answer was long, made the analysis ask "retry what?" twice.)
        List<ChatMessage> clipped = new ArrayList<>(groomed.size());
        for (int i = 0; i < groomed.size(); i++) {
            clipped.add(i < lastUser ? clip(groomed.get(i)) : groomed.get(i));
        }
        int start = previousUser >= 0 ? previousUser : lastUser;
        int size = 0;
        for (int i = clipped.size() - 1; i >= start; i--) size += SummaryMemory.estimateTokens(clipped.get(i)) * 4;
        for (int i = start - 1; i >= 0 && size <= ANALYSIS_CHARS; i--) {
            size += SummaryMemory.estimateTokens(clipped.get(i)) * 4;
            if (size > ANALYSIS_CHARS) break;
            if (clipped.get(i) instanceof UserMessage) start = i;
        }
        return new ArrayList<>(clipped.subList(start, clipped.size()));
    }

    /**
     * The window with the coding jobs of the conversation in front (what each one was and how it ended, {@link JobLedger}), as the agent
     * is told them: "retry" is about the last job, and its goal and outcome are not always in the window.
     */
    static List<ChatMessage> withJobs(List<ChatMessage> window, List<ChatMessage> all) {
        String jobs = JobLedger.note(all);
        if (jobs.isEmpty()) return window;
        List<ChatMessage> out = new ArrayList<>(window.size() + 1);
        out.add(UserMessage.from(jobs));
        out.addAll(window);
        return out;
    }

    /** The most characters of one earlier message the analysis reads. */
    static final int ANALYSIS_MESSAGE_CHARS = 1_500;

    private static ChatMessage clip(ChatMessage message) {
        if (message instanceof AiMessage ai && !ai.hasToolExecutionRequests() && ai.text() != null && ai.text().length() > ANALYSIS_MESSAGE_CHARS) {
            return AiMessage.from(ai.text().substring(0, ANALYSIS_MESSAGE_CHARS) + " [… cut]");
        }
        if (message instanceof UserMessage user && user.hasSingleText() && user.singleText().length() > ANALYSIS_MESSAGE_CHARS) {
            return UserMessage.from(user.singleText().substring(0, ANALYSIS_MESSAGE_CHARS) + " [… cut]");
        }
        return message;
    }

    /**
     * Once per turn, before the agent: asks the model what to do with the user's latest message ({@link Analyzer}, {@link AnalyzeMessage}):
     * it returns the goal, written so that it stands alone, and where to go next. The edges it may name are the ones of the annotation
     * below (the job edges only when the coding skill is loaded). {@code agent}: nothing happens here and the agent takes the turn.
     * {@code job_*}: the {@code startCodingJob} call is added with the goal, which {@link #route} sends to the coding skill, and the way the
     * job goes (plan, study, free) is kept in the state for {@code pickFiles}. Nothing happens (no model call) with the analysis switched
     * off or when the turn doesn't start with a user message; two unusable answers in a row leave the turn to the agent.
     */
    @Offers(tools = {"findParts", "searchFiles", "readSection", "outline"}, edges = {
            @Offers.Edge(name = "agent", does = "one model call, all tools, answers in text",
                    useWhen = "question, explanation, example, file handling (list, find, delete, move)"),
            @Offers.Edge(name = "job_plan", does = "coding job: choose files, plan, approval, edit, build",
                    useWhen = "the request names the class or method and what to change"),
            @Offers.Edge(name = "job_study", does = "coding job: read code first, then plan, approval, edit, build",
                    useWhen = "the change touches several places or hooks into existing code"),
            @Offers.Edge(name = "job_free", does = "coding job without a plan, works with tools, then build",
                    useWhen = "cause unknown, something fails, commands must be run")})
    @Node("analyze")
    Map<String, Object> analyze(State state) {
        List<ChatMessage> all = state.messages();
        // After a tool call of its own the last message is the result, not the user's message
        if (analyzer == null || all.isEmpty() || !(all.getLast() instanceof UserMessage || state.analyzing())) return Map.of();
        boolean jobs = skillsByTool.containsKey(CodingGraph.START_TOOL);
        List<Offers.Edge> edges = offeredEdges(jobs);
        boolean mayAsk = state.askRounds() < MAX_ASKS;
        List<dev.langchain4j.agent.tool.ToolSpecification> tools = state.analysisTools() >= MAX_ANALYSIS_TOOLS ? List.of()
                : toolCalls.tools().specifications(Set.of(OFFERS.tools())::contains);
        Analysis.Offered offered = new Analysis.Offered(edges.stream().map(Offers.Edge::name).collect(Collectors.toSet()),
                tools.stream().map(dev.langchain4j.agent.tool.ToolSpecification::name).collect(Collectors.toSet()), mayAsk);
        Optional<Analysis> analysis = analyzer.analyze(AnalyzeMessage.compose(edges, tools, mayAsk, withJobs(analysisWindow(all), all)), offered);
        if (analysis.isEmpty()) {
            AgentLog.current().line("⇢ analysis: no usable answer, the agent takes it");
            return Map.of();
        }
        if (analysis.get().next() instanceof Analysis.Tool tool) {
            AgentLog.current().line("⇢ analysis: calls " + tool.name() + " " + AgentLog.oneLine(tool.args().toString(), 200));
            // The call keeps the id the model gave it, so its result answers it
            ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id(tool.id() == null || tool.id().isBlank() ? "call_" + java.util.UUID.randomUUID().toString().replace("-", "") : tool.id())
                    .name(tool.name()).arguments(tool.args().toString()).build();
            return Map.of(State.MESSAGES, AiMessage.from(List.of(call)), State.ANALYZING, "1", State.ANALYSIS_TOOLS, state.analysisTools() + 1);
        }
        if (analysis.get().next() instanceof Analysis.Ask ask) {
            AgentLog.current().line("⇢ analysis: asks the user · " + AgentLog.oneLine(ask.question(), 300));
            return Map.of(State.ASK_QUESTION, Analysis.questionJson(ask), State.ASK_ANSWER, "", State.ANALYZING, "");
        }
        String edge = ((Analysis.Edge) analysis.get().next()).name();
        AgentLog.current().line("⇢ analysis: " + edge + " · goal: " + AgentLog.oneLine(analysis.get().goal(), 300));
        if (!edge.startsWith("job_")) return Map.of(State.ASK_QUESTION, "", State.ANALYZING, "");
        String goal = analysis.get().goal();
        return Map.of(State.MESSAGES, AiMessage.from(List.of(codingRedirect.startCallForGoal(goal))),
                State.FACTS, State.encodeFacts(Objective.with(state.facts(), goal)),
                State.CODING_ROUTE, edge.substring("job_".length()), State.ASK_QUESTION, "", State.ANALYZING, "");
    }

    /** After the tools ran: back to the analysis when it asked for them, else to the agent. */
    @Router(from = "tools", routes = {@Route(on = "analyze", to = "analyze"), @Route(on = "agent", to = "agent")})
    String afterTools(State state) {
        return state.analyzing() ? "analyze" : "agent";
    }

    @Router(from = "trusted", routes = {@Route(on = "analyze", to = "analyze"), @Route(on = "agent", to = "agent")})
    String afterTrusted(State state) {
        return state.analyzing() ? "analyze" : "agent";
    }

    @Router(from = "approval", routes = {@Route(on = "analyze", to = "analyze"), @Route(on = "agent", to = "agent")})
    String afterApproval(State state) {
        return state.analyzing() ? "analyze" : "agent";
    }

    /** After the analysis: a question for the user goes to the pause {@link #ask}, anything else to the agent. */
    @Router(from = "analyze", routes = {
            @Route(on = "ask", to = "ask"),
            @Route(on = "agent", to = "agent")})
    String afterAnalyze(State state) {
        return state.askQuestion().isBlank() ? "agent" : "ask";
    }

    /**
     * The graph pauses before this node; {@link Agent} shows the question of the analysis to the user and writes the answer into the
     * state. Here the question and the answer become part of the conversation (an assistant message and a user message), so the analysis,
     * which runs again after this node, reads them like any other turn of the conversation. A dismissed question is answered with
     * "decide yourself".
     */
    @Node("ask")
    Map<String, Object> ask(State state) {
        String question = Analysis.questionOf(state.askQuestion());
        String answer = state.askAnswer().isBlank() ? "(no answer: decide yourself)" : state.askAnswer();
        return Map.of(State.MESSAGES, List.of(AiMessage.from(question), UserMessage.from(answer)),
                State.ASK_QUESTION, "", State.ASK_ANSWER, "", State.ASK_ROUNDS, state.askRounds() + 1);
    }
    /** Everything the user wrote in the conversation: a name they used themselves is not an invention of the model. */
    private static String userWords(List<ChatMessage> messages) {
        StringBuilder words = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage user) words.append(user.hasSingleText() ? user.singleText() : String.valueOf(user.contents())).append('\n');
        }
        return words.toString();
    }

    private static String latestUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) return user.hasSingleText() ? user.singleText().strip() : String.valueOf(user.contents());
        }
        return "";
    }

    /**
     * Asks the model for the next step: an answer or tool calls. After {@link #MAX_AGENT_ROUNDS} rounds
     * of tool calls in this turn it is asked once more without tools, and any calls it makes anyway are
     * stripped, so {@link #route} sees a plain answer and ends the turn with nothing left open.
     * A turn that {@link #triage} started as a coding job reaches here with the job's call open: there is
     * nothing to ask then.
     */
    @Node("agent")
    Map<String, Object> callModel(State state) {
        // A call that the analysis added (the job's start, or a tool it wants the result of) is open: nothing to ask, the router sends it on
        if (!state.unansweredToolCalls().isEmpty()) return Map.of();
        // A skill that has told the user itself (a job that succeeded, a finish with `say`) leaves nothing to word again: no model call
        if (state.messages().getLast() instanceof AiMessage last && !last.hasToolExecutionRequests()) return Map.of();
        boolean lastRound = state.agentToolRounds() >= MAX_AGENT_ROUNDS;
        // The conversation + the tool list; the model decides whether to answer or call tools
        List<ChatMessage> messages = agentMessages(state);
        // Files that changed since the conversation saw them (in the editor, by a job): one note at the end, said once.
        // Added at the end and not stored, so the start of the prompt stays as the model server has it cached.
        List<String> changed = Knowledge.changes(state.knowledge(), state.workFolder());
        List<String> notes = new ArrayList<>();
        if (!changed.isEmpty()) {
            notes.add("Files changed since you looked at them, so what you saw of them is out of date (read again only what you need):\n- "
                    + String.join("\n- ", changed));
        }
        // The jobs of earlier turns; a job of this turn has just reported, and its report is the last message
        String jobs = jobStartedThisTurn(state.messages()) ? "" : JobLedger.note(state.messages());
        if (!jobs.isEmpty()) notes.add(jobs);
        if (!notes.isEmpty()) messages.add(UserMessage.from("Notes from the program (not from the user):\n\n" + String.join("\n\n", notes)));
        // The tool list is sent on the last round too, although no call will run: the chat template puts the
        // tools at the very start of the prompt, and a request without them would make the model server
        // re-read the whole conversation instead of reusing what it has cached (the next turn then started cold:
        // 16,000 tokens at 90 a second, three minutes).
        ChatRequest.Builder request = ChatRequest.builder().toolSpecifications(toolCalls.tools().specifications());
        if (lastRound) {
            // Only this request needs it, so it isn't stored in the state
            messages.add(UserMessage.from("You have used " + MAX_AGENT_ROUNDS + " rounds of tool calls in this turn."
                    + " Answer now without tool calls: say what is done and what is left."));
        }
        AiMessage reply = model.chat(request.messages(messages).build()).aiMessage(); // blocks until Lemonade answers
        if (!lastRound && announcesWithoutActing(reply)) reply = afterAnnouncement(state, request, messages, reply);
        if (lastRound && reply.hasToolExecutionRequests()) {
            // Calls despite no tools offered: keep only the text, so no call stays open in the conversation
            String text = reply.text() == null || reply.text().isBlank() ? TOO_MANY_ROUNDS : reply.text();
            reply = AiMessage.from(text);
        } else if (jobStartedThisTurn(state.messages()) && changesFiles(reply)) {
            // A job ran in this turn: code changes go through jobs. The agent patching around a job that stopped (the log
            // showed it) left files corrupted, so it tells the user instead
            AgentLog.current().line("⛔ the reply changes files after a coding job: not allowed in this turn");
            reply = AiMessage.from(noChangesAfterJob(state.messages()));
        } else if (skillsByTool.containsKey(CodingGraph.START_TOOL) && !jobStartedThisTurn(state.messages())) {
            // Changing existing code by hand is coding work: its calls become the start of a coding job, which
            // route() sends to skill-coding, so the user never has to ask for a job (see CodingRedirect)
            AiMessage original = reply;
            Optional<ToolExecutionRequest> job = codingRedirect.startCall(state.messages(), original, state.workFolder());
            if (job.isPresent()) {
                AgentLog.current().line("↪ the reply changes existing code by hand: starting a coding job instead");
                reply = AiMessage.builder().text(original.text()).toolExecutionRequests(List.of(job.get())).build();
            }
        }
        if (!reply.hasToolExecutionRequests() && reply.text() != null && !lastRound) {
            // A name in the answer that is nowhere in the project is said so under the answer, where the user and the model read it
            String note = NameCheck.note(reply.text(), userWords(state.messages()), state.workFolder(), net.rka.ai.tools.code.java.JavaWorkspaces.shared());
            if (!note.isEmpty()) reply = AiMessage.from(reply.text() + "\n\n" + note);
        }
        if (!changed.isEmpty()) {
            return Map.of(State.MESSAGES, reply, State.KNOWLEDGE, Knowledge.refreshed(state.knowledge(), state.workFolder()));
        }
        return Map.of(State.MESSAGES, reply); // appended to the history by the MESSAGES channel
    }

    /**
     * A reply that promised an action and stopped ("Let me make these changes to ChatWindow.java:"), a local
     * model's habit that a conversation full of it makes worse. Where the text names a source file, the promised
     * work is a coding job and starts as one, with no more tries on the model. Otherwise the model is asked once
     * more (the request starts with the same messages, so the model server reuses its cache) and the nudge isn't
     * stored: the announcement keeps its text and takes the calls the model now makes, or the model's new answer
     * replaces it; an answer that still announces a change to a source file starts the job.
     */
    private AiMessage afterAnnouncement(State state, ChatRequest.Builder request, List<ChatMessage> messages, AiMessage reply) {
        Optional<AiMessage> job = jobFor(state, reply);
        if (job.isPresent()) return job.get();
        AgentLog.current().line("↺ the reply announces an action without making the call: asking once more");
        List<ChatMessage> again = new ArrayList<>(messages);
        again.add(reply);
        again.add(UserMessage.from(ANNOUNCED_NUDGE));
        AiMessage next = model.chat(request.messages(again).build()).aiMessage();
        if (next.hasToolExecutionRequests()) {
            return AiMessage.builder().text(reply.text()).toolExecutionRequests(next.toolExecutionRequests()).build();
        }
        if (next.text() == null || next.text().isBlank()) return reply;
        return announcesWithoutActing(next) ? jobFor(state, next).orElse(next) : next;
    }

    /**
     * Whether a coding job already started in this turn (a {@code startCodingJob} call after the user's last
     * message). The graph starts a job from the model's replies only once per turn: a job that ended without
     * finishing says why in its report, and starting the same job again from the reply that follows would loop
     * (each job ends the same way, then the model announces the same change again).
     */
    static boolean jobStartedThisTurn(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof UserMessage) return false;
            if (message instanceof AiMessage ai && ai.toolExecutionRequests().stream()
                    .anyMatch(c -> CodingGraph.START_TOOL.equals(c.name()))) {
                return true;
            }
        }
        return false;
    }

    /** The reply with a {@code startCodingJob} call in place of its announcement, when it names a source file. */
    private Optional<AiMessage> jobFor(State state, AiMessage announcement) {
        if (!skillsByTool.containsKey(CodingGraph.START_TOOL) || jobStartedThisTurn(state.messages())) return Optional.empty();
        return codingRedirect.startCallForAnnouncement(state.messages(), announcement).map(call -> {
            AgentLog.current().line("↪ the reply announces a change to a source file: starting a coding job instead");
            return AiMessage.builder().text(announcement.text()).toolExecutionRequests(List.of(call)).build();
        });
    }

    /** What the agent says instead of changing files itself after a coding job (see {@link #changesFiles}). */
    static final String NO_CHANGES_AFTER_JOB = "A coding job ran in this turn, so I'm not changing files myself.";

    /** The program's reply with the job's outcome (the first line of its report), which the chat does not otherwise show. */
    private static String noChangesAfterJob(List<ChatMessage> messages) {
        String outcome = "";
        for (ChatMessage message : messages) {
            if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage result && CodingGraph.START_TOOL.equals(result.toolName())) {
                outcome = result.text().lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
            }
        }
        return NO_CHANGES_AFTER_JOB + (outcome.isEmpty() ? "" : " The job's outcome: " + outcome)
                + " Tell me what you want next (a new request starts a new job).";
    }

    /** Whether the reply calls a tool that changes files (the memory tools only change the conversation's facts). */
    private boolean changesFiles(AiMessage reply) {
        return reply.toolExecutionRequests().stream().anyMatch(c -> toolCalls.tools().writesFiles(c.name())
                && !Set.of("remember", "forget").contains(c.name()));
    }

    /** What the model is told when its reply promised an action and ended (see {@link #announcesWithoutActing}). */
    static final String ANNOUNCED_NUDGE = "You said what you would do next but made no tool call. Make the call now."
            + " If there is nothing left to do, answer again without ending on a colon.";

    /**
     * Whether the reply is a plain answer that ends on a colon ("Let me make these changes:"): a promise of
     * what comes next, from a local model that stopped before it. A finished answer ends with its content,
     * not with an introduction.
     */
    static boolean announcesWithoutActing(AiMessage reply) {
        return !reply.hasToolExecutionRequests() && reply.text() != null && reply.text().stripTrailing().endsWith(":");
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
     * same as without skills. The coding job is the built-in skill {@code coding}: pick files, plan, the
     * user's plan approval (the pause before {@link #PLAN_APPROVAL}), the changes, verify, fix, and the report
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

