package net.rka.ai.tools;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binary files are not text: a model that "writes" a jar, a zip or an image with text, or with bytes it made up, makes a file that looks
 * like one and is not (a 4-byte {@code gradle-wrapper.jar} that every build then fails on). Such a file comes from {@code downloadFile},
 * or from a tool that builds it; the file and command tools refuse to make it from text.
 */
public final class BinaryFiles {

    private static final Set<String> EXTENSIONS = Set.of("jar", "zip", "war", "ear", "class", "exe", "dll", "so", "dylib", "png", "jpg", "jpeg",
            "gif", "ico", "bmp", "pdf", "gz", "tgz", "7z", "rar", "bin", "wasm", "ttf", "otf");

    /** A command that writes text or bytes (Set-Content, Add-Content, Out-File, New-Item, WriteAll…, echo, printf, a redirect) and names a binary file. */
    private static final Pattern WRITER = Pattern.compile("(?i)\\b(Set-Content|Add-Content|Out-File|New-Item|WriteAllText|WriteAllBytes|echo|printf)\\b");
    /** A redirect whose target is a binary file ({@code 2>&1} and the like are not one). */
    private static final Pattern REDIRECT = Pattern.compile("(?i)>>?\\s*[^\\s|;&]*\\.(" + String.join("|", EXTENSIONS) + ")\\b");
    private static final Pattern BINARY_NAME = Pattern.compile("(?i)[\\w./\\\\:$'\"-]*\\.(" + String.join("|", EXTENSIONS) + ")\\b");

    private BinaryFiles() {
    }

    /** The reason a text file tool must not write this path, or empty when it may. */
    public static Optional<String> refusalForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(lower.substring(dot + 1))) return Optional.empty();
        return Optional.of(path + " is a binary file (" + lower.substring(dot) + "): text written to it would not be one. Get it with downloadFile,"
                + " or tell the user it is missing and what they can do about it");
    }

    /** The reason a command is refused, or empty: it writes text or bytes to a file whose name says it is binary. */
    public static Optional<String> refusalForCommand(String command) {
        if (command == null) return Optional.empty();
        if (!REDIRECT.matcher(command).find() && !WRITER.matcher(command).find()) return Optional.empty();
        Matcher name = BINARY_NAME.matcher(command);
        if (!name.find()) return Optional.empty();
        return Optional.of("The command writes text or bytes to " + name.group() + ", a binary file: what it makes would not be one. Get such a file"
                + " with downloadFile, or tell the user it is missing and what they can do about it");
    }
}
