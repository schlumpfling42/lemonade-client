package net.rka.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the client knows that the model does not: recipes selected by the goal, lessons from failures, and the checks for a new project. */
class KnowledgeBookTest {

    @TempDir
    Path dir;

    private KnowledgeBook book() {
        return KnowledgeBook.load(dir.resolve("recipes"), dir.resolve("lessons.json"));
    }

    @Test
    void theSpringBootRecipeComesWithAGoalAboutASpringBootProjectAndNotWithOthers() {
        String text = book().forGoal("Create a new Spring Boot project called spring-boot-test in C:\\Data\\Projects with a GET /hello endpoint", 2_500);

        assertTrue(text.startsWith("### spring-boot-new"), text);
        assertTrue(text.contains("start.spring.io") && text.contains("gradlew.bat") && text.contains("type=gradle-project"), text);
        assertEquals("", book().forGoal("Fix the bug in Counter.increment", 2_500));
        assertEquals("", book().forGoal("", 2_500));
    }

    @Test
    void aKeywordMatchesWholeWordsAndIgnoresCaseAndSeparators() {
        assertFalse(book().forGoal("a SPRING-BOOT app", 2_500).isEmpty());
        assertFalse(book().forGoal("use Spring.Boot please", 2_500).isEmpty());
        assertEquals("", book().forGoal("springbooting around", 2_500));
    }

    @Test
    void theRecipeIsCutToTheBudget() {
        String text = book().forGoal("a new spring boot project", 300);

        assertTrue(text.length() < 340, text.length() + "");
        assertTrue(text.endsWith("[... cut ...]"), text);
    }

    @Test
    void aRecipeOfTheUserIsAddedAndOneWithTheSameNameReplacesTheBuiltIn() throws Exception {
        Files.createDirectories(dir.resolve("recipes"));
        Files.writeString(dir.resolve("recipes").resolve("react-new.md"), "---\nkeywords: new react app, vite\n---\nUse npm create vite@latest NAME -- --template react-ts.");
        Files.writeString(dir.resolve("recipes").resolve("spring-boot-new.md"), "---\nkeywords: spring boot\n---\nMine.");

        assertTrue(book().forGoal("create a new React app", 2_500).contains("npm create vite"));
        String spring = book().forGoal("a spring boot service", 2_500);
        assertTrue(spring.contains("Mine.") && !spring.contains("start.spring.io"), spring);
    }

    @Test
    void aFileWithoutAHeaderOrKeywordsIsNotARecipe() {
        assertTrue(KnowledgeBook.parse("x", "just text").isEmpty());
        assertTrue(KnowledgeBook.parse("x", "---\ntitle: a\n---\nbody").isEmpty());
        assertTrue(KnowledgeBook.parse("x", "---\nkeywords: a\n---\n").isEmpty());
        assertEquals(List.of("a b", "c"), KnowledgeBook.parse("x", "---\nkeywords: A-B, C\n---\nbody").orElseThrow().keywords());
    }

    @Test
    void aLessonIsKeptOnceAndComesBackForAGoalThatSharesItsWords() {
        KnowledgeBook book = book();
        book.learn("Create a Spring Boot project with Maven", "A setup command failed: Invoke-WebRequest dlcdn.apache.org -> exit code 1.");
        book.learn("Create a Spring Boot project with Maven", "A setup command failed: Invoke-WebRequest dlcdn.apache.org -> exit code 1.");

        assertEquals(1, book().readLessons().size());
        String again = book().forGoal("Set up a Spring Boot project using Maven and Java", 2_500);
        assertTrue(again.contains("What failed before") && again.contains("dlcdn.apache.org"), again);
        assertFalse(book().forGoal("Change the colour of the title in style.css", 2_500).contains("dlcdn"));
    }

    @Test
    void aGoalWithTooFewWordsLearnsNothingAndOnlyTheLatestLessonsAreKept() {
        KnowledgeBook book = book();
        book.learn("ok", "x");
        book.learn("the and for", "nothing significant");
        assertEquals(0, book.readLessons().size());

        for (int i = 0; i < KnowledgeBook.MAX_LESSONS + 5; i++) book.learn("Deploy the gateway service", "lesson " + i);
        assertEquals(KnowledgeBook.MAX_LESSONS, book.readLessons().size());
        assertEquals("lesson " + (KnowledgeBook.MAX_LESSONS + 4), book.readLessons().getLast().text());
    }

    @Test
    void thePlanInstructionsAboutExistingCodeAreLeftOutForNewFilesAndTheMarkersNeverReachTheModel() throws Exception {
        String prompt = "Plan.\n- keep this\n[[existing]]- patch: a few lines\n- replace: a section[[/existing]]\n- and this\n[[existing]]- imports[[/existing]]\n- last\n";

        assertEquals("Plan.\n- keep this\n- and this\n- last\n", CodingGraph.planInstructions(prompt, false));
        String withCode = CodingGraph.planInstructions(prompt, true);
        assertTrue(withCode.contains("- patch: a few lines") && withCode.contains("- imports") && !withCode.contains("[["), withCode);
        // the real plan prompt: much shorter without them, and the same text with them
        String plan = new String(KnowledgeBookTest.class.getResourceAsStream("/net/rka/ai/agent/skills/coding.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(plan.contains("[[existing]]") && plan.contains("[[/existing]]"));
    }

    @Test
    void aNewProjectIsToldFromAGoalThatNamesAnExistingOne() {
        assertTrue(CodingGraph.looksLikeNewProject("Create a new Spring Boot project called demo in C:\\Data\\Projects\\demo."));
        assertTrue(CodingGraph.looksLikeNewProject("Set up a fresh Node app for the dashboard"));
        assertTrue(CodingGraph.looksLikeNewProject("I need a new project for the API"));
        assertFalse(CodingGraph.looksLikeNewProject("Add a new method to the existing project"));
        assertFalse(CodingGraph.looksLikeNewProject("Add a button to the dialog"));
        assertFalse(CodingGraph.looksLikeNewProject("Fix the project's build"));
        assertFalse(CodingGraph.looksLikeNewProject(null));
    }
}
