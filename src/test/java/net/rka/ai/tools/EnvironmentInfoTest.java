package net.rka.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentInfoTest {

    private static final Instant START = Instant.parse("2026-10-18T10:00:00Z");

    @TempDir
    Path dir;

    /** Records each probe command and answers with canned output. */
    private final List<String> probed = new ArrayList<>();
    private Function<String, String> runner = command -> "exit code: 0\n--- stdout ---\nv1 of " + command;

    private EnvironmentInfo info(Clock clock) {
        return new EnvironmentInfo(dir.resolve("environment.json"), command -> {
            probed.add(command);
            return runner.apply(command);
        }, clock);
    }

    @Test
    void probesOnceThenUsesCache() {
        EnvironmentInfo info = info(Clock.fixed(START, ZoneOffset.UTC));
        String first = info.summary();
        String second = info.summary();
        assertEquals(EnvironmentInfo.PROBES.size(), probed.size());
        assertEquals(first, second);
        assertTrue(first.contains("node --version: v1 of node --version"), first);
    }

    @Test
    void staleAfterSevenDays() {
        info(Clock.fixed(START, ZoneOffset.UTC)).summary();
        info(Clock.fixed(START.plus(Duration.ofDays(6)), ZoneOffset.UTC)).summary();
        assertEquals(EnvironmentInfo.PROBES.size(), probed.size()); // still fresh after 6 days
        info(Clock.fixed(START.plus(Duration.ofDays(8)), ZoneOffset.UTC)).summary();
        assertEquals(2 * EnvironmentInfo.PROBES.size(), probed.size());
    }

    @Test
    void markStaleForcesProbe() {
        EnvironmentInfo info = info(Clock.fixed(START, ZoneOffset.UTC));
        info.summary();
        info.markStale();
        info.summary();
        assertEquals(2 * EnvironmentInfo.PROBES.size(), probed.size());
    }

    @Test
    void missingToolsAreReported() {
        runner = command -> command.equals("mvn -v")
                ? "exit code: 1\n--- stderr ---\nmvn : The term 'mvn' is not recognized as the name of a cmdlet"
                : "exit code: 0\n--- stdout ---\nok";
        String summary = info(Clock.fixed(START, ZoneOffset.UTC)).summary();
        assertTrue(summary.contains("mvn: not installed"), summary);
        assertFalse(summary.contains("node: not installed"), summary);
    }

    @Test
    void isProbeIsExact() {
        assertTrue(EnvironmentInfo.isProbe("node --version"));
        assertTrue(EnvironmentInfo.isProbe("node  --version"));
        assertFalse(EnvironmentInfo.isProbe("node --version; rm x"));
        assertFalse(EnvironmentInfo.isProbe("npm install"));
    }
}
