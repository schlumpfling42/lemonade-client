package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the agent's reply is coding work that belongs in a job. */
class CodingRedirectTest {

    private static final String REQUEST = "add a stop button";

    @TempDir
    Path folder;

    private final CodingRedirect redirect = new CodingRedirect();

    @BeforeEach
    void files() throws Exception {
        Files.writeString(folder.resolve("Window.java"), "class Window {}\n");
        Files.writeString(folder.resolve("notes.md"), "# notes\n");
        Files.writeString(folder.resolve("package.json"), "{}\n");
    }

    private static ToolExecutionRequest call(String tool, String arguments) {
        return ToolExecutionRequest.builder().id("c").name(tool).arguments(arguments).build();
    }

    private static ToolExecutionRequest edit(String path, int newTextLength) {
        return call("editSection", "{\"path\":\"" + path + "\",\"section\":\"s\",\"md5\":\"m\",\"newText\":\"" + "x".repeat(newTextLength) + "\"}");
    }

    @Test
    void aMessageThatStandsAloneIsTheWholeGoalAndOneThatPointsBackBringsTheEarlierOnes() {
        List<ChatMessage> conversation = List.of(UserMessage.from("add a stop button"), AiMessage.from("Done."),
                UserMessage.from("add a menu button"));

        String alone = goalOf(redirect.startCallForRequest(conversation, false));
        String continuing = goalOf(redirect.startCallForRequest(conversation, true));

        assertEquals("add a menu button", alone);
        assertTrue(continuing.contains("add a menu button") && continuing.contains("What the assistant said last: \"Done.\"") && continuing.contains("and only that"), continuing);
    }

    @Test
    void aShortFollowUpBringsTheGoalAndTheReportOfTheJobBeforeItOrWhatTheAssistantSaid() {
        ToolExecutionRequest job = ToolExecutionRequest.builder().id("j1").name(CodingGraph.START_TOOL)
                .arguments("{\"goal\":\"add a menu button to every conversation row\"}").build();
        List<ChatMessage> afterAJob = List.of(UserMessage.from("add a menu button to the conversation"), AiMessage.from(List.of(job)),
                ToolExecutionResultMessage.from(job, "Stopped: the plan could not be applied to ChatWindow.java"), AiMessage.from("The job could not finish."),
                UserMessage.from("continue"), AiMessage.from("Not sure what you mean."), UserMessage.from("implement the changes"));

        String goal = goalOf(redirect.startCallForRequest(afterAJob, true));

        assertTrue(goal.contains("\"implement the changes\"") && goal.contains("add a menu button to every conversation row")
                && goal.contains("the plan could not be applied to ChatWindow.java"), goal);

        List<ChatMessage> afterTalk = List.of(UserMessage.from("the conversation rows need a menu button like the folder rows"),
                AiMessage.from("Then I would give the row's MenuButton the folder-menu style and add create and copy items."), UserMessage.from("do it"));
        String talked = goalOf(redirect.startCallForRequest(afterTalk, true));
        assertTrue(talked.contains("What the assistant said last: \"Then I would give the row's MenuButton the folder-menu style"), talked);
    }

    @Test
    void aMessageThatPointsBackSaysWhatItIsAboutThroughTheTaskInTheUsersOwnWords() {
        List<ChatMessage> conversation = List.of(UserMessage.from("fix the buttons"), AiMessage.from("What do you mean?"), UserMessage.from("fix the buttons"));

        String goal = goalOf(redirect.startCallForRequest(conversation, true, "the menu button in a conversation row must look like the one of the folder row"));

        assertTrue(goal.contains("What the assistant said last: \"What do you mean?\"") && goal.contains("\"the menu button in a conversation row must look like the one of the folder row\""),
                "the answer before comes first; its length is too little, so the older task is added: " + goal);
        assertEquals("fix the buttons", goalOf(redirect.startCallForRequest(conversation, false, "something else")), "a message that stands alone is the goal");
    }

    @Test
    void implementThatMeansTheRecommendationJustGivenNotTheFirstRequestOfTheConversation() {
        ToolExecutionRequest job = ToolExecutionRequest.builder().id("j1").name(CodingGraph.START_TOOL)
                .arguments("{\"goal\":\"add a menu button to every conversation row\"}").build();
        String recommendation = "I recommend drawing the diagram with Java2D into an image and showing it in an ImageView: no node, no HTML, no WebView. "
                + "Steps: a small renderer class, a call in the code block of the markdown view, a test with a sample flowchart.";
        List<ChatMessage> conversation = List.of(UserMessage.from("add a menu button to the conversation rows, like the folder rows have"),
                AiMessage.from(List.of(job)), ToolExecutionResultMessage.from(job, "Done: every change is made and the build passes."),
                AiMessage.from("The menu button is in."), UserMessage.from("how should we show mermaid diagrams?"), AiMessage.from(recommendation),
                UserMessage.from("plan that"));

        String goal = goalOf(redirect.startCallForRequest(conversation, true, "add a menu button to the conversation rows, like the folder rows have"));

        assertTrue(goal.contains("\"plan that\"") && goal.contains("drawing the diagram with Java2D"), goal);
        assertTrue(!goal.contains("menu button"), "nothing of the outdated first task: " + goal);
    }

    private static String goalOf(Optional<ToolExecutionRequest> call) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.orElseThrow().arguments()).path("goal").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<ToolExecutionRequest> check(List<ChatMessage> earlier, ToolExecutionRequest... calls) {
        List<ChatMessage> messages = new java.util.ArrayList<>(List.of(UserMessage.from(REQUEST)));
        messages.addAll(earlier);
        return redirect.startCall(messages, AiMessage.from(List.of(calls)), folder);
    }

    @Test
    void aBigChangeOfExistingCodeStartsAJobWithTheUsersRequest() {
        ToolExecutionRequest job = check(List.of(), edit("Window.java", 300)).orElseThrow();
        assertEquals(CodingGraph.START_TOOL, job.name());
        assertEquals("{\"goal\":\"" + REQUEST + "\"}", job.arguments());
        assertTrue(job.id().startsWith("call_"));
        // the same for the other changing tools and for build files
        assertTrue(check(List.of(), call("writeFile", "{\"path\":\"Window.java\",\"overwrite\":true,\"content\":\""
                + "x".repeat(300) + "\"}")).isPresent());
        assertTrue(check(List.of(), call("editSection", "{\"path\":\"Window.java\",\"section\":\"Window\",\"md5\":\"m\",\"newText\":\""
                + "x".repeat(300) + "\"}")).isPresent());
        assertTrue(check(List.of(), edit("package.json", 300)).isPresent());
    }

    @Test
    void aTinyChangeStaysDirectUntilItIsTheSecondOne() {
        assertEquals(Optional.empty(), check(List.of(), edit("Window.java", 20)));
        // The turn's earlier change counts: a second small change is a job
        AiMessage earlier = AiMessage.from(List.of(edit("Window.java", 20)));
        ChatMessage result = ToolExecutionResultMessage.from(earlier.toolExecutionRequests().get(0), "ok");
        assertTrue(check(List.of(earlier, result), edit("Window.java", 20)).isPresent());
        // two changes in one reply are one too
        assertTrue(check(List.of(), edit("Window.java", 20), edit("package.json", 20)).isPresent());
    }

    @Test
    void textNewFilesAndReadsAreNeverRedirected() {
        assertEquals(Optional.empty(), check(List.of(), edit("notes.md", 5000)));                  // not code
        assertEquals(Optional.empty(), check(List.of(), edit("New.java", 5000)));                  // doesn't exist yet
        assertEquals(Optional.empty(), check(List.of(), call("writeFile", "{\"path\":\"New.java\",\"content\":\""
                + "x".repeat(5000) + "\"}")));
        assertEquals(Optional.empty(), check(List.of(), call("readFile", "{\"path\":\"Window.java\"}")));
        assertEquals(Optional.empty(), check(List.of(), call("outline", "{\"path\":\"Window.java\"}")));
        assertEquals(Optional.empty(), check(List.of(), edit("../outside.java", 5000)));
        assertEquals(Optional.empty(), check(List.of(), call("editSection", "not json")));
        assertEquals(Optional.empty(), redirect.startCall(List.of(UserMessage.from(REQUEST)), AiMessage.from("just text"), folder));
    }

    @Test
    void aShortFollowUpBringsTheEarlierRequestIntoTheGoal() {
        String request = "add a stop button to the chat window that cancels the running turn";
        List<ChatMessage> messages = List.of(UserMessage.from(request), AiMessage.from("Here is my idea."),
                UserMessage.from("implement it"));
        ToolExecutionRequest job = redirect.startCall(messages, AiMessage.from(List.of(edit("Window.java", 300))), folder).orElseThrow();
        String goal = job.arguments();
        assertTrue(goal.contains("implement it") && goal.contains(request), goal);
        assertTrue(goal.indexOf(request) < goal.indexOf("implement it") || goal.contains("latest message"), goal);
        // a full request on its own is the goal as it is
        List<ChatMessage> alone = List.of(UserMessage.from("earlier chatter"), AiMessage.from("ok"), UserMessage.from(request));
        assertEquals("{\"goal\":\"" + request + "\"}",
                redirect.startCall(alone, AiMessage.from(List.of(edit("Window.java", 300))), folder).orElseThrow().arguments());
    }

    @Test
    void anAnnouncementThatNamesASourceFileStartsAJob() {
        List<ChatMessage> messages = List.of(UserMessage.from(REQUEST));
        ToolExecutionRequest job = redirect.startCallForAnnouncement(messages,
                AiMessage.from("Let me make these changes to the ChatWindow.java file:")).orElseThrow();
        assertEquals("{\"goal\":\"" + REQUEST + "\"}", job.arguments());
        assertTrue(redirect.startCallForAnnouncement(messages, AiMessage.from("I'll update src/app/main.py now:")).isPresent());
        assertTrue(redirect.startCallForAnnouncement(messages, AiMessage.from("First I add it to package.json:")).isPresent());
        // no source file, or only text files: nothing to start a job for
        assertEquals(Optional.empty(), redirect.startCallForAnnouncement(messages, AiMessage.from("Here are the results:")));
        assertEquals(Optional.empty(), redirect.startCallForAnnouncement(messages, AiMessage.from("I'll edit notes.md next:")));
        assertEquals(Optional.empty(), redirect.startCallForAnnouncement(messages, AiMessage.from("Take a look at www.example.com:")));
    }

    @Test
    void aReplyThatAlreadyStartsAJobIsLeftAlone() {
        assertEquals(Optional.empty(), check(List.of(), call(CodingGraph.START_TOOL, "{\"goal\":\"g\"}"), edit("Window.java", 5000)));
    }
}
