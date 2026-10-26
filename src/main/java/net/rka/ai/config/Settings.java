package net.rka.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Agent settings, at two levels:
 * <ul>
 *   <li><b>global</b>, in {@code SETTINGS_FILE} (default {@code ~/chat/settings.json}): the default trusted
 *       folder for new conversations and the commands trusted in every conversation;</li>
 *   <li><b>per conversation</b>, saved with it: its own trusted folder and extra trusted commands.</li>
 * </ul>
 * A run uses {@link #effective}: the conversation's folder, and global plus conversation commands.
 *
 * <h2>How it works</h2>
 * The same record type holds all three kinds of settings (global, conversation, effective), which
 * keeps the JSON format and the dialog code shared. It's immutable: "changing" a setting means
 * building a new record with {@link #withWorkFolder} / {@link #withTrustedCommands}.
 * <p>
 * Where the values flow:
 * <pre>
 *   ~/chat/settings.json ──loadGlobal()──► global ─┐
 *                                            ├─ effective(global, conversation) ─► Agent.run(..., settings)
 *   conversations/&lt;id&gt;.json ──► conversation ─┘        ─► graph state: workFolder + trustedCommands
 * </pre>
 * The chat window edits them with {@code SettingsDialog}; the CLI only reads the global ones.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>More settings: model name and temperature, which tools are enabled, SSH hosts, the system
 *       prompt. Today those come from {@code .env} or code; moving them here would make them editable
 *       in the UI and per conversation.</li>
 *   <li>Per-conversation <em>removal</em> of a global trusted command (today a conversation can only add).</li>
 *   <li>Several trusted folders per conversation, or a read-only vs read-write distinction.</li>
 *   <li>Validation on load (e.g. warn when the folder no longer exists) and a schema version field
 *       for future migrations.</li>
 * </ul>
 *
 * @param workFolder      trusted folder; null in global settings means "the default" ({@link #defaultFolder})
 * @param trustedCommands commands that run without asking; {@code *} matches arguments
 */
public record Settings(Path workFolder, List<String> trustedCommands) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Compact constructor: runs for every new record. It cleans the command list so the rest of the
     * code never sees null, blank entries, stray spaces or duplicates, and makes it immutable.
     */
    public Settings {
        trustedCommands = List.copyOf(trustedCommands == null ? List.of() : trustedCommands.stream()
                .map(String::strip).filter(s -> !s.isEmpty()).distinct().toList());
    }

    public Settings withWorkFolder(Path folder) {
        return new Settings(folder, trustedCommands);
    }

    public Settings withTrustedCommands(List<String> commands) {
        return new Settings(workFolder, commands);
    }

    /** The settings a run uses: the conversation's folder (else the global one) and both command lists. */
    public static Settings effective(Settings global, Settings conversation) {
        // LinkedHashSet: union of both lists, without duplicates, in a stable order (global first)
        LinkedHashSet<String> commands = new LinkedHashSet<>(global.trustedCommands());
        commands.addAll(conversation.trustedCommands());
        Path folder = conversation.workFolder() != null ? conversation.workFolder() : global.workFolderOrDefault();
        return new Settings(folder, new ArrayList<>(commands));
    }

    public Path workFolderOrDefault() {
        return workFolder != null ? workFolder : defaultFolder();
    }

    /** {@code WORK_FOLDER} from the environment or {@code .env}, else the user's home folder. */
    public static Path defaultFolder() {
        return Path.of(Env.get("WORK_FOLDER", System.getProperty("user.home"))).toAbsolutePath().normalize();
    }

    /** Settings for a new conversation: the global default folder and no extra commands. */
    public static Settings newConversation(Settings global) {
        // The folder is copied (not left null) so the conversation keeps it even if the global default changes later
        return new Settings(global.workFolderOrDefault(), List.of());
    }

    // --- Global settings file ---

    /** {@code SETTINGS_FILE}, else {@code ~/chat/settings.json} (on Windows {@code C:\Users\<you>\chat\settings.json}). */
    public static Path globalFile() {
        String configured = Env.get("SETTINGS_FILE", null);
        return configured != null
                ? Path.of(configured).toAbsolutePath()
                : Path.of(System.getProperty("user.home"), "chat", "settings.json");
    }

    /**
     * The user's skill folder, {@code skills} next to the {@link #globalFile} ({@code ~/chat/skills}).
     * Only the skill builder writes there; file tools refuse paths inside it.
     */
    public static Path skillsFolder() {
        return globalFile().resolveSibling("skills");
    }

    /**
     * Reads the global settings. Until the file exists, the defaults come from
     * {@code WORK_FOLDER} and {@code TRUSTED_COMMANDS} (comma-separated) in the environment or {@code .env}.
     */
    public static Settings loadGlobal() {
        Path file = globalFile();
        if (!Files.isRegularFile(file)) {
            // First run: seed from .env. workFolder stays null = "use defaultFolder()", so WORK_FOLDER still applies
            List<String> commands = Arrays.asList(Env.get("TRUSTED_COMMANDS", "").split(","));
            return new Settings(null, commands);
        }
        try {
            return fromJson(JSON.readTree(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** Writes the global settings atomically (temp file + rename), so a crash can't leave half a file. */
    public static void saveGlobal(Settings settings) {
        Path file = globalFile();
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            // Write next to the target, then rename over it: readers see either the old or the new file
            Path temp = Files.createTempFile(parent, "settings", ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), settings.toJson());
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    // --- JSON, shared with the conversation files ---

    /** {@code {"workFolder": "...", "trustedCommands": [...]}}; workFolder is omitted when null. */
    public ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        if (workFolder != null) node.put("workFolder", workFolder.toAbsolutePath().toString());
        ArrayNode commands = node.putArray("trustedCommands");
        trustedCommands.forEach(commands::add);
        return node;
    }

    /** The inverse of {@link #toJson}; missing fields become null / empty, so old files still load. */
    public static Settings fromJson(JsonNode node) {
        // path(...) never returns null (a "missing node" instead), so absent fields need no special casing
        String folder = node.path("workFolder").asText("");
        List<String> commands = new ArrayList<>();
        node.path("trustedCommands").forEach(c -> commands.add(c.asText()));
        return new Settings(folder.isBlank() ? null : Path.of(folder), commands);
    }
}
