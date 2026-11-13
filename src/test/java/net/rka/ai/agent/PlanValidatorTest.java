package net.rka.ai.agent;

import net.rka.ai.agent.CodingPlan.Action;
import net.rka.ai.agent.CodingPlan.Change;
import net.rka.ai.agent.CodingPlan.Command;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph's check of a plan against the trusted folder. */
class PlanValidatorTest {

    @TempDir
    Path folder;

    @BeforeEach
    void files() throws Exception {
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/App.java"), "class App {\n    int x;\n\n    void run() {\n    }\n}\n");
        Files.writeString(folder.resolve("build.gradle"), "plugins {}\n");
    }

    private static CodingPlan plan(List<Change> changes, List<Command> setup, List<Command> verify) {
        return new CodingPlan("s", List.of(), setup, changes, verify);
    }

    private static CodingPlan plan(Change... changes) {
        return plan(List.of(changes), List.of(), List.of());
    }

    private static Change change(String file, String section, Action action) {
        return new Change(file, section, action, "why");
    }

    @Test
    void aPatchMustFitItsSectionAndAJavaPatchNamesTheMember() {
        Change fits = new Change("src/App.java", "App.run()", Action.PATCH, "why", "", " void run() {\n+    two();\n }");
        Change nowhere = new Change("src/App.java", "App.run()", Action.PATCH, "why", "", "-    one();\n+    two();");
        Change noSection = new Change("src/App.java", "", Action.PATCH, "why", "", " void run() {\n+    two();");
        Change noPatch = new Change("src/App.java", "App.run()", Action.PATCH, "why");
        Change wholeFile = new Change("build.gradle", "", Action.PATCH, "why", "", " plugins {}\n+id 'java'");

        assertTrue(PlanValidator.validate(plan(fits), folder).valid());
        PlanValidator.Result unfit = PlanValidator.validate(plan(nowhere), folder);
        assertTrue(unfit.valid(), "a patch that does not fit a section that exists is not a reason to send the plan back");
        Change rewritten = unfit.plan().changes().get(0);
        assertEquals(Action.REPLACE, rewritten.action());
        assertEquals("App.run()", rewritten.section());
        assertTrue(rewritten.intent().contains("did not fit") && rewritten.intent().contains("+    two();"), rewritten.intent());
        assertTrue(PlanValidator.validate(plan(noSection), folder).errors().toString().contains("name the section the patch is in"));
        assertTrue(PlanValidator.validate(plan(noPatch), folder).errors().toString().contains("needs \"patch\""));
        assertTrue(PlanValidator.validate(plan(wholeFile), folder).valid(), "a file that is not Java is patched as a whole when no section is named");
    }

    @Test
    void aChangeToTheImportsOfAJavaFileIsLeftOutBecauseTheJobAddsImportsItself() {
        Change imports = new Change("src/App.java", "imports", Action.REPLACE, "import Alert");
        Change real = new Change("src/App.java", "App.run()", Action.REPLACE, "do it");

        PlanValidator.Result result = PlanValidator.validate(plan(imports, real), folder);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(1, result.plan().changes().size());
        assertEquals("App.run()", result.plan().changes().get(0).section());
    }

    @Test
    void aLongPatchIsLeftToTheEditStepSoThePlanStaysAList() {
        StringBuilder hunk = new StringBuilder(" void run() {\n");
        for (int i = 0; i < PlanValidator.MAX_PATCH_LINES + 1; i++) hunk.append("+    step").append(i).append("();\n");
        Change longPatch = new Change("src/App.java", "App.run()", Action.PATCH, "add the steps", "", hunk.append(" }").toString());

        PlanValidator.Result result = PlanValidator.validate(plan(longPatch), folder);

        assertTrue(result.valid(), result.errors().toString());
        Change change = result.plan().changes().get(0);
        assertEquals(Action.REPLACE, change.action());
        assertTrue(change.intent().startsWith("add the steps") && change.intent().contains("step8();"), "the sketch stays as guidance: " + change.intent());
    }

    @Test
    void startAndEndNeedAMethodOrConstructor() {
        assertTrue(PlanValidator.validate(plan(change("src/App.java", "run", Action.START), change("src/App.java", "App.run()", Action.END)), folder).valid());
        PlanValidator.Result onField = PlanValidator.validate(plan(change("src/App.java", "App.x", Action.START)), folder);
        assertFalse(onField.valid());
        assertTrue(onField.errors().getFirst().contains("body of a method or constructor"), onField.errors().toString());
        assertEquals(Action.START, CodingPlan.Action.fromJson("prepend"));
        assertEquals(Action.END, CodingPlan.Action.fromJson("insert-end"));
    }

    @Test
    void aValidPlanComesBackWithFullSectionIdsAndTheDefaultBuild() {
        PlanValidator.Result result = PlanValidator.validate(plan(
                change("src/App.java", "run", Action.REPLACE),          // shortened: App.run()
                change("./src/App.java", "App", Action.ADD),
                change("src/New.java", "", Action.CREATE)), folder);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(List.of("App.run()", "App", ""), result.plan().changes().stream().map(Change::section).toList());
        assertEquals("src/App.java", result.plan().changes().get(1).file()); // "./" is gone
        assertEquals(List.of(new Command("gradle build", "")), result.plan().verify()); // from build.gradle
    }

    @Test
    void laterChangesToAFileThePlanCreatesBecomePartOfTheCreateChange() {
        PlanValidator.Result result = PlanValidator.validate(plan(
                new Change("src/Calc.java", "", Action.CREATE, "a calculator"),
                new Change("./src/Calc.java", "Calc", Action.ADD, "add(int, int)"),     // the file has no sections yet
                new Change("src/Calc.java", "Calc.add(int,int)", Action.REPLACE, "make it static"),
                change("src/App.java", "App.run()", Action.REPLACE)), folder);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(2, result.plan().changes().size());
        assertEquals(new Change("src/Calc.java", "", Action.CREATE,
                "a calculator; add Calc: add(int, int); replace Calc.add(int,int): make it static"), result.plan().changes().get(0));
        assertEquals("App.run()", result.plan().changes().get(1).section());
    }

    @Test
    void theModelsOwnVerifyCommandIsKept() {
        PlanValidator.Result result = PlanValidator.validate(plan(List.of(change("src/App.java", "App.x", Action.DELETE)),
                List.of(), List.of(new Command("gradle test", ""))), folder);
        assertEquals(List.of(new Command("gradle test", "")), result.plan().verify());
    }

    @Test
    void filesAndSectionsMustExist() {
        PlanValidator.Result result = PlanValidator.validate(plan(
                change("src/Nope.java", "Nope", Action.REPLACE),
                change("src/App.java", "App.walk()", Action.REPLACE),
                change("src/App.java", "", Action.REPLACE),
                change("src/App.java", "App", Action.CREATE),             // exists
                change("../outside.java", "X", Action.REPLACE)), folder);

        assertFalse(result.valid());
        String errors = String.join("\n", result.errors());
        assertTrue(errors.contains("Change 1 (src/Nope.java): no such file"), errors);
        assertTrue(errors.contains("Change 2") && errors.contains("No section 'App.walk()'") && errors.contains("App.run()"), errors);
        assertTrue(errors.contains("Change 3") && errors.contains("name the section"), errors);
        assertTrue(errors.contains("Change 4") && errors.contains("already exists"), errors);
        assertTrue(errors.contains("Change 5") && errors.contains("outside the trusted folder"), errors);
    }

    @Test
    void addNamingAHeaderMeansTheClassAndAddNamingAPartMeansAfterIt() {
        PlanValidator.Result result = PlanValidator.validate(plan(
                change("src/App.java", "App.<header>", Action.ADD),    // what the model picks from the outline for "a new method"
                change("src/App.java", "App.run()", Action.ADD)), folder);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(new Change("src/App.java", "App", Action.ADD, "why"), result.plan().changes().get(0));
        assertEquals(new Change("src/App.java", "App.run()", Action.AFTER, "why"), result.plan().changes().get(1));
    }

    @Test
    void anEmptyOrTooLongPlanIsRefused() {
        assertTrue(String.join("\n", PlanValidator.validate(plan(), folder).errors()).contains("no changes"));
        List<Change> many = new ArrayList<>();
        for (int i = 0; i < PlanValidator.HARD_MAX_CHANGES + 1; i++) many.add(change("src/N" + i + ".java", "", Action.CREATE));
        assertTrue(String.join("\n", PlanValidator.validate(plan(many, List.of(), List.of()), folder).errors())
                .contains("at most " + PlanValidator.HARD_MAX_CHANGES));
    }

    @Test
    void aBigPlanBetweenTheLimitsIsAcceptedWithARemarkInsteadOfRefused() {
        List<Change> some = new ArrayList<>();
        for (int i = 0; i < PlanValidator.MAX_CHANGES + 1; i++) some.add(change("src/N" + i + ".java", "", Action.CREATE));

        PlanValidator.Result result = PlanValidator.validate(plan(some, List.of(), List.of()), folder);

        assertTrue(result.valid(), result.errors().toString());
        assertEquals(PlanValidator.MAX_CHANGES + 1, result.plan().changes().size());
        assertTrue(result.remarks().getFirst().contains("big plan"), result.remarks().toString());
    }

    @Test
    void aCommandThatChangesTheBuildToolsWrapperIsRefused() {
        PlanValidator.Result result = PlanValidator.validate(plan(List.of(change("src/App.java", "App.x", Action.DELETE)),
                List.of(new Command("./gradlew wrapper --gradle-version 8.14", "")), List.of()), folder);

        assertTrue(String.join("\n", result.errors()).contains("changes the build tool"), result.errors().toString());
    }

    @Test
    void commandsMayNotWriteFilesOrLeaveTheFolder() {
        PlanValidator.Result result = PlanValidator.validate(plan(List.of(change("src/App.java", "App.x", Action.DELETE)),
                List.of(new Command("echo hi > a.txt", ""), new Command("sed -i s/a/b/ f", ""), new Command("npm install", "..")),
                List.of(new Command("gradle build 2>&1", ""), new Command("", ""))), folder);

        String errors = String.join("\n", result.errors());
        assertTrue(errors.contains("`echo hi > a.txt` writes a file"), errors);
        assertTrue(errors.contains("`sed -i s/a/b/ f` writes a file"), errors);
        assertTrue(errors.contains("`npm install`: the working directory is outside"), errors);
        assertTrue(errors.contains("A command is blank"), errors);
        assertFalse(errors.contains("gradle build 2>&1"), errors); // a redirect of stderr is not a file write
    }

    @Test
    void aCommandThatChainsOthersWithAndAndIsSentBackToThePlanner() {
        PlanValidator.Result result = PlanValidator.validate(plan(List.of(change("src/App.java", "App.x", Action.DELETE)), List.of(),
                List.of(new Command("javac A.java && java A", ""), new Command("a || b", ""), new Command("echo \"a && b\"", ""),
                        new Command("javac A.java", ""))), folder);

        String errors = String.join("\n", result.errors());
        assertTrue(errors.contains("`javac A.java && java A` chains commands with && or ||"), errors);
        assertTrue(errors.contains("`a || b` chains"), errors);
        assertFalse(errors.contains("echo"), errors); // && inside quotes is text
        assertFalse(errors.contains("`javac A.java` chains"), errors);
    }
}
