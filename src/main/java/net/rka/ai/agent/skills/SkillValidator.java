package net.rka.ai.agent.skills;

import net.rka.ai.agent.skills.NodeKind.SkillContext;
import net.rka.ai.agent.skills.SkillFile.Input;
import net.rka.ai.agent.skills.SkillFile.NodeSpec;
import net.rka.ai.tools.ToolRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks a skill file before it becomes a subgraph. Each problem is one full sentence naming the
 * node and the field, so the user (or the skill builder's model) can fix the file from the list.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li>Name, description, inputs, limits: {@code name} is lower-case letters, digits and {@code -};
 *       the description has at least {@value #MIN_DESCRIPTION} characters; inputs have unique names;
 *       {@code maxSteps} is 1 to {@value #MAX_STEPS} (1 to {@value #MAX_CODING_STEPS} with coding kinds),
 *       {@code maxToolRounds} 1 to {@value #MAX_TOOL_ROUNDS}, other limits are ones the skill's kinds read
 *       ({@link NodeKind#limitNames}), 1 to {@value #MAX_EXTRA_LIMIT}; {@code tool} is set by built-in skills only (so
 *       user skills can't shadow core tools).</li>
 *   <li>Nodes: unique ids (letters, digits, {@code _}, starting with a letter, so never LangGraph4j's own
 *       {@code __START__} / {@code __END__}; not one of the subgraph's own nodes {@link #RESERVED_IDS}), a known kind, fields the kind accepts ({@link NodeKind#check}).</li>
 *   <li>Tools: every name in a node's {@code tools} exists and is not {@linkplain #isForbidden forbidden}.</li>
 *   <li>Wiring: {@code start} exists; every outcome of a node except {@code stopped} leads to an existing
 *       node ({@code next} for a single outcome, else {@code on}); {@code on} names only outcomes the
 *       node has; a terminal node ({@code finish}) has no wiring.</li>
 *   <li>Graph: every node is reachable from {@code start}, and from every node, following the wired
 *       outcomes, a terminal node can be reached (no path that can only loop forever).</li>
 *   <li>Data flow: every {@code {{var}}} a node uses is an input, a built-in ({@code goal},
 *       {@code folder}, {@code environment}) or set on <em>every</em> path from {@code start} to it.</li>
 *   <li>Coding kinds: {@code coding.setup}, {@code coding.edit}, {@code coding.fix} and {@code coding.verify}
 *       are reachable from {@code start} and from every {@code coding.plan} node only
 *       through a {@code coding.planApproval} node's {@code approve} outcome; every path to a coding node
 *       starts with {@code coding.environment}.</li>
 * </ol>
 * The file name and node-id collisions between skills are the loader's checks: they need the file
 * and the other skills.
 *
 * <h2>The data-flow check</h2>
 * A "must be defined" forward analysis: {@code in(start)} is empty; for every other node,
 * {@code in(n)} is the intersection, over the wired edges p → n, of {@code in(p) ∪ writes(p)}, or just
 * {@code in(p)} for a {@code stopped} edge (a node that stopped didn't write its output). Starting from
 * "everything is defined" and repeating until nothing changes gives the largest solution, so a
 * branch back to an earlier node keeps what was defined on the way there.
 */
public final class SkillValidator {

    /** Tools a skill may never offer: they start jobs, skills or the builder, or reach other machines. */
    public static final Set<String> FORBIDDEN_TOOLS = Set.of("startCodingJob", "buildSkill", "sshHosts", "runRemoteCommand");
    /** The prefix of every skill's tool; a skill can't start skills. */
    public static final String SKILL_TOOL_PREFIX = "skill_";
    /** Node ids the skill subgraph adds itself, so a file can't use them. */
    public static final Set<String> RESERVED_IDS = Set.of("start", "toolStep", "toolApproval", NodeKind.STOPPED);

    static final int MIN_DESCRIPTION = 20;
    static final int MAX_STEPS = 200;
    /** {@code maxSteps} cap of a skill that uses the coding kinds (a long coding job takes hundreds of steps). */
    static final int MAX_CODING_STEPS = 1000;
    static final int MAX_TOOL_ROUNDS = 50;
    static final int MAX_EXTRA_LIMIT = 100;

    private static final Pattern SKILL_NAME = Pattern.compile("[a-z][a-z0-9-]*");
    // Input names: identifiers, as {{input.<name>}} uses them
    private static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    // Node ids: no dashes, so skill-<name>-<id> always splits the same way, whatever the name; and a letter
    // first, so no id can be one of LangGraph4j's own node names (__START__, __END__), which addNode
    // rejects: such a file would pass here and then fail to build
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final Map<String, NodeKind> kinds;
    private final ToolRegistry tools;

    public SkillValidator(Map<String, NodeKind> kinds, ToolRegistry tools) {
        this.kinds = Collections.unmodifiableMap(new LinkedHashMap<>(kinds)); // get(null) is allowed: a node without kind
        this.tools = tools;
    }

    /** Whether a skill may never offer the tool: {@link #FORBIDDEN_TOOLS} and every {@code skill_*} tool. */
    public static boolean isForbidden(String tool) {
        return FORBIDDEN_TOOLS.contains(tool) || tool.startsWith(SKILL_TOOL_PREFIX);
    }

    /** The node kinds skills can use, by name. */
    public Map<String, NodeKind> kinds() {
        return kinds;
    }

    /** The tools skills are checked against: a node may list these, except the {@linkplain #isForbidden forbidden} ones. */
    public ToolRegistry tools() {
        return tools;
    }

    /**
     * The problems with the skill; empty when it is valid.
     *
     * @param builtIn whether the skill ships with the app (only built-ins may set {@code tool})
     */
    public List<String> validate(SkillFile skill, boolean builtIn) {
        return validate(skill, builtIn, null);
    }

    /**
     * Like {@link #validate(SkillFile, boolean)}, for a user file that replaces a built-in skill: it
     * is a user file for every rule (and every kind's check sees {@code builtIn = false}), except that
     * its {@code tool} may be exactly the built-in's.
     *
     * @param pinnedTool the one {@code tool} value a user file may have, or {@code null} for none
     */
    public List<String> validate(SkillFile skill, boolean builtIn, String pinnedTool) {
        List<String> errors = new ArrayList<>();
        checkHeader(skill, builtIn, pinnedTool, errors);
        Map<String, NodeSpec> nodes = checkNodes(skill, new SkillContext(skill, tools, builtIn), errors);
        boolean kindsKnown = skill.nodes().stream().allMatch(node -> kinds.containsKey(node.kind()));
        // With an unknown kind the outcomes are unknown too; the graph checks would only add noise
        if (!kindsKnown) return errors;
        checkWiring(skill, nodes, errors);
        if (skill.start() == null || !nodes.containsKey(skill.start())) return errors;
        Set<String> reachable = checkGraph(skill, nodes, errors);
        checkDataFlow(skill, nodes, reachable, errors);
        checkPlanApproval(skill, nodes, errors);
        return errors;
    }

    /**
     * The coding job's safety rules, for every skill that uses the coding kinds:
     * <ul>
     *   <li>The steps that work under a plan ({@link CodingKinds#SETUP} which runs the setup commands,
     *       {@link CodingKinds#EDIT} and {@link CodingKinds#FIX} which change files, {@link CodingKinds#VERIFY} which
     *       runs the verify commands) are reached only through a {@link CodingKinds#PLAN_APPROVAL} node's
     *       {@code approve} outcome: from {@code start}, and from every node that writes a plan
     *       ({@link CodingKinds#PLAN}), so a new draft (a revision, even by a second plan node after the
     *       approval) is always approved before it is worked by. Checked by walking the wiring from those
     *       nodes without the {@code approve} edges (every other edge counts, {@code stopped} too).</li>
     *   <li>{@link CodingKinds#ENVIRONMENT} comes first: every path from {@code start} to another coding
     *       node passes through one, so the job's keys are fresh (goal, call, plan) for every run.</li>
     * </ul>
     * The kinds enforce the approval at run time too (setup, edit, fix and verify only run by the
     * approved plan); this rule rejects files whose wiring would only ever stop there.
     */
    private void checkPlanApproval(SkillFile skill, Map<String, NodeSpec> nodes, List<String> errors) {
        if (nodes.values().stream().noneMatch(node -> CodingKinds.isCodingKind(node.kind()))) return;
        Set<String> sources = new LinkedHashSet<>(List.of(skill.start()));
        nodes.values().stream().filter(node -> CodingKinds.PLAN.equals(node.kind()))
                .forEach(node -> sources.add(node.id()));
        Set<String> reported = new HashSet<>();
        for (String source : sources) {
            Set<String> unapproved = walk(source, nodes, (node, outcome) ->
                    !(CodingKinds.PLAN_APPROVAL.equals(node.kind()) && outcome.equals(CodingKinds.APPROVE)));
            for (String id : unapproved) {
                NodeSpec node = nodes.get(id);
                if (isGuardedByPlanApproval(node) && reported.add(id)) {
                    errors.add("node `" + id + "`: a " + node.kind() + " node may only be reached through a "
                            + CodingKinds.PLAN_APPROVAL + " node's `" + CodingKinds.APPROVE + "` outcome, from `start` and"
                            + " from every plan node, so nothing runs by a plan the user didn't approve;"
                            + " this wiring has a path around it from `" + source + "`.");
                }
            }
        }
        // Up to an environment node: a coding node found on the way runs before the job's keys are reset
        Set<String> beforeEnvironment = walk(skill.start(), nodes, (node, outcome) -> !CodingKinds.ENVIRONMENT.equals(node.kind()));
        for (String id : beforeEnvironment) {
            NodeSpec node = nodes.get(id);
            if (CodingKinds.isCodingKind(node.kind()) && !CodingKinds.ENVIRONMENT.equals(node.kind())) {
                errors.add("node `" + id + "`: every path from `start` to a " + node.kind() + " node must pass through a "
                        + CodingKinds.ENVIRONMENT + " node, which starts the job.");
            }
        }
    }

    /** The nodes reachable from {@code from} (included) along the edges {@code follow} accepts. */
    private Set<String> walk(String from, Map<String, NodeSpec> nodes, java.util.function.BiPredicate<NodeSpec, String> follow) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(from));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!seen.add(id)) continue;
            NodeSpec node = nodes.get(id);
            edges(node, nodes).forEach((outcome, target) -> {
                if (follow.test(node, outcome)) todo.push(target);
            });
        }
        return seen;
    }

    private static boolean isGuardedByPlanApproval(NodeSpec node) {
        return CodingKinds.SETUP.equals(node.kind()) || CodingKinds.EDIT.equals(node.kind())
                || CodingKinds.FIX.equals(node.kind()) || CodingKinds.VERIFY.equals(node.kind());
    }

    private void checkHeader(SkillFile skill, boolean builtIn, String pinnedTool, List<String> errors) {
        if (skill.name() == null || skill.name().isBlank()) {
            errors.add("The skill needs a `name`.");
        } else if (!SKILL_NAME.matcher(skill.name()).matches()) {
            errors.add("The name `" + skill.name() + "` may only have lower-case letters, digits and `-`, starting with a letter.");
        }
        if (skill.description() == null || skill.description().strip().length() < MIN_DESCRIPTION) {
            errors.add("The `description` must say in at least " + MIN_DESCRIPTION
                    + " characters what the skill does and when to use it: the model reads it to decide.");
        }
        if (skill.tool() != null && !builtIn && !skill.tool().equals(pinnedTool)) {
            errors.add(pinnedTool != null
                    ? "This file replaces a built-in skill, so its tool stays `" + pinnedTool + "`; leave `tool` out."
                    : "Only built-in skills may set `tool`; this skill's tool is always `" + SKILL_TOOL_PREFIX
                    + (skill.name() == null ? "" : skill.name().replace('-', '_')) + "`.");
        } else if (skill.tool() != null && skill.tool().isBlank()) {
            errors.add("`tool` must not be blank.");
        }
        Set<String> inputNames = new HashSet<>();
        for (Input input : skill.inputs()) {
            if (input.name() == null || !INPUT_NAME.matcher(input.name()).matches()) {
                errors.add("The input name `" + input.name() + "` must be letters, digits and `_`.");
            } else if (!inputNames.add(input.name())) {
                errors.add("The input `" + input.name() + "` is listed more than once.");
            }
            if (input.description() == null || input.description().isBlank()) {
                errors.add("The input `" + input.name() + "` needs a `description`: the model reads it to fill it in.");
            }
        }
        SkillFile.Limits limits = skill.limits();
        // A coding job visits a node per model turn of each step, so it may take many more steps
        boolean coding = skill.nodes().stream().anyMatch(node -> CodingKinds.isCodingKind(node.kind()));
        range("limits.maxSteps", limits.maxSteps(), coding ? MAX_CODING_STEPS : MAX_STEPS, errors);
        range("limits.maxToolRounds", limits.maxToolRounds(), MAX_TOOL_ROUNDS, errors);
        // Only the limits the skill's kinds read: a misspelled one would silently not apply
        Set<String> known = new TreeSet<>();
        for (NodeSpec node : skill.nodes()) {
            NodeKind kind = kinds.get(node.kind());
            if (kind != null) known.addAll(kind.limitNames());
        }
        limits.extra().forEach((name, value) -> {
            if (!known.contains(name)) {
                List<String> all = new ArrayList<>(List.of("maxSteps", "maxToolRounds"));
                all.addAll(known);
                errors.add("`limits." + name + "` is not a limit of this skill; its limits are " + String.join(", ", all) + ".");
            } else {
                range("limits." + name, value, MAX_EXTRA_LIMIT, errors);
            }
        });
    }

    private static void range(String field, int value, int max, List<String> errors) {
        if (value < 1 || value > max) errors.add("`" + field + "` must be between 1 and " + max + ", not " + value + ".");
    }

    /** Ids, kinds, the kinds' own field checks and tool names; returns the nodes by id (first of duplicates). */
    private Map<String, NodeSpec> checkNodes(SkillFile skill, SkillContext context, List<String> errors) {
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        if (skill.nodes().isEmpty()) errors.add("The skill needs at least one node in `nodes`.");
        for (NodeSpec node : skill.nodes()) {
            if (node.id() == null || !ID.matcher(node.id()).matches()) {
                errors.add("The node id `" + node.id() + "` must be letters, digits and `_`, starting with a letter"
                        + " (names like `__END__` are LangGraph4j's own).");
                continue;
            }
            if (RESERVED_IDS.contains(node.id())) {
                errors.add("The node id `" + node.id() + "` is reserved: the skill subgraph has a node of that name.");
            }
            if (nodes.putIfAbsent(node.id(), node) != null) {
                errors.add("The node id `" + node.id() + "` is used more than once.");
                continue;
            }
            NodeKind kind = kinds.get(node.kind());
            if (kind == null) {
                String what = node.kind() == null ? "needs a `kind`" : "unknown kind `" + node.kind() + "`";
                errors.add("node `" + node.id() + "`: " + what + "; the kinds are "
                        + String.join(", ", kinds.keySet().stream().sorted().toList()) + ".");
                continue;
            }
            errors.addAll(kind.check(node, context));
            checkTools(node, errors);
        }
        return nodes;
    }

    /** Every listed tool must exist and be allowed in skills; checked for every kind, so none can forget it. */
    private void checkTools(NodeSpec node, List<String> errors) {
        if (!(node.fields().get("tools") instanceof List<?> list)) return;
        for (Object entry : list) {
            if (!(entry instanceof String tool)) continue; // the kind reports the wrong type
            if (isForbidden(tool)) {
                errors.add("node `" + node.id() + "`: the tool `" + tool + "` is never available in skills.");
            } else if (!tools.names().contains(tool)) {
                errors.add("node `" + node.id() + "`: there is no tool `" + tool + "`.");
            }
        }
    }

    private void checkWiring(SkillFile skill, Map<String, NodeSpec> nodes, List<String> errors) {
        if (skill.start() == null) {
            errors.add("The skill needs `start`: the id of its first node.");
        } else if (!nodes.containsKey(skill.start())) {
            errors.add("`start` points to `" + skill.start() + "`, which doesn't exist.");
        }
        for (NodeSpec node : nodes.values()) {
            String where = "node `" + node.id() + "`: ";
            Set<String> outcomes = kinds.get(node.kind()).outcomes(node);
            if (outcomes.isEmpty()) {
                if (node.next() != null || !node.on().isEmpty()) {
                    errors.add(where + "a " + node.kind() + " node ends the skill, so it takes no `next` or `on`.");
                }
                continue;
            }
            if (node.next() != null && outcomes.size() != 1) {
                errors.add(where + "`next` only works for a node with one outcome; map each of "
                        + String.join(", ", outcomes) + " with `on`.");
            }
            if (node.next() != null && outcomes.size() == 1 && node.on().containsKey(outcomes.iterator().next())) {
                errors.add(where + "the outcome `" + outcomes.iterator().next() + "` is wired twice, by `next` and by `on`.");
            }
            for (String outcome : node.on().keySet()) {
                if (!outcomes.contains(outcome) && !outcome.equals(NodeKind.STOPPED)) {
                    errors.add(where + "`on." + outcome + "` is not an outcome of this node; its outcomes are "
                            + String.join(", ", outcomes) + " and stopped.");
                }
            }
            Map<String, String> targets = node.targets(outcomes);
            for (String outcome : outcomes) {
                if (!targets.containsKey(outcome)) {
                    errors.add(where + "the outcome `" + outcome + "` doesn't lead anywhere; add it to `on`"
                            + (outcomes.size() == 1 ? " or set `next`." : "."));
                }
            }
            targets.forEach((outcome, target) -> {
                if (!nodes.containsKey(target)) {
                    String field = node.on().containsKey(outcome) ? "`on." + outcome + "`" : "`next`";
                    errors.add(where + field + " points to `" + target + "`, which doesn't exist.");
                }
            });
        }
    }

    /** The wired edges from the node to existing nodes: outcome → target. */
    private Map<String, String> edges(NodeSpec node, Map<String, NodeSpec> nodes) {
        Map<String, String> edges = new LinkedHashMap<>(node.targets(kinds.get(node.kind()).outcomes(node)));
        edges.values().removeIf(target -> !nodes.containsKey(target));
        return edges;
    }

    /** Reachability from start and "a finish can be reached from every node"; returns the reachable ids. */
    private Set<String> checkGraph(SkillFile skill, Map<String, NodeSpec> nodes, List<String> errors) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(skill.start()));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (reachable.add(id)) todo.addAll(edges(nodes.get(id), nodes).values());
        }
        for (String id : nodes.keySet()) {
            if (!reachable.contains(id)) errors.add("node `" + id + "`: no path from `start` reaches this node.");
        }
        // Grow the set of nodes that can finish: terminal nodes, then every node with an edge into the set
        Set<String> canFinish = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : reachable) {
                if (canFinish.contains(id)) continue;
                NodeSpec node = nodes.get(id);
                boolean terminal = kinds.get(node.kind()).outcomes(node).isEmpty();
                // a stopped edge doesn't count: a node whose real outcomes only loop must not pass
                // because its error path happens to reach a finish
                boolean leadsToFinish = edges(node, nodes).entrySet().stream()
                        .anyMatch(edge -> !edge.getKey().equals(NodeKind.STOPPED) && canFinish.contains(edge.getValue()));
                if (terminal || leadsToFinish) {
                    canFinish.add(id);
                    changed = true;
                }
            }
        }
        for (String id : reachable) {
            if (!canFinish.contains(id)) {
                errors.add("node `" + id + "`: no path from here reaches a `finish` node, so the skill could only loop forever.");
            }
        }
        return reachable;
    }

    private void checkDataFlow(SkillFile skill, Map<String, NodeSpec> nodes, Set<String> reachable, List<String> errors) {
        // Start from "everything any node writes" (except at start) and shrink until stable
        Set<String> everything = new HashSet<>();
        for (String id : reachable) everything.addAll(writes(nodes.get(id)));
        Map<String, Set<String>> in = new HashMap<>();
        for (String id : reachable) in.put(id, id.equals(skill.start()) ? Set.of() : everything);
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Set<String>> next = new HashMap<>();
            for (String id : reachable) next.put(id, id.equals(skill.start()) ? Set.of() : null);
            for (String id : reachable) {
                NodeSpec node = nodes.get(id);
                for (Map.Entry<String, String> edge : edges(node, nodes).entrySet()) {
                    if (edge.getValue().equals(skill.start())) continue; // in(start) stays empty
                    Set<String> out = new HashSet<>(in.get(id));
                    if (!edge.getKey().equals(NodeKind.STOPPED)) out.addAll(writes(node));
                    Set<String> target = next.get(edge.getValue());
                    if (target == null) {
                        next.put(edge.getValue(), out);
                    } else {
                        target.retainAll(out);
                    }
                }
            }
            for (String id : reachable) {
                Set<String> value = next.get(id) == null ? Set.of() : next.get(id);
                if (!value.equals(in.get(id))) {
                    in.put(id, value);
                    changed = true;
                }
            }
        }
        Set<String> inputs = new HashSet<>();
        skill.inputs().forEach(input -> inputs.add(input.name()));
        for (String id : reachable) {
            NodeSpec node = nodes.get(id);
            for (String path : kinds.get(node.kind()).reads(node)) {
                String[] parts = path.split("\\.");
                String root = parts[0];
                if (GenericKinds.BUILT_IN_VARIABLES.contains(root)) continue;
                if (root.equals(GenericKinds.INPUT)) {
                    if (parts.length > 1 && !inputs.contains(parts[1])) {
                        errors.add("node `" + id + "`: `{{" + path + "}}` uses the input `" + parts[1]
                                + "`, which the skill doesn't declare in `inputs`.");
                    }
                } else if (!in.get(id).contains(root)) {
                    errors.add("node `" + id + "`: `{{" + path + "}}` is not set on every path to this node; "
                            + "set `" + root + "` with an earlier node's `output` or `set`, or declare an input in `inputs` and use `{{input." + root + "}}`.");
                }
            }
        }
    }

    private Set<String> writes(NodeSpec node) {
        return kinds.get(node.kind()).writes(node);
    }

    /**
     * The node ids the skill takes in the agent graph: its subgraph node {@code skill-<name>} and
     * {@code skill-<name>-<id>} for its own and the {@linkplain #RESERVED_IDS built-in} nodes. The
     * loader rejects a skill whose ids another loaded skill already has.
     */
    public static Set<String> graphIds(SkillFile skill) {
        String prefix = "skill-" + skill.name();
        Set<String> ids = new LinkedHashSet<>(List.of(prefix));
        for (String id : RESERVED_IDS) ids.add(prefix + "-" + id);
        for (NodeSpec node : skill.nodes()) ids.add(prefix + "-" + node.id());
        return ids;
    }
}
