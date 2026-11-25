package net.rka.ai.ui;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the person was when the app closed, kept in {@code ~/chat/ui-state.json}: the conversation that was open, what was typed and not
 * sent, the place and size of the window and whether the sidebar was open. The app starts there again, so a restart (a rebuild, a crash, a
 * stopped debug session) does not cost the place. It is written as things change, not at the end: a stopped debug session runs no exit code.
 * A file that cannot be read gives an empty state, never an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
final class UiState {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The id of the conversation that was open (its file name in the conversations folder), or null. */
    public String lastConversation;
    /** The text in the input box, not sent. */
    public String draft = "";
    public Double x;
    public Double y;
    public Double width;
    public Double height;
    public boolean maximized;
    public boolean sidebarVisible = true;
    /** Whether the sidebar with the traffic of the model is open. */
    public boolean trafficVisible;
    /** The two boxes of that sidebar: hold every request / every answer until the person lets it go. */
    public boolean holdRequests;
    public boolean holdResponses;
        /** The preferred width of the sidebar, in pixels. */
        public Double sidebarWidth;

    @JsonIgnore
    private Path file;

    /** The state in {@code file}, or an empty one; later {@link #save} writes it back to the same file. */
    static UiState load(Path file) {
        UiState state = new UiState();
        try {
            if (Files.isRegularFile(file)) state = JSON.readValue(file.toFile(), UiState.class);
        } catch (IOException | RuntimeException e) {
            state = new UiState(); // damaged: start empty, the next save replaces it
        }
        state.file = file;
        return state;
    }

    /** Writes the state (to a temporary file first, so a crash in the middle never leaves a half file). */
    synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), this);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // the place is only a convenience: a failing disk must not break the chat
        }
    }

    /** Whether a saved window position and size are known. */
    @JsonIgnore
    boolean hasBounds() {
        return x != null && y != null && width != null && height != null && width > 100 && height > 100;
    }
}
