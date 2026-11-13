package net.rka.ai.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a file's own style is, read from its text, for the model that writes code into it: the code a job adds must
 * look like the code around it, so the facts a local model gets wrong most (indentation, where braces go, whether
 * members carry doc comments) are counted here instead of guessed. A fact is only stated when the file shows it.
 */
final class StyleNotes {

    private StyleNotes() {
    }

    /** The style facts of the text as short lines, or "" when the text is too small to show a style. */
    static String of(String text) {
        List<String> lines = text.replace("\r\n", "\n").lines().toList();
        if (lines.size() < 5) return "";
        StringBuilder notes = new StringBuilder();
        String indent = indentation(lines);
        if (!indent.isEmpty()) notes.append("- Indentation: ").append(indent).append('\n');
        String braces = braces(lines);
        if (!braces.isEmpty()) notes.append("- Braces: ").append(braces).append('\n');
        String docs = docComments(lines);
        if (!docs.isEmpty()) notes.append("- Comments: ").append(docs).append('\n');
        return notes.toString().strip();
    }

    /** Tabs or the usual width of one level, from the step between a line's indentation and the one before it. */
    private static String indentation(List<String> lines) {
        int tabs = 0;
        Map<Integer, Integer> steps = new HashMap<>();
        int previous = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.startsWith("\t")) {
                tabs++;
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            if (spaces > previous && !line.stripLeading().startsWith("*")) steps.merge(spaces - previous, 1, Integer::sum);
            previous = spaces;
        }
        int spaced = steps.values().stream().mapToInt(Integer::intValue).sum();
        if (tabs == 0 && spaced == 0) return "";
        if (tabs > spaced) return "tabs";
        int width = steps.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return width + " spaces per level";
    }

    private static String braces(List<String> lines) {
        int sameLine = 0;
        int ownLine = 0;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.equals("{")) ownLine++;
            else if (stripped.endsWith("{") && stripped.length() > 1) sameLine++;
        }
        if (sameLine + ownLine < 3) return "";
        return ownLine > sameLine ? "the opening brace is on its own line" : "the opening brace is on the line of the declaration";
    }

    /** Whether members have doc comments: the share of declarations-with-bodies preceded by a closing comment line. */
    private static String docComments(List<String> lines) {
        int members = 0;
        int documented = 0;
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            boolean ownLineBrace = i + 1 < lines.size() && lines.get(i + 1).strip().equals("{") && stripped.endsWith(")");
            boolean member = (stripped.endsWith("{") || ownLineBrace) && stripped.contains("(") && !stripped.startsWith("//")
                    && !stripped.matches("^(if|for|while|switch|catch|try|else|do|synchronized)\\b.*")
                    && !stripped.startsWith("return") && !stripped.contains("->") && !stripped.contains("= new");
            if (!member) continue;
            members++;
            int above = i - 1;
            while (above >= 0 && lines.get(above).strip().startsWith("@")) above--; // annotations sit between
            if (above >= 0 && (lines.get(above).strip().endsWith("*/") || lines.get(above).strip().startsWith("///"))) documented++;
        }
        if (members < 3) return "";
        if (documented * 4 >= members * 3) return "almost every method has a doc comment: give new ones the same kind";
        if (documented * 4 <= members) return "methods have no doc comments: don't add any";
        return "";
    }
}
