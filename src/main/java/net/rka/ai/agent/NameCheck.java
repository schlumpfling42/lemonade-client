package net.rka.ai.agent;

import net.rka.ai.tools.code.java.JavaWorkspace;
import net.rka.ai.tools.code.java.JavaWorkspaces;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the code names in an answer against the project: a name in backticks that looks like a class ({@code ChatWindowMenuButton}) and
 * is nowhere in the project's Java code, and that the user did not say themselves, is named in a note under the answer ("Not found in the
 * project: …"). The note is part of the stored answer, so the user sees it and the model reads it in every later turn: an invented class
 * can no longer pass for a fact. Only a project with Java files is checked, and only names written in backticks.
 */
final class NameCheck {

    /** A name like a class: a capital first, another capital later and a small letter in between. */
    private static final Pattern CLASS_LIKE = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+\\b");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]{3,120})`");
    static final String MARK = "[Not found in the project: ";

    private NameCheck() {
    }

    /** The note for the answer, or "" when every class-like name in it is known. */
    static String note(String answer, String userText, Path folder, JavaWorkspaces workspaces) {
        if (answer == null || answer.contains(MARK)) return "";
        JavaWorkspace workspace = workspaces.workspace(folder).orElse(null);
        if (workspace == null) return "";
        List<String> unknown = new ArrayList<>();
        for (String name : names(answer)) {
            if (workspace.mentions(name) || userText.contains(name)) continue;
            unknown.add("`" + name + "`");
        }
        return unknown.isEmpty() ? "" : MARK + String.join(", ", unknown) + ". These names may be wrong: check them before relying on them.]";
    }

    /** The class-like names written in backticks, each once. */
    static Set<String> names(String answer) {
        Set<String> names = new LinkedHashSet<>();
        Matcher ticked = BACKTICKED.matcher(answer);
        while (ticked.find()) {
            Matcher name = CLASS_LIKE.matcher(ticked.group(1));
            while (name.find()) if (name.group().length() >= 6) names.add(name.group());
        }
        return names;
    }
}
