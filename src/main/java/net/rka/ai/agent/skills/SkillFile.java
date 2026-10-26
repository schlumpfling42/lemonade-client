package net.rka.ai.agent.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One skill file: a subgraph made of the app's node kinds, as JSON.
 *
 * <pre>
 * {
 *   "name": "release-notes",                       // file name = name + ".json"; tool skill_release_notes
 *   "description": "Draft release notes ...",      // what the model reads to decide when to use it
 *   "inputs": [ { "name": "since", "description": "tag or date", "required": false } ],
 *   "limits": { "maxSteps": 40, "maxToolRounds": 8 },
 *   "start": "collect",
 *   "nodes": [
 *     { "id": "collect", "kind": "model", "prompt": "...", "output": "commits", "next": "draft" },
 *     { "id": "check", "kind": "askUser", ..., "on": { "accept": "done", "revise": "draft" } },
 *     ...
 *   ]
 * }
 * </pre>
 * {@link #parse} only reads the structure (and refuses unknown top-level fields, which are usually
 * typos); whether the skill makes sense is {@link SkillValidator}'s job, so missing values stay
 * {@code null} here and are reported there with the other problems.
 *
 * @param name        lower-case letters, digits and {@code -}
 * @param tool        the tool name, for built-in skills only ({@code null} otherwise); see {@link #toolName()}
 * @param description the tool description the model reads
 * @param inputs      the tool's (string) parameters, available as {@code {{input.<name>}}}
 * @param limits      step and tool-round limits, defaults filled in
 * @param start       the id of the first node
 * @param nodes       the nodes in file order
 */
public record SkillFile(String name, String tool, String description, List<Input> inputs, Limits limits,
                        String start, List<NodeSpec> nodes) {

    /** {@code maxSteps} when the file doesn't set it. */
    public static final int DEFAULT_MAX_STEPS = 40;
    /** {@code maxToolRounds} when the file doesn't set it. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 8;

    /**
     * One parameter of the skill's tool.
     *
     * @param required whether the model must pass it; optional inputs default to ""
     */
    public record Input(String name, String description, boolean required) {
    }

    /**
     * How far one run of the skill may go.
     *
     * @param maxSteps      node visits per run
     * @param maxToolRounds rounds of tool calls per visit of a {@code model} node
     * @param extra         limits of particular node kinds, e.g. the coding kinds' {@code maxStepTurns}
     */
    public record Limits(int maxSteps, int maxToolRounds, Map<String, Integer> extra) {
        public Limits {
            extra = Map.copyOf(extra);
        }
    }

    /**
     * One node of the skill.
     *
     * @param id     unique within the skill; the node is {@code skill-<name>-<id>} in the agent graph
     * @param kind   the node kind, e.g. {@code model}
     * @param fields the kind's own fields (everything but id, kind, next and on), as Jackson reads JSON
     * @param next   the target of the kind's only outcome, or {@code null}
     * @param on     outcome → target node id; empty when not given
     */
    public record NodeSpec(String id, String kind, Map<String, Object> fields, String next, Map<String, String> on) {
        public NodeSpec {
            // unmodifiable but null-tolerant: JSON null values are kept, the kinds' checks report them
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            on = Collections.unmodifiableMap(new LinkedHashMap<>(on)); // keeps the file's order for messages
        }

        /**
         * Where each outcome leads: the {@code on} entries plus, when {@code next} is set and the kind has
         * exactly one outcome, that outcome → {@code next}. Outcomes without a target are left out
         * ({@code stopped} usually is: unmapped, it ends the skill with an error).
         */
        public Map<String, String> targets(Set<String> outcomes) {
            Map<String, String> targets = new LinkedHashMap<>(on);
            if (next != null && outcomes.size() == 1) targets.putIfAbsent(outcomes.iterator().next(), next);
            return targets;
        }
    }

    public SkillFile {
        inputs = List.copyOf(inputs);
        nodes = List.copyOf(nodes);
    }

    /** The tool that starts the skill: {@code tool} if set, else {@code skill_} + the name with {@code -} → {@code _}. */
    public String toolName() {
        if (tool != null) return tool;
        return "skill_" + (name == null ? "" : name.replace('-', '_'));
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOP_LEVEL = Set.of("name", "tool", "description", "inputs", "limits", "start", "nodes");
    private static final Set<String> INPUT_FIELDS = Set.of("name", "description", "required");
    // The node fields every kind shares; the rest belongs to the kind
    private static final Set<String> NODE_WIRING = Set.of("id", "kind", "next", "on");

    /**
     * Reads a skill file.
     *
     * @throws IllegalArgumentException with a readable message when the text isn't JSON, has unknown
     *                                  top-level fields or a value of the wrong type
     */
    public static SkillFile parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            // getOriginalMessage skips Jackson's source excerpt; the line keeps it findable
            String line = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")";
            throw new IllegalArgumentException("The file is not valid JSON" + line
                    + ": " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("The file must hold one JSON object.");
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!TOP_LEVEL.contains(field)) {
                throw new IllegalArgumentException("Unknown field `" + field + "`; a skill file has " + sorted(TOP_LEVEL) + ".");
            }
        }
        List<Input> inputs = new ArrayList<>();
        for (JsonNode input : array(root, "inputs")) {
            if (!input.isObject()) throw new IllegalArgumentException("Each entry of `inputs` must be an object.");
            unknownFields(input, INPUT_FIELDS, "an input");
            inputs.add(new Input(text(input, "name", "input name"), text(input, "description", "input description"),
                    required(input)));
        }
        List<NodeSpec> nodes = new ArrayList<>();
        for (JsonNode node : array(root, "nodes")) nodes.add(node(node));
        return new SkillFile(text(root, "name", "`name`"), text(root, "tool", "`tool`"),
                text(root, "description", "`description`"), inputs, limits(root.get("limits")),
                text(root, "start", "`start`"), nodes);
    }

    private static NodeSpec node(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("Each entry of `nodes` must be an object.");
        String id = text(node, "id", "node id");
        String where = "node `" + (id == null ? "?" : id) + "`";
        Map<String, String> on = new LinkedHashMap<>();
        JsonNode onNode = node.get("on");
        if (onNode != null && !onNode.isNull()) {
            if (!onNode.isObject()) throw new IllegalArgumentException(where + ": `on` must be an object of outcome → node id.");
            for (Map.Entry<String, JsonNode> entry : onNode.properties()) {
                if (!entry.getValue().isTextual()) {
                    throw new IllegalArgumentException(where + ": `on." + entry.getKey() + "` must be a node id (text).");
                }
                on.put(entry.getKey(), entry.getValue().asText());
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (NODE_WIRING.contains(entry.getKey())) continue;
            // plain Java values (String, Number, Boolean, List, Map), so kinds need no Jackson types
            fields.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class));
        }
        return new NodeSpec(id, text(node, "kind", where + " kind"), fields, text(node, "next", where + " `next`"), on);
    }

    /** An input's {@code required}: false when left out; anything but true/false is a type error. */
    private static boolean required(JsonNode input) {
        JsonNode value = input.get("required");
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("The input `" + input.path("name").asText("?") + "`: `required` must be true or false.");
        }
        return value.asBoolean();
    }

    private static Limits limits(JsonNode limits) {
        int maxSteps = DEFAULT_MAX_STEPS;
        int maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
        Map<String, Integer> extra = new LinkedHashMap<>();
        if (limits == null || limits.isNull()) return new Limits(maxSteps, maxToolRounds, extra);
        if (!limits.isObject()) throw new IllegalArgumentException("`limits` must be an object of name → whole number.");
        for (Map.Entry<String, JsonNode> entry : limits.properties()) {
            if (!entry.getValue().canConvertToExactIntegral() || !entry.getValue().canConvertToInt()) {
                throw new IllegalArgumentException("`limits." + entry.getKey() + "` must be a whole number.");
            }
            int value = entry.getValue().asInt();
            switch (entry.getKey()) {
                case "maxSteps" -> maxSteps = value;
                case "maxToolRounds" -> maxToolRounds = value;
                default -> extra.put(entry.getKey(), value);
            }
        }
        return new Limits(maxSteps, maxToolRounds, extra);
    }

    private static List<JsonNode> array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) throw new IllegalArgumentException("`" + field + "` must be a list.");
        List<JsonNode> items = new ArrayList<>();
        value.forEach(items::add);
        return items;
    }

    /** The field's text, {@code null} when missing; anything but text is a type error. */
    private static String text(JsonNode parent, String field, String what) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("The " + what + " must be text.");
        return value.asText();
    }

    private static void unknownFields(JsonNode object, Set<String> known, String what) {
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!known.contains(field)) {
                throw new IllegalArgumentException("Unknown field `" + field + "` in " + what + "; it has " + sorted(known) + ".");
            }
        }
    }

    private static List<String> sorted(Set<String> names) {
        return names.stream().sorted().toList();
    }
}
