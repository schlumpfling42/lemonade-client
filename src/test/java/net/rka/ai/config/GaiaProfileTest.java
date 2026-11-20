package net.rka.ai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GaiaProfileTest {

    @TempDir
    Path home;

    private void state(String json) throws Exception {
        Files.createDirectories(home.resolve("lemonade"));
        Files.writeString(home.resolve("lemonade").resolve("state.json"), json);
    }

    @Test
    void theAddressAndTheKeyOfTheRunningGaiaAreRead() throws Exception {
        state("{\"pid\": 1, \"port\": 15848, \"api_key\": \"abc\", \"version\": \"2026.44.1\"}");

        assertEquals(Map.of("LEMONADE_BASE_URL", "http://127.0.0.1:15848/api/v1", "LEMONADE_API_KEY", "abc"), GaiaProfile.connection(home));
    }

    @Test
    void aGaiaThatIsNotRunningIsSaidInTheMessage() throws Exception {
        assertTrue(assertThrows(IllegalStateException.class, () -> GaiaProfile.connection(home)).getMessage().contains("needs GAIA to be running"));
        state("{\"pid\": 1}");
        assertTrue(assertThrows(IllegalStateException.class, () -> GaiaProfile.connection(home)).getMessage().contains("no port"));
        state("{ not json");
        assertTrue(assertThrows(IllegalStateException.class, () -> GaiaProfile.connection(home)).getMessage().contains("could not be read"));
    }

    @Test
    void aProfileNameIsAPlainNameAndNoProfileChangesNothing() {
        assertEquals(Map.of(), Env.profile(Map.of()));
        assertThrows(IllegalStateException.class, () -> Env.profile(Map.of("CHAT_PROFILE", "../x")));
    }
}
