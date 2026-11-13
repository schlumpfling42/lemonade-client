package net.rka.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The build command found from a project's files. */
class VerifyDefaultsTest {

    @TempDir
    Path folder;

    private void write(String name, String content) throws Exception {
        Files.writeString(folder.resolve(name), content);
    }

    private static List<CodingPlan.Command> command(String text) {
        return List.of(new CodingPlan.Command(text, ""));
    }

    @Test
    void aBareJavacInAProjectWithABuildToolBecomesTheBuild() throws Exception {
        write("build.gradle", "plugins {}");
        List<CodingPlan.Command> normalized = VerifyDefaults.normalize(folder,
                List.of(new CodingPlan.Command("javac -d build/classes src/A.java src/B.java", "")));
        assertEquals(VerifyDefaults.detect(folder), normalized);
        assertEquals(List.of(new CodingPlan.Command("echo hi", "")), VerifyDefaults.normalize(folder, command("echo hi")), "other commands stay");
    }

    @Test
    void gradleWrapperBeatsPlainGradle() throws Exception {
        write("build.gradle", "");
        write("gradlew", "");
        write("gradlew.bat", "");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        assertEquals(command(windows ? ".\\gradlew.bat build" : "./gradlew build"), VerifyDefaults.detect(folder));
    }

    @Test
    void aModelsWrapperCommandBecomesTheOneThisComputerRuns() throws Exception {
        write("gradlew", "");
        write("gradlew.bat", "");
        for (String written : List.of("gradlew build", "./gradlew build", ".\\gradlew build", "gradle build", "gradlew.bat build")) {
            assertEquals(command(".\\gradlew.bat build").getFirst(),
                    VerifyDefaults.normalize(folder, new CodingPlan.Command(written, ""), true), written);
            assertEquals(command("./gradlew build").getFirst(),
                    VerifyDefaults.normalize(folder, new CodingPlan.Command(written, ""), false), written);
        }
        // Without a wrapper in the working directory, or for any other command, nothing changes
        assertEquals(command("npm test").getFirst(), VerifyDefaults.normalize(folder, new CodingPlan.Command("npm test", ""), true));
        assertEquals("javac -d $env:TEMP\\verify-javac -sourcepath src src/Main.java",
                VerifyDefaults.normalize(folder, new CodingPlan.Command("javac src/Main.java", ""), true).command(), "one file sees its neighbours");
        assertEquals("javac -d /tmp/verify-javac -sourcepath . Main.java",
                VerifyDefaults.normalize(folder, new CodingPlan.Command("javac Main.java", ""), false).command());
        assertEquals("javac -d out src/A.java", VerifyDefaults.normalize(folder, new CodingPlan.Command("javac -d out src/A.java", ""), true).command());
        // a wildcard is not one file, and a command that runs the classes needs them where javac put them
        Path plain = Files.createDirectory(folder.resolve("plain")); // no build file
        assertEquals("javac *.java", VerifyDefaults.normalize(plain, new CodingPlan.Command("javac *.java", ""), true).command());
        List<CodingPlan.Command> pair = List.of(new CodingPlan.Command("javac Main.java", ""), new CodingPlan.Command("java Main", ""));
        assertEquals(pair, VerifyDefaults.normalize(plain, pair));
        Files.createDirectories(folder.resolve("sub"));
        assertEquals(new CodingPlan.Command("gradle build", "sub"), VerifyDefaults.normalize(folder, new CodingPlan.Command("gradle build", "sub"), true));
    }

    @Test
    void gradleMavenNpmCargoGo() throws Exception {
        write("build.gradle.kts", "");
        assertEquals(command("gradle build"), VerifyDefaults.detect(folder));
        Files.delete(folder.resolve("build.gradle.kts"));

        write("pom.xml", "");
        assertEquals(command("mvn -q test"), VerifyDefaults.detect(folder));
        Files.delete(folder.resolve("pom.xml"));

        write("package.json", "{\"scripts\": {\"build\": \"vite build\"}}");
        assertEquals(command("npm run build"), VerifyDefaults.detect(folder));
        write("package.json", "{\"scripts\": {\"test\": \"vitest\", \"build\": \"vite build\"}}");
        assertEquals(command("npm test"), VerifyDefaults.detect(folder));
        Files.delete(folder.resolve("package.json"));

        write("Cargo.toml", "");
        assertEquals(command("cargo build"), VerifyDefaults.detect(folder));
        Files.delete(folder.resolve("Cargo.toml"));

        write("go.mod", "");
        assertEquals(command("go build ./..."), VerifyDefaults.detect(folder));
    }

    @Test
    void javaFilesWithoutABuildToolAreCompiledTogether() throws Exception {
        Files.createDirectories(folder.resolve("src/shop"));
        write("src/shop/A.java", "package shop; class A {}");

        List<CodingPlan.Command> detected = VerifyDefaults.detect(folder);

        assertEquals(1, detected.size());
        assertTrue(detected.get(0).command().startsWith("javac -d "), detected.toString());
        assertTrue(detected.get(0).command().contains("verify-javac"), detected.toString());
    }

    @Test
    void anUnknownProjectHasNoCommand() throws Exception {
        write("notes.txt", "x");
        assertEquals(List.of(), VerifyDefaults.detect(folder));
    }

    @Test
    void theBuildOfTheProjectThatHoldsTheChangedFileRunsThere() throws Exception {
        Files.createDirectories(folder.resolve("projects/app/src"));
        write("projects/app/pom.xml", "<project/>");
        write("projects/app/src/A.java", "class A {}");
        Files.createDirectories(folder.resolve("projects/other"));
        write("projects/other/B.java", "class B {}");

        List<CodingPlan.Command> commands = VerifyDefaults.detectFor(folder, List.of("projects/app/src/A.java"));

        assertEquals(List.of(new CodingPlan.Command("mvn -q test", "projects/app")), commands);
    }

    @Test
    void aBigOrHiddenPileOfJavaFilesIsNotACompiledProject() throws Exception {
        Files.createDirectories(folder.resolve(".gradle/caches"));
        for (int i = 0; i < VerifyDefaults.MAX_PLAIN_JAVA_FILES + 5; i++) write(".gradle/caches/S" + i + ".java", "class S" + i + " {}");
        write("notes.txt", "x");
        assertEquals(List.of(), VerifyDefaults.detect(folder), "hidden folders do not count");

        Files.createDirectories(folder.resolve("many"));
        for (int i = 0; i < VerifyDefaults.MAX_PLAIN_JAVA_FILES + 1; i++) write("many/M" + i + ".java", "class M" + i + " {}");
        assertEquals(List.of(), VerifyDefaults.detectFor(folder, List.of("many/M1.java")), "too many files for one small project");
    }
}
