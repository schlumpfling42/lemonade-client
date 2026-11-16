package net.rka.ai.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireLogTest {

    @TempDir
    Path dir;

    private static WireLog.Entry entry(int n) {
        return new WireLog.Entry(n, "#" + n + " · 2 messages", "Call " + n, "=== user ===\nline\nwith \"quotes\"", "=== text ===\ndone", "[]");
    }

    @Test
    void theCallsOfAConversationComeBackAsTheyWereSaved() {
        WireLog log = new WireLog(dir);

        log.save("c1", List.of(entry(1), entry(2)));

        assertEquals(List.of(entry(1), entry(2)), log.load("c1"));
        assertTrue(log.load("another").isEmpty(), "every conversation has its own calls");
    }

    @Test
    void onlyTheNewestCallsAreKeptAndADeletedConversationLeavesNothing() {
        WireLog log = new WireLog(dir);
        List<WireLog.Entry> many = new ArrayList<>();
        for (int i = 1; i <= WireLog.MAX_SAVED + 10; i++) many.add(entry(i));

        log.save("c1", many);

        List<WireLog.Entry> loaded = log.load("c1");
        assertEquals(WireLog.MAX_SAVED, loaded.size());
        assertEquals(11, loaded.getFirst().n());
        log.delete("c1");
        assertTrue(log.load("c1").isEmpty());
    }

    @Test
    void anIdThatIsAPathStaysInTheFolderAndAFileThatCannotBeReadGivesNoCalls() throws Exception {
        WireLog log = new WireLog(dir);

        log.save("../outside", List.of(entry(1)));

        assertTrue(java.nio.file.Files.list(dir).allMatch(p -> p.getParent().equals(dir)));
        java.nio.file.Files.writeString(dir.resolve("broken.json"), "{ not json");
        assertTrue(log.load("broken").isEmpty());
    }
}
