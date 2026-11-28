package net.rka.ai.agent.skills;

import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.CodingGraph;
import net.rka.ai.agent.skills.SkillFile.NodeSpec;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The coding job's node kinds: one per step of the job's pipeline, implemented by {@link CodingGraph} (which keeps the
 * job's state keys, plan files and design guides). The built-in skill {@code coding} ({@code coding.json}) wires them
 * into the job; a user's {@code coding.json} may wire, limit and prompt them differently, but every kind enforces its
 * own rules whatever the wiring.
 *
 * <pre>
 *   kind                    | fields   | outcomes (+ stopped)        | method
 *   ------------------------+----------+-----------------------------+---------------------------------
 *   coding.environment      |          | done                        | environment (goal, fresh keys, project map)
 *   coding.pickFiles        | prompt*  | done, study, free           | pickFiles (model: the files, and how the work goes)
 *   coding.gather           |          | done                        | gather (graph: their outlines)
 *   coding.plan             | prompt*  | approval, read              | plan (model: the plan; graph: validates, reads)
 *   coding.planApproval     |          | approve, revise, deny       | planApproval (⏸ pauses before)
 *   coding.revise           | prompt   | change, question            | revise (reads the comment on a plan)
 *   coding.setup            |          | done                        | setup (graph: the approved commands)
 *   coding.edit             | prompt*  | edit, verify                | edit (model: a section's new text; graph applies)
 *   coding.verify           |          | passed, fix, failing        | verify (graph: the build; failures to sections)
 *   coding.fix              | prompt*  | verify, revise              | fix (model: a section's new text; graph applies)
 *   coding.planCheck        | prompt   | ok, revise, ask             | planCheck (graph: classes nothing uses; model: approach and coverage)
 *   coding.intent           | prompt   | done, gap, ask              | intent (model: does the change do what was asked?)
 *   coding.designReview    | prompt*  | fix, done                   | designReview (model: issues in the changed code)
 *   coding.report           |          | done, unchanged             | report (graph: the report and how the job ended)
 *   coding.explain          | prompt   | done                        | explain (model: why no code changed, what to do)
 *   coding.answer           |          | none: ends the skill        | answer (answers the call)
 *   (* required: the step's instructions to the model, sent with its material in the user message)
 * </pre>
 * The model never calls a tool inside a job: the graph reads, writes and builds, the model answers with text of a known
 * shape. Each kind routes to {@link NodeKind#STOPPED} once {@code codingStopReason} is set, which the built-in file maps
 * to {@code report}. The limits come from the skill file's {@code limits} ({@link CodingGraph.Limits}); each kind
 * declares the ones it reads ({@link NodeKind#limitNames}): plan {@code maxReadRounds} and {@code maxPlanAttempts}, edit
 * {@code maxEditAttempts}, verify {@code maxVerifyAttempts}, designReview {@code maxDesignRounds}. A skill with coding
 * kinds may set {@code maxSteps} up to 1000. The validator also requires that setup, edit, fix and verify are only
 * reached through {@code coding.planApproval}'s {@code approve} (from start and from every plan node), and that
 * {@code coding.environment} comes before every other coding node ({@link SkillValidator}); at run time they only work
 * by the plan the user approved last.
 */
public final class CodingKinds {

    public static final String ENVIRONMENT = "coding.environment";
    public static final String PICK_FILES = "coding.pickFiles";
    public static final String GATHER = "coding.gather";
    public static final String PLAN = "coding.plan";
    /** Reads the user's comment on a plan before the plan is made again: a question is answered, a change becomes a list of points. */
    public static final String REVISE = "coding.revise";
    /** The plan approval: the graph pauses before it, {@code Agent} asks the user about the plan. */
    public static final String PLAN_APPROVAL = "coding.planApproval";
    /** Runs the approved setup commands; reachable only through {@link #PLAN_APPROVAL}'s {@link #APPROVE}. */
    public static final String SETUP = "coding.setup";
    /** Makes the approved changes; reachable only through {@link #PLAN_APPROVAL}'s {@link #APPROVE}. */
    public static final String EDIT = "coding.edit";
    /** Runs the plan's verify commands; reachable only through {@link #PLAN_APPROVAL}'s {@link #APPROVE}. */
    public static final String VERIFY = "coding.verify";
    /** After free work (no plan): runs the folder's own build command, with no model; the result is the variable {@code check}. */
    public static final String CHECK = "coding.check";
    /** Repairs what verify or the design review found; reachable only through {@link #PLAN_APPROVAL}'s {@link #APPROVE}. */
    public static final String FIX = "coding.fix";
    /** Once the build passes: does the change do what the user asked? One check, from the request and the diff. */
    public static final String INTENT = "coding.intent";
    /** Checks the plan before the user sees it: a class nothing uses, the wrong approach, a point of the request left out. */
    public static final String PLAN_CHECK = "coding.planCheck";
    public static final String DESIGN_REVIEW = "coding.designReview";
    /** Writes the job's report and says how it ended: {@code done}, or {@code unchanged} when no code was changed. */
    public static final String REPORT = "coding.report";
    /** Tells the user why a job changed no code and asks what they want to do; reachable only through {@link #REPORT}'s {@code unchanged}. */
    public static final String EXPLAIN = "coding.explain";
    /** Ends the job: the report (and the explanation) is the answer to the call. */
    public static final String ANSWER = "coding.answer";
    /** The plan approval's outcome after the user approved the plan. */
    public static final String APPROVE = CodingGraph.APPROVE;

    private static final String PROMPT = "prompt";
    private static final String PREFIX = "coding.";

    private CodingKinds() {
    }

    /** Whether the kind is one of these ({@code coding.*}), e.g. to show a node as a coding job's phase. */
    public static boolean isCodingKind(String kind) {
        return kind != null && kind.startsWith(PREFIX);
    }

    /**
     * The coding kinds as the skill builder's draft request teaches them, when it changes a skill that uses
     * them (the built-in {@code coding} or a user's copy): each kind with its field and outcomes and the limit
     * it reads, from the kinds themselves, plus the rules of a coding skill: its {@code maxSteps} cap
     * ({@value SkillValidator#MAX_CODING_STEPS}), that the built-in's tool is pinned, and the wiring rules.
     */
    public static String builderReference() {
        StringBuilder out = new StringBuilder("""
                ## Coding kinds

                The skill to change runs a coding job with these kinds (no others besides the ones above). They \
                keep the job's data themselves: they have no "output", write no variables and their "prompt" is \
                sent as it is (no templates).

                  kind                    | fields   | outcomes (+ stopped)        | limit it reads
                  ------------------------+----------+-----------------------------+------------------------
                """);
        for (NodeKind kind : all(null).values()) { // only the descriptions are read: no job runs
            Kind coding = (Kind) kind;
            out.append(String.format("  %-23s | %-8s | %-27s | %s\n", coding.name(), coding.prompted ? "prompt*" : "",
                    coding.outcomes.isEmpty() ? "none: ends the skill" : String.join(", ", coding.outcomes),
                    String.join(", ", coding.limitNames)));
        }
        out.append("""
                  (* required: the step's instructions to the model)

                Rules for a skill with coding kinds (they replace the limits above where they differ):
                - "limits": "maxSteps" may be 1 to %d: a coding job visits a node per model turn of each step, so \
                keep the value of the current file (never lower it unless the description asks for it). The \
                limits the kinds read (in the table) are each 1 to %d; keep the current values unless asked.
                - "tool": the built-in coding skill's tool is pinned: keep "tool" exactly as in the current file, \
                or leave it out; never change it.
                - Every path from "start" to another coding node passes through a coding.environment node, and \
                coding.setup, coding.edit, coding.fix and coding.verify are only reached through a \
                coding.planApproval node's "approve" outcome (from start and from every coding.plan node), so \
                nothing changes by a plan the user didn't approve.
                - A stopped outcome usually goes to the report node, so the job always answers with a report.
                """.formatted(SkillValidator.MAX_CODING_STEPS, SkillValidator.MAX_EXTRA_LIMIT));
        return out.toString();
    }

    /** How a kind's node runs: one of the job's methods, given the node's prompt ({@code null} for kinds without one). */
    @FunctionalInterface
    private interface Step {
        Map<String, Object> run(CodingGraph job, State state, String prompt);
    }

    /** Which outcome a node of the kind ended with: one of the job's routers. */
    @FunctionalInterface
    private interface Router {
        String route(State state, CodingGraph.Limits limits);
    }

    /**
     * The coding kinds by name.
     *
     * @param setup what every job of the app runs with: environment summary, command timeout, plan files, design guides
     */
    public static Map<String, NodeKind> all(CodingGraph.Setup setup) {
        List<NodeKind> kinds = List.of(
                new Kind(ENVIRONMENT, List.of("done"), false, setup,
                        (job, state, prompt) -> job.environment(state), (state, limits) -> "done") {
                    @Override
                    public Optional<String> otherCallsAnswer() {
                        return Optional.of(CodingGraph.NOT_RUN); // "a coding job started", as before skills
                    }
                },
                new Kind(PICK_FILES, List.of("done", "study", "free"), true, setup,
                        CodingGraph::pickFiles, (state, limits) -> CodingGraph.afterPickFiles(state)),
                new Kind(GATHER, List.of("done"), false, setup,
                        (job, state, prompt) -> job.gather(state), (state, limits) -> "done"),
                new Kind(PLAN, List.of("approval", "read"), true, setup,
                        CodingGraph::plan, (state, limits) -> CodingGraph.afterPlan(state))
                        .withLimits(CodingGraph.Limits.READ_ROUNDS, CodingGraph.Limits.PLAN_ATTEMPTS),
                new Kind(REVISE, List.of(CodingGraph.CHANGE, CodingGraph.QUESTION), true, setup,
                        CodingGraph::revise, (state, limits) -> CodingGraph.afterRevise(state)),
                new Kind(PLAN_CHECK, List.of("ok", "revise", "ask"), true, setup,
                        CodingGraph::planCheck, (state, limits) -> CodingGraph.afterPlanCheck(state)),
                new Kind(PLAN_APPROVAL, List.of(CodingGraph.APPROVE, CodingGraph.REVISE, CodingGraph.DENY), false, setup,
                        (job, state, prompt) -> job.planApproval(state), (state, limits) -> CodingGraph.afterPlanApproval(state)) {
                    @Override
                    public boolean pausesBefore() {
                        return true; // Agent asks the user (Approver.reviewPlan) and writes codingDecision
                    }
                },
                new Kind(SETUP, List.of("done", "replan"), false, setup,
                        (job, state, prompt) -> job.setup(state), (state, limits) -> CodingGraph.afterSetup(state)),
                new Kind(EDIT, List.of("edit", "verify"), true, setup,
                        CodingGraph::edit, (state, limits) -> CodingGraph.afterEdit(state)).withLimits(CodingGraph.Limits.EDIT_ATTEMPTS),
                new Kind(VERIFY, List.of("passed", "fix", "failing"), false, setup,
                        (job, state, prompt) -> job.verify(state), CodingGraph::afterVerify).withLimits(CodingGraph.Limits.VERIFY_ATTEMPTS),
                new Kind(CHECK, List.of("passed", "none", "failing"), false, setup,
                        (job, state, prompt) -> job.check(state), (state, limits) -> CodingGraph.afterCheck(state)).withWrites("check"),
                new Kind(FIX, List.of("verify", "revise"), true, setup,
                        CodingGraph::fix, (state, limits) -> CodingGraph.afterFix(state)),
                new Kind(INTENT, List.of("done", "gap", "ask"), true, setup,
                        CodingGraph::intent, (state, limits) -> CodingGraph.afterIntent(state)),
                new Kind(DESIGN_REVIEW, List.of("fix", "done"), true, setup,
                        CodingGraph::designReview, (state, limits) -> CodingGraph.afterDesignReview(state)).withLimits(CodingGraph.Limits.DESIGN_ROUNDS),
                new Kind(REPORT, List.of("done", "unchanged"), false, setup,
                        (job, state, prompt) -> job.report(state), (state, limits) -> CodingGraph.afterReport(state)),
                new Kind(EXPLAIN, List.of("done"), true, setup,
                        CodingGraph::explain, (state, limits) -> "done"),
                new Kind(ANSWER, List.of(), false, setup,
                        (job, state, prompt) -> job.answer(state), (state, limits) -> {
                    throw new IllegalStateException("an answer node has no outcome"); // terminal: never asked
                }));
        Map<String, NodeKind> byName = new LinkedHashMap<>();
        kinds.forEach(kind -> byName.put(kind.name(), kind));
        return byName;
    }

    /**
     * One coding kind: its outcomes and fields for the validator, and the job method and router it runs.
     * Every node builds its own {@link CodingGraph} from the skill's runtime (model, tools, limits) and
     * the app's {@link CodingGraph.Setup}.
     */
    private static class Kind implements NodeKind {
        private final String name;
        private final Set<String> outcomes;
        private final boolean prompted;
        private final CodingGraph.Setup setup;
        private final Step step;
        private final Router router;
        private final Set<String> limitNames = new LinkedHashSet<>();
        private final Set<String> writes = new LinkedHashSet<>();

        Kind(String name, List<String> outcomes, boolean prompted, CodingGraph.Setup setup, Step step, Router router) {
            this.name = name;
            this.outcomes = new LinkedHashSet<>(outcomes);
            this.prompted = prompted;
            this.setup = setup;
            this.step = step;
            this.router = router;
        }

        /** Declares the skill variables this kind's step writes (most coding kinds keep their data in the job's own state keys). */
        Kind withWrites(String... names) {
            writes.addAll(List.of(names));
            return this;
        }

        /** Declares the skill limits this kind's step or router reads ({@link #limitNames}). */
        Kind withLimits(String... names) {
            limitNames.addAll(List.of(names));
            return this;
        }

        @Override
        public Set<String> limitNames() {
            return limitNames;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> outcomes(NodeSpec node) {
            return outcomes;
        }

        /** A step that asks the model needs its {@code prompt} (non-blank text); no kind has other fields. */
        @Override
        public List<String> check(NodeSpec node, SkillContext context) {
            List<String> errors = new ArrayList<>();
            String where = "node `" + node.id() + "`: ";
            for (String field : node.fields().keySet()) {
                if (!prompted || !field.equals(PROMPT)) {
                    errors.add(where + "unknown field `" + field + "`; a " + name + " node has "
                            + (prompted ? "only `prompt`." : "no fields."));
                }
            }
            if (prompted && !(node.fields().get(PROMPT) instanceof String prompt && !prompt.isBlank())) {
                errors.add(where + "`prompt` is required: the step's instructions to the model, as text.");
            }
            return errors;
        }

        /** The job keeps its data in the coding state keys, not in the skill's variables (except what {@link #withWrites} declares). */
        @Override
        public Set<String> writes(NodeSpec node) {
            return Set.copyOf(writes);
        }

        /** The prompt is sent as it is, not rendered as a template. */
        @Override
        public Set<String> reads(NodeSpec node) {
            return Set.of();
        }

        @Override
        public NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime) {
            CodingGraph job = new CodingGraph(runtime, setup);
            String prompt = prompted ? (String) node.fields().get(PROMPT) : null;
            return (state, config) -> step.run(job, state, prompt);
        }

        @Override
        public String route(NodeSpec node, SkillRuntime runtime, State state) {
            return router.route(state, CodingGraph.Limits.of(runtime.skill().limits()));
        }
    }
}
