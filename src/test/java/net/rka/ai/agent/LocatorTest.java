package net.rka.ai.agent;

import net.rka.ai.tools.code.java.JavaWorkspace;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorTest {

    @TempDir
    Path folder;

    @Test
    void theMembersTheGoalIsAboutAreFoundWithTheirCodeAndTheirOutlineIds() throws Exception {
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/ChatWindow.java"), String.join("\n",
                "class ChatWindow {",
                "    Object folderMenu() {",
                "        MenuButton menu = new MenuButton(\"x\");",
                "        menu.getStyleClass().add(\"folder-menu\");",
                "        return menu;",
                "    }",
                "",
                "    Object conversationRow(String item) {",
                "        MenuButton menu = new MenuButton(\"...\", null, create, copy);",
                "        return menu;",
                "    }",
                "",
                "    void unrelated() {",
                "        int a = 1;",
                "    }",
                "}", ""));
        JavaWorkspaces workspaces = new JavaWorkspaces(false);
        try {
            JavaWorkspace workspace = workspaces.workspace(folder).orElseThrow();

            List<Locator.Candidate> places = Locator.find(workspace, "the menu button in the conversation row should look like the folder menu button", 5);

            assertEquals("ChatWindow.conversationRow(String)", places.getFirst().id(), places.toString());
            assertTrue(places.getFirst().excerpt().contains("new MenuButton(\"...\", null, create, copy)"));
            assertTrue(places.stream().anyMatch(p -> p.id().equals("ChatWindow.folderMenu()")), places.toString());
            assertTrue(places.stream().noneMatch(p -> p.id().contains("unrelated")), places.toString());
            String shown = Locator.render(places, 5000);
            assertTrue(shown.startsWith("--- src/ChatWindow.java · ChatWindow.conversationRow(String) · lines 8–11"), shown);
        } finally {
            workspaces.close();
        }
    }

    @Test
    void blocksOfFilesThatAreNotJavaAreFoundToo() throws Exception {
        Files.createDirectories(folder.resolve("src/main/resources"));
        Files.createDirectories(folder.resolve("build"));
        Files.writeString(folder.resolve("src/main/resources/chat.css"),
                ".folder-menu {\n    -fx-background-color: red;\n}\n\n.row-menu .menu-button {\n    -fx-padding: 2;\n}\n\n.unrelated {\n    -fx-opacity: 1;\n}\n");
        Files.writeString(folder.resolve("build/generated.css"), ".row-menu .menu-button { -fx-padding: 9; }\n");

        List<Locator.Candidate> places = Locator.findInTextFiles(folder, "the row menu button padding is too small", 5);

        assertEquals(1, places.size(), places.toString());
        assertEquals("src/main/resources/chat.css", places.getFirst().path());
        assertTrue(places.getFirst().excerpt().contains("-fx-padding: 2"));
        assertTrue(Locator.render(places, 5000).startsWith("--- src/main/resources/chat.css · lines 5–7"), Locator.render(places, 5000));
    }

    @Test
    void wordsAreLowerCaseStemmedAndWithoutFillerAndCamelCaseNamesAreSplit() {
        assertEquals(List.of("conversation", "row", "menu", "button", "folder"),
                List.copyOf(Locator.words("Make the conversationRow MenuButtons look like the folder")));
    }
}
