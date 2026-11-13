package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaUnknownTypesTest {

    private static final String FILE = """
            package net.rka.ai.ui;

            import java.io.ByteArrayInputStream;
            import java.util.List;
            import javafx.scene.image.Image;

            class View {
                <T> T pick(List<T> items) { return items.get(0); }
                static class Inner {}
            }
            """;

    @Test
    void anInventedClassIsReported() {
        String written = "byte[] png = MermaidRenderer.render(source);\nImage img = new Image(new ByteArrayInputStream(png));";
        assertEquals(List.of("MermaidRenderer"), JavaUnknownTypes.unknown(FILE, written, Set.of("ChatWindow")));
    }

    @Test
    void aClassThatIsNotImportedIsReported() {
        assertEquals(List.of("ImageView"), JavaUnknownTypes.unknown(FILE, "ImageView view = new ImageView(img);", Set.of()));
    }

    @Test
    void knownClassesAreNotReported() {
        String written = "String s = String.valueOf(1);\nList<String> l = List.of();\nInner i = new Inner();\n"
                + "ChatWindow w = new ChatWindow();\nMath.max(1, 2);\nThread t = null; // Ghost\nString g = \"Ghost.run()\";";
        assertEquals(List.of(), JavaUnknownTypes.unknown(FILE, written, Set.of("ChatWindow")));
    }

    @Test
    void typeParametersAndAnnotationsAreFine() {
        assertEquals(List.of(), JavaUnknownTypes.unknown(FILE, "@Override\nT first = pick(items);", Set.of()));
    }

    @Test
    void aWildcardImportOfALibraryTheAppLacksMeansNothingIsReported() {
        String file = "package a;\nimport com.vendor.*;\nclass A {}\n";
        assertEquals(List.of(), JavaUnknownTypes.unknown(file, "Widget w = new Widget();", Set.of()));
    }

    @Test
    void aWildcardOfTheJdkResolvesItsClasses() {
        String file = "package a;\nimport java.util.*;\nclass A {}\n";
        assertEquals(List.of(), JavaUnknownTypes.unknown(file, "Map<String, String> m = new HashMap<>();", Set.of()));
        assertEquals(List.of("Ghost"), JavaUnknownTypes.unknown(file, "Ghost g = new Ghost();", Set.of()));
    }
}
