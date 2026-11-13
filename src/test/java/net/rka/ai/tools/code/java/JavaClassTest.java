package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The immutable model of a Java type. */
class JavaClassTest {

    private static JavaField field(Set<Modifier> modifiers) {
        return new JavaField("/** The names. */", List.of("@Nullable"), Visibility.PRIVATE, modifiers, "List<String>", "names", "");
    }

    private static JavaClass type(String pkg, String name, Set<Modifier> modifiers, List<JavaField> fields,
                                  List<JavaInitializer> initializers) {
        List<JavaMember> members = new ArrayList<>(fields);
        members.addAll(initializers);
        return new JavaClass(null, "/** Foo. */", pkg, List.of("java.util.List"), List.of(), Visibility.PUBLIC, modifiers, JavaKind.CLASS,
                name, "", null, List.of(), List.of(), List.of(), List.of(), members);
    }

    @Test
    void aTypeKeepsItsMembersInOrderAndGivesBackCopies() {
        List<JavaField> fields = new ArrayList<>(List.of(field(Set.of()), field(Set.of(Modifier.FINAL))));
        JavaClass foo = type("net.rka", "Foo", Set.of(), fields, List.of());
        fields.clear(); // the caller's list no longer matters

        assertEquals(2, foo.fields().size());
        assertEquals("net.rka.Foo", foo.qualifiedName());
        assertEquals("Foo", type("", "Foo", Set.of(), List.of(), List.of()).qualifiedName());
        assertEquals("", foo.extendsClass());
        assertThrows(UnsupportedOperationException.class, () -> foo.fields().add(field(Set.of())));
    }

    @Test
    void initializersAndCommentsAreKept() {
        JavaClass foo = type("", "Foo", Set.of(), List.of(field(Set.of())), List.of(
                new JavaInitializer("// loads the table", true, "TABLE.load();"), new JavaInitializer(null, false, null)));

        assertEquals(2, foo.initializers().size());
        assertTrue(foo.initializers().get(0).isStatic());
        assertEquals("// loads the table", foo.initializers().get(0).comment());
        assertEquals("", foo.initializers().get(1).comment());
        assertEquals("", foo.initializers().get(1).code());
        assertEquals("/** Foo. */", foo.comment());
        assertEquals("/** The names. */", foo.fields().getFirst().comment());
        assertEquals("", new JavaConstructor(null, List.of(), Visibility.PUBLIC, null, List.of(), List.of(), null, false).comment());
        assertEquals("", new JavaMethod(null, List.of(), Visibility.PUBLIC, Set.of(), "", "void", "run", List.of(), List.of(), null, null)
                .comment());
    }

    @Test
    void modifiersThatDontFitWhatTheyAreWrittenOnAreRefused() {
        assertTrue(field(Set.of(Modifier.STATIC, Modifier.FINAL)).isStatic());
        assertThrows(IllegalArgumentException.class, () -> field(Set.of(Modifier.ABSTRACT)));
        assertThrows(IllegalArgumentException.class, () -> new JavaMethod("", List.of(), Visibility.PUBLIC, Set.of(Modifier.TRANSIENT),
                "", "void", "run", List.of(), List.of(), "", ""));
        assertThrows(IllegalArgumentException.class, () -> type("", "Foo", Set.of(Modifier.VOLATILE), List.of(), List.of()));
    }

    @Test
    void namesAreRequiredAndKeywordsAreWhatSourceUses() {
        assertThrows(IllegalArgumentException.class, () -> type("", " ", Set.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new JavaParameter("int", null));
        assertEquals("", Visibility.PACKAGE.keyword());
        assertEquals("interface", JavaKind.INTERFACE.keyword());
        assertEquals("synchronized", Modifier.SYNCHRONIZED.keyword());
        JavaMethod run = new JavaMethod("", List.of("@Override"), Visibility.PUBLIC, Set.of(Modifier.ABSTRACT), "", "void", "run",
                List.of(new JavaParameter("int", "n")), List.of("IOException"), null, null);
        assertTrue(run.isAbstract());
        assertEquals("", run.code());
    }
}
