package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.agent.CodingPlan.Change;
import net.rka.ai.tools.DateTimeTools;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.MemoryTools;
import net.rka.ai.tools.SshTools;
import net.rka.ai.tools.TerminalTools;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tools.TrustedCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing the model's plan and deciding which tool calls the approved plan covers. */
class CodingPlanTest {

    private final ToolRegistry tools = ToolRegistry.of(
            new FileTools(), new TerminalTools(), new SshTools(), new MemoryTools(), new DateTimeTools());

    @TempDir
    Path folder;

    private static ToolExecutionRequest call(String tool, String arguments) {
        return ToolExecutionRequest.builder().id("1").name(tool).arguments(arguments).build();
    }

    /** A plan with one step holding the given commands/files JSON fragments and no verify commands. */
    private static CodingPlan plan(String commands, String files) {
        return CodingPlan.parse("{\"summary\":\"s\",\"steps\":[{\"description\":\"d\",\"commands\":[" + commands
                + "],\"files\":[" + files + "]}],\"verify\":[]}");
    }

    private boolean covers(CodingPlan plan, String tool, String arguments) {
        return plan.covers(call(tool, arguments), folder, TrustedCommands.of(""), tools);
    }

    private static final String NPM_IN_FRONTEND = "{\"command\":\"npm install\",\"workingDirectory\":\"frontend\"}";

    @Test
    void parsesFencedJson() {
        CodingPlan plan = CodingPlan.parse("Here is the plan:\n```json\n"
                + "{\"summary\":\"Add a page\",\"steps\":[{\"description\":\"Install\","
                + "\"commands\":[{\"command\":\"npm install\",\"workingDirectory\":\"frontend\"}],"
                + "\"files\":[{\"path\":\"frontend/index.html\",\"change\":\"create\"}]}],"
                + "\"verify\":[{\"command\":\"npm test\",\"workingDirectory\":\"frontend\"}]}\n```\nGood luck!");
        assertEquals(1, plan.steps().size());
        assertEquals("frontend", plan.steps().get(0).commands().get(0).workingDirectory());
        assertEquals(Change.CREATE, plan.steps().get(0).files().get(0).change());
        assertEquals("npm test", plan.verify().get(0).command());
        // the JSON we write ourselves reads back to the same plan, with lower-case change values
        assertTrue(plan.toJson().contains("\"create\""));
        assertEquals(plan, CodingPlan.parse(plan.toJson()));
    }

    @Test
    void rejectsPlanWithoutSteps() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CodingPlan.parse("{\"summary\":\"x\",\"steps\":[]}"));
        assertTrue(e.getMessage().contains("steps"), e.getMessage());
    }

    @Test
    void rejectsInvalidJsonAndBadValues() {
        assertThrows(IllegalArgumentException.class, () -> CodingPlan.parse("no plan here"));
        assertThrows(IllegalArgumentException.class, () -> CodingPlan.parse("{\"steps\": [oops]}"));
        assertThrows(IllegalArgumentException.class, () -> plan("{\"command\":\" \"}", ""));
        assertThrows(IllegalArgumentException.class, () -> plan("", "{\"path\":\"\",\"change\":\"create\"}"));
        assertThrows(IllegalArgumentException.class, () -> plan("", "{\"path\":\"a\",\"change\":\"rename\"}"));
    }

    @Test
    void exactCommandInPlannedFolderIsCovered() {
        CodingPlan plan = plan(NPM_IN_FRONTEND, "");
        assertTrue(covers(plan, "runCommand", "{\"command\":\"npm  install\",\"workingDirectory\":\"frontend\"}"));
        assertTrue(covers(plan, "runCommand", "{\"command\":\"npm install\",\"workingDirectory\":\"./frontend/\"}"));
        // the same command in another folder is a different command
        assertFalse(covers(plan, "runCommand", "{\"command\":\"npm install\"}"));
    }

    @Test
    void commandOutsideFolderIsNotCovered() {
        CodingPlan plan = plan("{\"command\":\"npm install\",\"workingDirectory\":\"\"}", "");
        assertTrue(covers(plan, "runCommand", "{\"command\":\"npm install\"}"));
        assertFalse(covers(plan, "runCommand", "{\"command\":\"npm install\",\"workingDirectory\":\"..\"}"));
        String outside = folder.getRoot().toString().replace("\\", "\\\\");
        assertFalse(covers(plan, "runCommand", "{\"command\":\"npm install\",\"workingDirectory\":\"" + outside + "\"}"));
        // a plan that itself names a directory outside the folder never covers anything
        CodingPlan outsidePlan = plan("{\"command\":\"npm install\",\"workingDirectory\":\"..\"}", "");
        assertFalse(covers(outsidePlan, "runCommand", "{\"command\":\"npm install\",\"workingDirectory\":\"..\"}"));
    }

    @Test
    void otherCommandIsNotCoveredButUserTrustedIs() {
        CodingPlan plan = plan(NPM_IN_FRONTEND, "");
        assertFalse(covers(plan, "runCommand", "{\"command\":\"npm install lodash\",\"workingDirectory\":\"frontend\"}"));
        assertFalse(covers(plan, "runCommand", "{\"command\":\"git status\"}"));
        assertTrue(plan.covers(call("runCommand", "{\"command\":\"git status\"}"), folder,
                TrustedCommands.of("git status"), tools));
    }

    @Test
    void verifyCommandsAreCovered() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"s\",\"steps\":[{\"description\":\"d\"}],"
                + "\"verify\":[{\"command\":\"npm test\",\"workingDirectory\":\"\"}]}");
        assertTrue(covers(plan, "runCommand", "{\"command\":\"npm test\"}"));
    }

    @Test
    void fileUnderPlannedFolderIsCovered() {
        CodingPlan plan = plan("", "{\"path\":\"frontend/\",\"change\":\"create\"}");
        assertTrue(covers(plan, "writeFile", "{\"path\":\"frontend/src/app.css\",\"content\":\"x\"}"));
        assertTrue(covers(plan, "createDirectory", "{\"path\":\"frontend/src\"}"));
        assertTrue(covers(plan, "copyFile", "{\"from\":\"outside.txt\",\"to\":\"frontend/a.txt\"}"));
        assertFalse(covers(plan, "writeFile", "{\"path\":\"backend/x.java\",\"content\":\"x\"}"));
        assertFalse(covers(plan, "writeFile", "{\"path\":\"../x.java\",\"content\":\"x\"}"));
        // an exact file entry does not cover siblings or children
        CodingPlan exact = plan("", "{\"path\":\"a.txt\",\"change\":\"modify\"}");
        assertTrue(covers(exact, "writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"));
        assertFalse(covers(exact, "writeFile", "{\"path\":\"a.txt/b\",\"content\":\"x\"}"));
        assertFalse(covers(exact, "writeFile", "{\"path\":\"b.txt\",\"content\":\"x\"}"));
    }

    @Test
    void deleteNeedsDeleteEntryAndNeverTheFolderItself() {
        CodingPlan created = plan("", "{\"path\":\"frontend/\",\"change\":\"create\"}");
        assertFalse(covers(created, "deletePath", "{\"path\":\"frontend\"}"));
        assertFalse(covers(created, "deletePath", "{\"path\":\"frontend/a.txt\"}"));
        CodingPlan deleting = plan("", "{\"path\":\"old.txt\",\"change\":\"delete\"},"
                + "{\"path\":\"build/\",\"change\":\"delete\"}");
        assertTrue(covers(deleting, "deletePath", "{\"path\":\"old.txt\"}"));
        assertTrue(covers(deleting, "deletePath", "{\"path\":\"build/classes/A.class\"}"));
        assertFalse(covers(deleting, "deletePath", "{\"path\":\"build\"}"));
        assertFalse(covers(deleting, "deletePath", "{\"path\":\"\"}"));
        assertFalse(covers(deleting, "deletePath", "{\"path\":\"other.txt\"}"));
    }

    @Test
    void moveNeedsMoveAndCreateEntries() {
        CodingPlan both = plan("", "{\"path\":\"a.txt\",\"change\":\"move\"},{\"path\":\"b/\",\"change\":\"create\"}");
        assertTrue(covers(both, "movePath", "{\"from\":\"a.txt\",\"to\":\"b/a.txt\"}"));
        assertFalse(covers(both, "movePath", "{\"from\":\"c.txt\",\"to\":\"b/c.txt\"}"));
        assertFalse(covers(both, "movePath", "{\"from\":\"a.txt\",\"to\":\"elsewhere/a.txt\"}"));
        CodingPlan onlyCreate = plan("", "{\"path\":\"b/\",\"change\":\"create\"}");
        assertFalse(covers(onlyCreate, "movePath", "{\"from\":\"a.txt\",\"to\":\"b/a.txt\"}"));
    }

    @Test
    void readOnlyToolsAreAlwaysCovered() {
        CodingPlan plan = plan(NPM_IN_FRONTEND, "");
        assertTrue(covers(plan, "readFile", "{\"path\":\"a.txt\"}"));
        assertTrue(covers(plan, "listFiles", "{}"));
        assertFalse(covers(plan, "runRemoteCommand", "{\"host\":\"h\",\"command\":\"ls\"}"));
        assertFalse(covers(plan, "remember", "{\"fact\":\"x\"}"));
        assertFalse(covers(plan, "forget", "{\"id\":\"f1\"}"));
    }

    @Test
    void malformedArgumentsAreNotCovered() {
        CodingPlan plan = plan(NPM_IN_FRONTEND, "{\"path\":\"a.txt\",\"change\":\"create\"}");
        assertFalse(covers(plan, "runCommand", "not json"));
        assertFalse(covers(plan, "writeFile", "{}"));
    }

    @Test
    void nullArgumentsAreNotCovered() {
        CodingPlan plan = plan(NPM_IN_FRONTEND, "{\"path\":\"a.txt\",\"change\":\"create\"}");
        // Jackson throws IllegalArgumentException for null text; covers must fail closed instead
        assertFalse(covers(plan, "runCommand", null));
        assertFalse(covers(plan, "writeFile", null));
        assertEquals(List.of(), plan.itemsDoneBy(0, call("writeFile", null), folder));
    }

    @Test
    void newlineIsNotNormalizedAway() {
        CodingPlan plan = plan("{\"command\":\"git add .\",\"workingDirectory\":\"\"}", "");
        // Spaces and tabs are collapsed
        assertTrue(covers(plan, "runCommand", "{\"command\":\"git \\t add  .\"}"));
        assertTrue(covers(plan, "runCommand", "{\"command\":\" git add . \"}"));
        // A newline separates two commands: "git add" then "." is not the planned command
        assertFalse(covers(plan, "runCommand", "{\"command\":\"git add\\n.\"}"));
        assertFalse(covers(plan, "runCommand", "{\"command\":\"git\\r\\nadd .\"}"));
        // TrustedCommands keeps its own (wider) normalization
        assertEquals("git add .", TrustedCommands.normalize("git\nadd ."));
    }

    @Test
    void itemsDoneByMatchesOnlyTheStepsOwnItems() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"s\",\"steps\":["
                + "{\"description\":\"Install\",\"commands\":[" + NPM_IN_FRONTEND + "]},"
                + "{\"description\":\"Page\",\"files\":[{\"path\":\"frontend/\",\"change\":\"create\"},"
                + "{\"path\":\"old.txt\",\"change\":\"move\"}]}],\"verify\":[]}");
        assertEquals(List.of("`npm install` in `frontend`"), plan.items(0));
        assertEquals(List.of("create `frontend/`", "move `old.txt`"), plan.items(1));
        ToolExecutionRequest npm = call("runCommand", NPM_IN_FRONTEND);
        assertEquals(List.of("`npm install` in `frontend`"), plan.itemsDoneBy(0, npm, folder));
        assertEquals(List.of(), plan.itemsDoneBy(1, npm, folder)); // step 1's command doesn't carry out step 2
        assertEquals(List.of("create `frontend/`"),
                plan.itemsDoneBy(1, call("writeFile", "{\"path\":\"frontend/a.html\",\"content\":\"x\"}"), folder));
        assertEquals(List.of("move `old.txt`", "create `frontend/`"),
                plan.itemsDoneBy(1, call("movePath", "{\"from\":\"old.txt\",\"to\":\"frontend/old.txt\"}"), folder));
        assertEquals(List.of(), plan.itemsDoneBy(1, call("readFile", "{\"path\":\"frontend/a.html\"}"), folder));
    }

    @Test
    void markdownMarksSkippedSteps() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"s\",\"steps\":[{\"description\":\"First\"},"
                + "{\"description\":\"Second\"},{\"description\":\"Third\"}]}");
        String markdown = plan.toMarkdown(2, Set.of(2));
        assertTrue(markdown.contains("1. ✓ First"), markdown);
        assertTrue(markdown.contains("2. ✗ skipped Second"), markdown);
        assertTrue(markdown.contains("3. Third"), markdown);
    }

    @Test
    void planReviewShowsTheRevisionReasonAndDoneSteps() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"s\",\"steps\":[{\"description\":\"First\"},"
                + "{\"description\":\"Second\"}]}");
        String first = PlanReview.first(plan).toMarkdown();
        assertFalse(first.contains("Revised plan"), first);
        assertFalse(first.contains("✓"), first);

        String revised = new PlanReview(plan, 1, Set.of(), "writeFile b.txt: not in the plan\nrunCommand x: not in the plan",
                "", true).toMarkdown();
        assertTrue(revised.startsWith("Revised plan: writeFile b.txt: not in the plan; runCommand x: not in the plan\n"), revised);
        assertTrue(revised.contains("1. ✓ First") && revised.contains("2. Second"), revised);

        String asked = new PlanReview(plan, 0, Set.of(), "", "use Kotlin", true).toMarkdown();
        assertTrue(asked.startsWith("Revised plan: you asked: use Kotlin"), asked);
    }

    @Test
    void markdownMarksDoneSteps() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"Do it\",\"steps\":["
                + "{\"description\":\"First\",\"commands\":[{\"command\":\"npm install\",\"workingDirectory\":\"frontend\"}]},"
                + "{\"description\":\"Second\",\"files\":[{\"path\":\"a.txt\",\"change\":\"modify\"}]}],"
                + "\"verify\":[{\"command\":\"npm test\",\"workingDirectory\":\"\"}]}");
        String markdown = plan.toMarkdown(1);
        assertEquals(1, markdown.chars().filter(c -> c == '✓').count(), markdown);
        assertTrue(markdown.indexOf('✓') < markdown.indexOf("Second"), markdown);
        assertTrue(markdown.contains("`npm install`"), markdown);
        assertTrue(markdown.contains("modify"), markdown);
        assertTrue(markdown.contains("`npm test`"), markdown);
    }
}
