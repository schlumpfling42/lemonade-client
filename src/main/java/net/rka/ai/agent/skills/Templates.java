package net.rka.ai.agent.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text templates of skill files: {@code {{var}}}, {@code {{var.field}}} and {@code {{input.x}}}.
 *
 * <h2>How it works</h2>
 * A placeholder is a variable path between double braces, spaces allowed around it. The first part
 * names a variable; each further part (after a dot) is a field of the JSON object the value before it
 * holds. Inputs are no special case: they are the variable {@code input}, an object with one field
 * per input. Values are inserted as text: strings as they are, everything else (numbers, lists,
 * objects) as JSON. A missing variable or field becomes the empty string. There is deliberately no
 * expression language: {@code {{ a + b }}} isn't a placeholder and stays in the text as written.
 * <p>
 * Variables are plain Java values as Jackson reads JSON ({@code String}, {@code Number},
 * {@code Boolean}, {@code List}, {@code Map}). {@link #references} is what the validator uses to
 * check that every placeholder is defined before the node that uses it runs.
 */
public final class Templates {

    // {{ name }}, {{ name.field.sub }}: identifiers separated by dots; anything else is left alone
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    private static final ObjectMapper JSON = new ObjectMapper();

    private Templates() {
    }

    /** The template with every placeholder replaced by its value's text; {@code null} renders as "". */
    public static String render(String template, Map<String, Object> vars) {
        if (template == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // quoteReplacement: values may contain $ and \, which appendReplacement would interpret
            matcher.appendReplacement(out, Matcher.quoteReplacement(text(lookup(matcher.group(1), vars))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The variable paths the template uses, e.g. {@code input.since} and {@code notes}, in order of appearance. */
    public static Set<String> references(String template) {
        Set<String> paths = new LinkedHashSet<>();
        if (template == null) return paths;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) paths.add(matcher.group(1));
        return paths;
    }

    /** A variable path as the {@code set} node's {@code copy}, {@code first} and {@code rest} take it: {@code a.b.c}, no braces. */
    static boolean isPath(Object value) {
        return value instanceof String text && text.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*");
    }

    /** The value (not its text) at a variable path, e.g. the list {@code roadmap.milestones}; {@code null} when it is missing. */
    static Object value(String path, Map<String, Object> vars) {
        return lookup(path, vars);
    }

    /** Follows {@code a.b.c} through nested maps; {@code null} as soon as a part is missing or not an object. */
    private static Object lookup(String path, Map<String, Object> vars) {
        Object value = vars;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(part);
        }
        return value;
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof String string) return string;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value); // values come from JSON, so this doesn't happen in practice
        }
    }
}
