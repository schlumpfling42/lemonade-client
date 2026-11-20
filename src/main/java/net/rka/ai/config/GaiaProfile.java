package net.rka.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The connection to the Lemonade server that GAIA runs for itself. GAIA starts it on a port of its own choice and with an API key made
 * for that run, and writes both to {@code ~/.gaia/lemonade/state.json}; they change when GAIA restarts, so they are read when the app
 * starts, not written into a settings file. Used by the profile {@code gaia} (see {@link Env}).
 */
final class GaiaProfile {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GaiaProfile() {
    }

    /** The folder GAIA keeps its files in. */
    static Path home() {
        return Path.of(System.getProperty("user.home"), ".gaia");
    }

    /**
     * @param home GAIA's folder ({@link #home()})
     * @return {@code LEMONADE_BASE_URL} and {@code LEMONADE_API_KEY} of the running GAIA
     * @throws IllegalStateException with what to do when GAIA's state file is missing or has no port
     */
    static Map<String, String> connection(Path home) {
        Path file = home.resolve("lemonade").resolve("state.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The profile gaia needs GAIA to be running: " + file + " does not exist. Start GAIA and try again.");
        }
        try {
            JsonNode state = JSON.readTree(file.toFile());
            int port = state.path("port").asInt(0);
            if (port <= 0) throw new IllegalStateException("The profile gaia: " + file + " has no port. Restart GAIA and try again.");
            Map<String, String> values = new HashMap<>();
            values.put("LEMONADE_BASE_URL", "http://127.0.0.1:" + port + "/api/v1");
            String key = state.path("api_key").asText("");
            if (!key.isBlank()) values.put("LEMONADE_API_KEY", key);
            return values;
        } catch (IOException e) {
            throw new IllegalStateException("The profile gaia: " + file + " could not be read: " + e.getMessage(), e);
        }
    }
}
