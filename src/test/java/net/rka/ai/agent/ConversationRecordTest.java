package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The job ledger, the name check, the health of a conversation and the reset of its context. */
class ConversationRecordTest {

    @TempDir
    Path folder;

    private static List<ChatMessage> withJob(String goal, String report) {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("j1").name(CodingGraph.START_TOOL)
                .arguments("{\"goal\":\"" + goal + "\"}").build();
        return List.of(UserMessage.from("add the menu button to the conversation rows, like the folder rows"), AiMessage.from(List.of(call)),
                ToolExecutionResultMessage.from(call, report), AiMessage.from("I invented `ChatWindowMenuButton` while the job was running."));
    }

    // ---- the job ledger ----

    @Test
    void theLedgerListsEachJobWithItsGoalAndHowItEndedFromTheReportNotFromTheModelsWords() {
        List<ChatMessage> messages = withJob("add the menu button", "Stopped: the plan could not be applied. Nothing was changed.\n\n## Plan\n...");

        String note = JobLedger.note(messages);

        assertTrue(note.contains("1. goal \"add the menu button\" → Stopped: the plan could not be applied. Nothing was changed."), note);
        assertTrue(!note.contains("invented"), "what the model said about the job is not in it");
        assertEquals("", JobLedger.note(List.of(UserMessage.from("hi"))));
    }

    // ---- the name check ----

    @Test
    void aClassLikeNameInBackticksThatIsNowhereInTheProjectIsNamedUnderTheAnswer() throws Exception {
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/ChatWindow.java"), "import javafx.scene.control.MenuButton;\nclass ChatWindow {\n    MenuButton menu;\n}\n");
        JavaWorkspaces workspaces = new JavaWorkspaces(false);
        try {
            String answer = "Use `ChatWindowMenuButton` instead of `MenuButton` in `ChatWindow`, and set `folder-menu` and `MAX_ITEMS`.";

            String note = NameCheck.note(answer, "", folder, workspaces);

            assertEquals("[Not found in the project: `ChatWindowMenuButton`. These names may be wrong: check them before relying on them.]", note);
            assertEquals("", NameCheck.note(answer, "please create ChatWindowMenuButton", folder, workspaces), "a name the user wrote is theirs");
            assertEquals("", NameCheck.note(answer + "\n\n" + note, "", folder, workspaces), "a note is not added twice");
            assertEquals("", NameCheck.note("Use `ChatWindowMenuButton`.", "", Files.createDirectory(folder.resolve("empty")), workspaces),
                    "a folder without Java files is not checked");
        } finally {
            workspaces.close();
        }
    }

    // ---- the health ----

    @Test
    void healthShowsWhatWentWrongLatelyAndHowFullTheContextIs() {
        List<ChatMessage> fine = List.of(UserMessage.from("hi"), AiMessage.from("Hello."));
        assertEquals(ConversationHealth.Level.OK, ConversationHealth.of(fine, 0, 16_384).level());

        List<ChatMessage> messages = new ArrayList<>(withJob("g", "Stopped: x"));
        messages.add(AiMessage.from("An answer.\n\n" + NameCheck.MARK + "`Foo`. These names may be wrong.]"));
        messages.add(AiMessage.from(AgentGraph.NO_CHANGES_AFTER_JOB));

        ConversationHealth health = ConversationHealth.of(messages, 0, 16_384);

        assertEquals(ConversationHealth.Level.DRIFTING, health.level());
        assertEquals(3, health.reasons().size(), health.reasons().toString());
        assertTrue(health.reasons().getFirst().contains("named things that are not in the project"), health.reasons().toString());

        String big = "x".repeat(60_000);
        ConversationHealth full = ConversationHealth.of(List.of(UserMessage.from(big)), 0, 16_384);
        assertEquals(ConversationHealth.Level.WATCH, full.level());
        assertTrue(full.reasons().getFirst().contains("% of the model's context"), full.reasons().toString());
    }

    // ---- the reset ----

    @Test
    void afterAResetWhatWentWrongBeforeItNoLongerCounts() {
        List<ChatMessage> history = new ArrayList<>(List.of(dev.langchain4j.data.message.SystemMessage.from("system"),
                UserMessage.from("add the menu button"), dev.langchain4j.data.message.AiMessage.from(AgentGraph.TOO_MANY_ROUNDS),
                UserMessage.from("again"), dev.langchain4j.data.message.AiMessage.from(AgentGraph.TOO_MANY_ROUNDS),
                UserMessage.from("again"), dev.langchain4j.data.message.AiMessage.from(AgentGraph.TOO_MANY_ROUNDS)));
        assertEquals(ConversationHealth.Level.DRIFTING, ConversationHealth.of(history, ConversationSummary.EMPTY, 65_536).level());

        ConversationSummary summary = ContextReset.summary(history, List.of());

        assertEquals(ConversationHealth.Level.OK, ConversationHealth.of(history, summary, 65_536).level(), "the model starts clean: so does the chip");
    }

    @Test
    void afterAResetTheNextMessageOfTheUserReachesTheModel() {
        // The window's history starts with the system prompt
        List<ChatMessage> history = new ArrayList<>(List.of(dev.langchain4j.data.message.SystemMessage.from("system"),
                UserMessage.from("add the menu button"), dev.langchain4j.data.message.AiMessage.from("done")));
        ConversationSummary summary = ContextReset.summary(history, List.of());

        history.add(UserMessage.from("now remove it again")); // typed after the reset
        List<ChatMessage> seen = SummaryMemory.view(history, summary);

        assertEquals(2, seen.size(), "the system prompt with the summary, and the new message: " + seen);
        assertEquals("now remove it again", ((UserMessage) seen.get(1)).singleText());
    }

    @Test
    void resetRebuildsTheContextFromTheUsersWordsTheTaskAndTheJobsWithoutWhatTheModelWrote() {
        List<ChatMessage> history = new ArrayList<>(withJob("add the menu button", "Done: every change is made and verified."));
        history.add(UserMessage.from("fix the buttons"));
        List<FactBook.Fact> facts = Objective.with(List.of(), "make the menu button of a conversation row look like the folder row's");

        ConversationSummary summary = ContextReset.summary(history, facts);

        assertEquals(1 + history.size(), summary.covered());
        assertTrue(summary.text().contains("The task the user is working on, in their words: make the menu button of a conversation row look like the folder row's"), summary.text());
        assertTrue(summary.text().contains("1. add the menu button to the conversation rows, like the folder rows\n2. fix the buttons"), summary.text());
        assertTrue(summary.text().contains("Done: every change is made and verified."), summary.text());
        assertTrue(!summary.text().contains("invented") && !summary.text().contains("ChatWindowMenuButton"), "nothing the model wrote: " + summary.text());
    }
}
