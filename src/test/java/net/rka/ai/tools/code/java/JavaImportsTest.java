package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaImportsTest {

    private static final String FILE = """
            package net.rka.ai.ui;

            import java.util.Map;
            import javafx.scene.paint.Color;

            class View {
            }
            """;

    @Test
    void importsOfJdkAndLibraryClassesAreAdded() {
        String written = "List<String> l = new ArrayList<>();\nImageView v = new ImageView();\nPath p = Path.of(\"a\");";
        String result = JavaImports.add(FILE, written, Set.of());

        assertTrue(result.contains("import java.util.List;"), result);
        assertTrue(result.contains("import java.util.ArrayList;"), result);
        assertTrue(result.contains("import java.nio.file.Path;"), result);
        assertTrue(result.contains("import javafx.scene.image.ImageView;"), result);
        // after the last import, before the class
        assertTrue(result.indexOf("import java.nio.file.Path;") < result.indexOf("class View"));
        assertTrue(result.indexOf("import javafx.scene.paint.Color;") < result.indexOf("import java.util.ArrayList;"));
    }

    @Test
    void anImportOfAProjectClassFromAPackageThatDoesNotExistIsTakenOut() {
        Set<String> project = Set.of("Renderer", "net.rka.ai.agent.Ledger");
        String text = "import renderer.Renderer;\nimport java.util.List;\nimport net.rka.ai.agent.Ledger;\nimport com.google.Unknown;\n\nclass R {}\n";

        assertEquals("import java.util.List;\nimport net.rka.ai.agent.Ledger;\nimport com.google.Unknown;\n\nclass R {}\n",
                JavaImports.dropInvented(text, project), "only the import of a project class under a wrong package goes");
        assertEquals("class R {}\n", JavaImports.dropInvented("class R {}\n", project));
    }

    @Test
    void aProjectClassOfAnotherPackageIsImportedAndOneOfTheSamePackageIsNot() {
        Set<String> project = Set.of("net.rka.ai.agent.Ledger", "net.rka.ai.ui.ChatWindow");
        String result = JavaImports.add(FILE, "Ledger a = null;\nChatWindow w = null;", project);

        assertTrue(result.contains("import net.rka.ai.agent.Ledger;"), result);
        assertFalse(result.contains("import net.rka.ai.ui.ChatWindow;"), result);
    }

    @Test
    void importedAndUnknownNamesAreLeftAlone() {
        String written = "Map<String, String> m = null;\nColor c = Color.RED;\nTotallyInvented t = null;";
        assertEquals(FILE, JavaImports.add(FILE, written, Set.of()));
    }

    @Test
    void aFileWithoutImportsGetsThemAfterThePackageLine() {
        String result = JavaImports.add("package a;\n\nclass A {\n}\n", "List<String> l = null;", Set.of());
        assertEquals("package a;\n\nimport java.util.List;\n\nclass A {\n}\n", result);
    }
}
