package net.rka.ai.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration lookup: real environment variables first, then the selected profile, then a {@code .env}
 * file in the working directory (KEY=value lines, # comments), then the default.
 * <p>
 * A <b>profile</b> is a second configuration for the same app, chosen with {@code CHAT_PROFILE=<name>} (a real environment variable, or a
 * line in {@code .env}): the file {@code .env.<name>} next to {@code .env} is read on top of it, and its values win over {@code .env}.
 * The profile {@code gaia} also takes the address and key of GAIA's own Lemonade server from GAIA's state file ({@link GaiaProfile}).
 * With no profile, nothing changes.
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
    /** The values of the selected profile; empty with none. */
    private static final Map<String, String> PROFILE = profile(DOT_ENV);
    /** The name of the selected profile, or "". */
    private static final String PROFILE_NAME = profileName(DOT_ENV);

    private Env() {
        // static helpers only
    }

    /**
     * @param name         the variable, e.g. {@code LEMONADE_BASE_URL}
     * @param defaultValue returned when neither the environment nor {@code .env} has a non-blank value
     */
    public static String get(String name, String defaultValue) {
        String value = System.getenv(name);                      // 1. real environment
        if (value == null || value.isBlank()) value = PROFILE.get(name); // 2. the selected profile
        if (value == null || value.isBlank()) value = DOT_ENV.get(name); // 3. .env file
        return value == null || value.isBlank() ? defaultValue : value;  // 4. default
    }

    /** The selected profile ({@code CHAT_PROFILE}), or "" when there is none. */
    public static String profileName() {
        return PROFILE_NAME;
    }

    private static String profileName(Map<String, String> dotEnv) {
        String name = System.getenv("CHAT_PROFILE");
        if (name == null || name.isBlank()) name = dotEnv.get("CHAT_PROFILE");
        return name == null ? "" : name.strip();
    }

    /** The values of a profile: what its source gives (GAIA: where its server is), then its {@code .env.<name>} file on top. */
    static Map<String, String> profile(Map<String, String> dotEnv) {
        String name = profileName(dotEnv);
        if (name.isEmpty()) return Map.of();
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalStateException("CHAT_PROFILE must be a plain name, not '" + name + "'");
        Map<String, String> values = new HashMap<>();
        if (name.equals("gaia")) values.putAll(GaiaProfile.connection(GaiaProfile.home()));
        values.putAll(load(Path.of(".env." + name)));
        return values;
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
