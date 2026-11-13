package net.rka.ai.tools.code.java;

import net.rka.ai.tools.outline.SectionEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace: Java files in memory, found by name, with the references between types, changed in memory and written back. */
class JavaWorkspaceTest {

    private static final String LEDGER = """
            package p;

            public class Ledger {
                private int size;

                public static Ledger parse(String text) {
                    return new Ledger();
                }

                public int size() {
                    return size;
                }
            }
            """;

    private static final String ACCOUNT = """
            package p;

            import java.util.List;

            public class Account extends Base implements Comparable<Account> {
                private final Ledger ledger = Ledger.parse("x");

                public void add(Ledger other, List<String> names) {
                    int n = other.size() + ledger.size();
                }

                public int compareTo(Account o) {
                    return 0;
                }

                public class Inner {
                    Ledger inner() {
                        return ledger;
                    }
                }
            }
            """;

    private static final String BASE = "package p;\n\nabstract class Base {\n}\n";

    private static final String REPORT = """
            package q;

            import p.*;
            import p.Account.Inner;

            class Report {
                Account account;
                Inner inner;
                Missing missing;

                String print(Ledger ledger) {
                    return Account.Inner.class.getName() + ledger;
                }
            }
            """;

    @TempDir
    Path dir;

    private JavaWorkspace workspace;

    private void write(String path, String text) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @BeforeEach
    void open() throws Exception {
        write("src/p/Ledger.java", LEDGER);
        write("src/p/Account.java", ACCOUNT);
        write("src/p/Base.java", BASE);
        write("src/q/Report.java", REPORT);
        write("src/q/Broken.java", "package q;\nclass Broken {\n    int x\n}\n");
        write("build/generated/Skipped.java", "package gen;\nclass Skipped {}\n");
        workspace = JavaWorkspace.open(dir);
    }

    @AfterEach
    void close() {
        workspace.close();
    }

    @Test
    void allJavaFilesAreReadAndFoundByNameExceptTheSkippedFolders() {
        assertEquals(List.of("src/p/Account.java", "src/p/Base.java", "src/p/Ledger.java", "src/q/Broken.java", "src/q/Report.java"),
                workspace.paths());
        assertEquals("src/p/Ledger.java", workspace.find("p.Ledger").orElseThrow().path());
        assertEquals("p.Ledger", workspace.find("Ledger").orElseThrow().qualifiedName());
        assertEquals("p.Account.Inner", workspace.find("Account.Inner").orElseThrow().qualifiedName());
        assertTrue(workspace.find("Skipped").isEmpty());
        assertEquals(3, workspace.type("p.Ledger").orElseThrow().type().members().size());
        // a file that is not Java keeps its text and outline and says why it isn't read
        assertTrue(workspace.parseError("src/q/Broken.java").startsWith("Line "), workspace.parseError("src/q/Broken.java"));
        assertTrue(workspace.typesIn("src/q/Broken.java").isEmpty());
        assertTrue(workspace.outline("src/q/Broken.java").isPresent());
    }

    @Test
    void referencesAreResolvedTheWayJavaResolvesNames() {
        List<Reference> usages = workspace.usagesOf("p.Ledger");

        assertTrue(usages.contains(new Reference("p.Account", "p.Account.ledger", "p.Ledger", "", Reference.Kind.DECLARATION)), usages.toString());
        assertTrue(usages.contains(new Reference("p.Account", "p.Account.ledger", "p.Ledger", "parse", Reference.Kind.BODY)), usages.toString());
        assertTrue(usages.contains(new Reference("p.Account", "p.Account.add(Ledger,List)", "p.Ledger", "", Reference.Kind.DECLARATION)), usages.toString());
        assertTrue(usages.contains(new Reference("p.Account.Inner", "p.Account.Inner.inner()", "p.Ledger", "", Reference.Kind.DECLARATION)), usages.toString());
        // an import on demand
        assertTrue(usages.contains(new Reference("q.Report", "q.Report.print(Ledger)", "p.Ledger", "", Reference.Kind.DECLARATION)), usages.toString());
        // supertypes, and a nested type reached by a single import and by Outer.Inner
        assertTrue(workspace.usagesOf("p.Base").contains(new Reference("p.Account", "", "p.Base", "", Reference.Kind.SUPERTYPE)));
        List<Reference> inner = workspace.usagesOf("p.Account.Inner");
        assertTrue(inner.stream().anyMatch(r -> r.fromMember().equals("q.Report.inner")), inner.toString());
        assertTrue(inner.stream().anyMatch(r -> r.fromMember().equals("q.Report.print(Ledger)") && r.kind() == Reference.Kind.BODY), inner.toString());
        // a name that is no type of the project (a JDK type, a class that doesn't exist) points nowhere
        assertTrue(workspace.usagesOf("java.util.List").isEmpty());
        assertTrue(workspace.referencesOf("q.Report").stream().noneMatch(r -> r.toType().endsWith("Missing")));

        assertEquals(List.of("p.Account", "q.Report"), List.copyOf(workspace.usersOf("p.Ledger")));
        assertEquals(List.of("p.Account", "p.Base", "p.Ledger"), // itself too: Comparable<Account>, compareTo(Account)
                
                workspace.referencesOf("p.Account").stream().map(Reference::toType).distinct().sorted().toList());
    }

    @Test
    void callsThroughVariablesAreFoundByName() {
        List<String> callers = workspace.possibleCallers("p.Ledger", "size");

        assertTrue(callers.contains("p.Account.add(Ledger,List) (by name)"), callers.toString());
        assertEquals(List.of("p.Account.ledger"), workspace.possibleCallers("p.Ledger", "parse"));
    }

    @Test
    void changesAreMadeInMemoryAndWrittenByFlush() throws Exception {
        String md5 = workspace.outline("src/p/Ledger.java").orElseThrow().md5();

        JavaWorkspace.EditResult result = workspace.edit("src/p/Ledger.java", "Ledger.size()", SectionEdit.Mode.REPLACE,
                "public int size() {\n    return Account.COUNT + size;\n}", md5);

        assertEquals("src/p/Ledger.java", result.path());
        assertEquals(List.of("src/p/Ledger.java"), workspace.dirtyPaths());
        assertEquals(LEDGER, Files.readString(dir.resolve("src/p/Ledger.java")), "the file is not touched");
        assertTrue(workspace.text("src/p/Ledger.java").orElseThrow().contains("Account.COUNT"));
        // the references follow the change: Ledger now uses Account
        assertTrue(workspace.usagesOf("p.Account").contains(new Reference("p.Ledger", "p.Ledger.size()", "p.Account", "COUNT", Reference.Kind.BODY)));
        // an edit with an old md5 is refused
        assertThrows(IllegalArgumentException.class, () -> workspace.edit("src/p/Ledger.java", "Ledger.size()",
                SectionEdit.Mode.REPLACE, "int size() { return 1; }", md5));

        JavaWorkspace.FlushResult flushed = workspace.flush();

        assertEquals(List.of("src/p/Ledger.java"), flushed.written());
        assertTrue(flushed.ok());
        assertTrue(workspace.dirtyPaths().isEmpty());
        assertTrue(Files.readString(dir.resolve("src/p/Ledger.java")).contains("return Account.COUNT + size;"));
        // our own write is not a change to take in again
        assertFalse(workspace.sync().any());
    }

    @Test
    void newAndDeletedFilesAreWrittenAndRemovedByFlush() throws Exception {
        workspace.setText("src/p/Extra.java", "package p;\n\nclass Extra {\n    Ledger ledger;\n}\n");
        workspace.delete("src/p/Base.java");

        assertTrue(workspace.has("src/p/Extra.java"));
        assertFalse(workspace.has("src/p/Base.java"));
        assertTrue(workspace.find("Extra").isPresent());
        assertTrue(workspace.find("Base").isEmpty());
        assertTrue(Files.exists(dir.resolve("src/p/Base.java")));
        assertFalse(Files.exists(dir.resolve("src/p/Extra.java")));

        JavaWorkspace.FlushResult flushed = workspace.flush();

        assertEquals(List.of("src/p/Extra.java"), flushed.written());
        assertEquals(List.of("src/p/Base.java"), flushed.deleted());
        assertTrue(Files.exists(dir.resolve("src/p/Extra.java")));
        assertFalse(Files.exists(dir.resolve("src/p/Base.java")));
        assertTrue(workspace.dirtyPaths().isEmpty());
    }

    @Test
    void whatChangesOnDiskIsTakenIn() throws Exception {
        // a changed file, a new file (which makes the unresolved Missing resolve) and a deleted file
        write("src/p/Ledger.java", LEDGER.replace("return size;", "return size + 1; // changed outside"));
        write("src/q/Missing.java", "package q;\n\nclass Missing {\n}\n");
        Files.delete(dir.resolve("src/p/Base.java"));

        JavaWorkspace.SyncResult result = workspace.sync();

        assertEquals(List.of("src/q/Missing.java"), result.added());
        assertEquals(List.of("src/p/Ledger.java"), result.changed());
        assertEquals(List.of("src/p/Base.java"), result.removed());
        assertTrue(workspace.text("src/p/Ledger.java").orElseThrow().contains("changed outside"));
        assertTrue(workspace.usagesOf("q.Missing").contains(new Reference("q.Report", "q.Report.missing", "q.Missing", "", Reference.Kind.DECLARATION)));
        assertTrue(workspace.usagesOf("p.Base").isEmpty(), "the supertype is gone, so is the reference to it");
        assertFalse(workspace.sync().any());
    }

    @Test
    void aFileChangedOnDiskWhileItHasChangesInMemoryIsAConflictAndIsNotOverwritten() throws Exception {
        String md5 = workspace.outline("src/p/Ledger.java").orElseThrow().md5();
        workspace.edit("src/p/Ledger.java", "Ledger.size()", SectionEdit.Mode.REPLACE, "public int size() {\n    return 7;\n}", md5);
        write("src/p/Ledger.java", LEDGER + "// the user's edit\n");

        JavaWorkspace.SyncResult result = workspace.sync();
        JavaWorkspace.FlushResult flushed = workspace.flush();

        assertEquals(List.of("src/p/Ledger.java"), result.conflicts());
        assertEquals(List.of("src/p/Ledger.java"), flushed.conflicts());
        assertEquals(List.of(), flushed.written());
        assertTrue(Files.readString(dir.resolve("src/p/Ledger.java")).endsWith("// the user's edit\n"), "the user's file is kept");
        assertTrue(workspace.text("src/p/Ledger.java").orElseThrow().contains("return 7;"), "so are the changes in memory");

        assertEquals(List.of("src/p/Ledger.java"), workspace.flush(true).written());
        assertTrue(Files.readString(dir.resolve("src/p/Ledger.java")).contains("return 7;"));
    }

    @Test
    void discardingBringsTheFilesBack() throws Exception {
        workspace.setText("src/p/Extra.java", "package p;\nclass Extra {}\n");
        workspace.delete("src/p/Base.java");
        String md5 = workspace.outline("src/p/Ledger.java").orElseThrow().md5();
        workspace.edit("src/p/Ledger.java", "Ledger.size()", SectionEdit.Mode.REPLACE, "public int size() {\n    return 7;\n}", md5);

        workspace.discardAll();

        assertTrue(workspace.dirtyPaths().isEmpty());
        assertFalse(workspace.has("src/p/Extra.java"));
        assertTrue(workspace.has("src/p/Base.java"));
        assertEquals(LEDGER, workspace.text("src/p/Ledger.java").orElseThrow());
    }

    @Test
    void theWatcherTakesInChangesWithoutAskingForALook() throws Exception {
        workspace.watch();
        Thread.sleep(100);

        write("src/q/Added.java", "package q;\n\nclass Added {\n}\n");
        write("src/p/Ledger.java", LEDGER.replace("return size;", "return 99;"));
        long deadline = System.currentTimeMillis() + 900; // shorter than the time after which reads look by themselves
        while (System.currentTimeMillis() < deadline && !(workspace.has("src/q/Added.java")
                && workspace.text("src/p/Ledger.java").orElse("").contains("return 99;"))) {
            Thread.sleep(20);
        }

        assertTrue(workspace.has("src/q/Added.java"));
        assertTrue(workspace.text("src/p/Ledger.java").orElseThrow().contains("return 99;"));
    }

    @Test
    void projectFilesReadAndWriteJavaInMemoryAndOtherFilesOnDisk() throws Exception {
        JavaWorkspaces workspaces = new JavaWorkspaces(false);
        try {
            ProjectFiles files = new ProjectFiles(dir.toRealPath(), workspaces);
            Path ledger = dir.toRealPath().resolve("src/p/Ledger.java");
            Path notes = dir.toRealPath().resolve("notes/todo.txt");

            files.writeString(ledger, "package p;\nclass Ledger {}\n");
            files.writeString(notes, "later");

            assertEquals("package p;\nclass Ledger {}\n", files.readString(ledger));
            assertEquals(LEDGER, Files.readString(ledger), "a Java file is not on disk yet");
            assertEquals("later", Files.readString(notes), "another file is");
            assertEquals(List.of("src/p/Ledger.java"), files.dirtyPaths());

            files.flush();

            assertEquals("package p;\nclass Ledger {}\n", Files.readString(ledger));
            assertTrue(files.isRegularFile(ledger) && files.exists(notes));
        } finally {
            workspaces.close();
        }
    }
}
