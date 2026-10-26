package net.rka.ai.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration lookup: real environment variables first, then a {@code .env}
 * file in the working directory (KEY=value lines, # comments), then the default.
 *
 * <h2>How it works</h2>
 * The {@code .env} file is read once, when this class is first used (the static field below),
 * into a map. {@link #get} then checks, in order:
 * <ol>
 *   <li>the process environment ({@code System.getenv}), so a value set in the shell or in a VS Code
 *       launch configuration always wins;</li>
 *   <li>the {@code .env} map;</li>
 *   <li>the default passed by the caller.</li>
 * </ol>
 * Blank values count as "not set", so {@code LANGSMITH_API_KEY=} in {@code .env} means "off".
 * The file is looked up relative to the <em>working directory</em>, which is the project folder when
 * you start the app with Gradle or from VS Code.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Reloading: changes to {@code .env} need an app restart. A file watcher could reload the map.</li>
 *   <li>Variable expansion ({@code BASE=${HOME}/x}) and multi-line values aren't supported.</li>
 *   <li>A search path (project folder, then {@code ~/.config/lemonade-agent/.env}) would let the app run
 *       from any directory.</li>
 *   <li>Typed getters ({@code getInt}, {@code getBoolean}, {@code getDuration}) would replace the
 *       parsing that callers do today.</li>
 * </ul>
 */
public final class Env {

    // Loaded once per JVM. Static initialization is thread-safe, so no locking is needed.
    private static final Map<String, String> DOT_ENV = load(Path.of(".env"));

    private Env() {
        // static helpers only
    }

    /**
     * @param name         the variable, e.g. {@code LEMONADE_BASE_URL}
     * @param defaultValue returned when neither the environment nor {@code .env} has a non-blank value
     */
    public static String get(String name, String defaultValue) {
        String value = System.getenv(name);                      // 1. real environment
        if (value == null || value.isBlank()) value = DOT_ENV.get(name); // 2. .env file
        return value == null || value.isBlank() ? defaultValue : value;  // 3. default
    }

    /** Parses a {@code .env} file into a map; a missing file gives an empty map. */
    private static Map<String, String> load(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;            // blank lines and comments
                if (line.startsWith("export ")) line = line.substring(7).strip(); // allow shell-style "export KEY=value"
                int eq = line.indexOf('=');
                if (eq <= 0) continue;                                            // no key: ignore the line
                // Everything after the first '=' is the value, so values may themselves contain '='
                String value = line.substring(eq + 1).strip();
                // Remove one pair of surrounding quotes: KEY="a b" and KEY='a b' both mean a b
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            // A broken .env shouldn't stop the app: report it and fall back to defaults
            System.err.println("[env] could not read " + file.toAbsolutePath() + ": " + e.getMessage());
        }
        return values;
    }
}
