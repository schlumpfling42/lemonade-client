package net.rka.ai.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.rka.ai.conversations.ConversationStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The calls of the model that the traffic sidebar shows, kept per conversation in {@code ~/chat/traffic/<conversation id>.json} (next to the
 * conversations folder), so the sidebar of a conversation looks the same after a restart. Only the newest {@value #MAX_SAVED} calls are
 * kept, as the text the sidebar shows. A file that cannot be read gives no calls: this is a convenience, never a reason to fail.
 */
final class WireLog {

    /** The calls kept for one conversation. */
    static final int MAX_SAVED = 40;

    /** One call as it is saved. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(int n, String line, String settings, String request, String response, String tools) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path dir;

    WireLog(Path dir) {
        this.dir = dir;
    }

    /** The folder next to the conversations ({@code CONVERSATIONS_DIR} or {@code ~/chat/conversations}). */
    static WireLog fromEnv() {
        return new WireLog(ConversationStore.fromEnv().dir().resolveSibling("traffic"));
    }

    /** The saved calls of a conversation, oldest first; none when there is no file or it cannot be read. */
    List<Entry> load(String conversation) {
        try {
            Path file = file(conversation);
            if (!Files.isRegularFile(file)) return List.of();
            return JSON.readValue(file.toFile(), new TypeReference<List<Entry>>() { });
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** Writes the newest calls (to a temporary file first, so a crash never leaves half a file). */
    void save(String conversation, List<Entry> entries) {
        try {
            Files.createDirectories(dir);
            List<Entry> kept = entries.size() <= MAX_SAVED ? entries : entries.subList(entries.size() - MAX_SAVED, entries.size());
            Path file = file(conversation);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(temporary.toFile(), kept);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // the traffic is a convenience: a failing disk must not break the chat
        }
    }

    /** Removes the calls of a deleted conversation. */
    void delete(String conversation) {
        try {
            Files.deleteIfExists(file(conversation));
        } catch (IOException | RuntimeException e) {
            // nothing to do about it
        }
    }

    private Path file(String conversation) {
        // an id is a UUID; anything else (a path) must not leave the folder
        String name = conversation.replaceAll("[^A-Za-z0-9._-]", "_");
        return dir.resolve(name + ".json");
    }
}
