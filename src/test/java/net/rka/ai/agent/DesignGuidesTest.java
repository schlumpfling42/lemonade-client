package net.rka.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignGuidesTest {

    @TempDir
    Path dir;

    @Test
    void aGuideAppliesWhenItsLanguageIsNamedOrAPlanFileHasItsExtension() {
        DesignGuides guides = new DesignGuides(null);
        assertEquals(Set.of("java"), guides.languagesFor("Create a Java project with Gradle", List.of()));
        assertEquals(Set.of("java"), guides.languagesFor("add a feature", List.of("src/Main.java")));
        assertEquals(Set.of(), guides.languagesFor("create a SvelteKit app; javascript only", List.of("web/app.js")));
    }

    @Test
    void yourRulesComeAfterTheBuiltInOnesAndTheStarterNoteAddsNothing() throws Exception {
        DesignGuides guides = new DesignGuides(dir);
        guides.createStarterFiles();
        assertTrue(Files.readString(dir.resolve("java.md")).contains("Your Java design rules"));
        String untouched = guides.text(Set.of("java"));
        assertTrue(untouched.contains("Instances over static"), untouched);
        assertFalse(untouched.contains("user's own rules"), untouched); // only the comment so far

        Files.writeString(dir.resolve("java.md"), "<!-- note -->\n- Records for every DTO.");
        String withRules = guides.text(Set.of("java"));
        assertTrue(withRules.indexOf("Instances over static") < withRules.indexOf("- Records for every DTO."), withRules);
        assertFalse(withRules.contains("note"), withRules);
    }

    @Test
    void filesThatArentALanguageNameAreIgnored() throws Exception {
        Files.writeString(dir.resolve(".md"), "- stray");
        Files.writeString(dir.resolve("my notes.md"), "- stray");
        DesignGuides guides = new DesignGuides(dir);
        assertEquals(Set.of("java"), guides.languages());
        assertEquals(Set.of("java"), guides.languagesFor("a java job", List.of("A.java")));
    }

    @Test
    void aLanguageCanHaveOnlyYourGuide() throws Exception {
        Files.writeString(dir.resolve("svelte.md"), "- Use runes.");
        DesignGuides guides = new DesignGuides(dir);
        assertEquals(Set.of("java", "svelte"), guides.languages());
        assertEquals(Set.of("svelte"), guides.languagesFor("build the svelte frontend", List.of()));
        assertTrue(guides.text(Set.of("svelte")).contains("- Use runes."));
        assertEquals("", DesignGuides.NONE.text(DesignGuides.NONE.languagesFor("java", List.of("A.java"))));
    }
}
