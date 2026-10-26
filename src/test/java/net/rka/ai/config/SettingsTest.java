package net.rka.ai.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SettingsTest {

    @Test
    void effectiveUsesTheConversationFolderAndBothCommandLists() {
        Settings global = new Settings(Path.of("/global"), List.of("Get-Date", "hostname"));
        Settings conversation = new Settings(Path.of("/project"), List.of("git status*", "hostname"));

        Settings effective = Settings.effective(global, conversation);

        assertEquals(Path.of("/project"), effective.workFolder());
        assertEquals(List.of("Get-Date", "hostname", "git status*"), effective.trustedCommands());
    }

    @Test
    void newConversationsStartInTheGlobalFolderWithNoExtraCommands() {
        Settings global = new Settings(Path.of("/global"), List.of("Get-Date"));
        assertEquals(new Settings(Path.of("/global"), List.of()), Settings.newConversation(global));
    }

    @Test
    void blankAndDuplicateCommandsAreDropped() {
        assertEquals(List.of("a", "b"), new Settings(null, List.of(" a ", "", "b", "a")).trustedCommands());
    }

    @Test
    void jsonRoundTrip() {
        Settings settings = new Settings(Path.of("/work").toAbsolutePath(), List.of("Get-Date", "git log*"));
        assertEquals(settings, Settings.fromJson(settings.toJson()));

        Settings noFolder = Settings.fromJson(new Settings(null, List.of("x")).toJson());
        assertNull(noFolder.workFolder());
        assertEquals(List.of("x"), noFolder.trustedCommands());
    }
}
