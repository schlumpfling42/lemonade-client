package net.rka.ai.tools.code.java;

import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Java file as the workspace holds it: its text (which may be newer than the file), the outline of that text (the
 * parts an edit names), the types read from it with the names they use, and what is known of the file on disk. Immutable:
 * a change makes a new unit.
 *
 * @param path     the file relative to the project folder, with {@code /}
 * @param text     the text in memory
 * @param outline  its outline (always made, also when the file doesn't parse)
 * @param types    the top-level types, empty when the text is not Java the reader understands
 * @param error    why it is not read, or ""
 * @param uses     the dotted names the types use
 * @param stamp    the file as it was when the text was read from it; null for a file that only exists in memory
 * @param dirty    whether the text differs from the file (changed in memory, created, or deleted)
 * @param deleted  whether the file is to be deleted when the workspace writes its changes
 * @param conflict whether the file on disk changed while the text in memory had changes of its own
 * @param scope    how simple names in this file resolve
 */
record JavaUnit(String path, String text, Outline outline, List<JavaClass> types, String error, List<JavaUses.Use> uses,
                DiskStamp stamp, boolean dirty, boolean deleted, boolean conflict, Scope scope) {

    /** What identifies a version of a file on disk. */
    record DiskStamp(long modified, long size) {
    }

    /** The names a file's simple names resolve through, as Java does: its own types, single imports, its package, imports on demand. */
    record Scope(String packageName, Map<String, String> singleImports, List<String> onDemand, Map<String, String> ownTypes) {
    }

    static JavaUnit parse(String path, String text, DiskStamp stamp, boolean dirty) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        Outline outline = Outlines.of(name, text);
        try {
            List<JavaClass> types = JavaSourceReader.read(text);
            return new JavaUnit(path, text, outline, types, "", JavaUses.of(types), stamp, dirty, false, false, scopeOf(types));
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new JavaUnit(path, text, outline, List.of(), message, List.of(), stamp, dirty, false, false,
                    new Scope("", Map.of(), List.of(), Map.of()));
        }
    }

    private static Scope scopeOf(List<JavaClass> types) {
        String pkg = types.isEmpty() ? "" : types.getFirst().packageName();
        Map<String, String> single = new LinkedHashMap<>();
        List<String> onDemand = new ArrayList<>();
        if (!types.isEmpty()) {
            for (String i : types.getFirst().imports()) {
                if (i.startsWith("static ") || i.startsWith("module ")) continue;
                if (i.endsWith(".*")) onDemand.add(i.substring(0, i.length() - 2));
                else single.put(i.substring(i.lastIndexOf('.') + 1), i);
            }
        }
        Map<String, String> own = new LinkedHashMap<>();
        for (JavaClass t : types) ownTypes(t, t.qualifiedName(), own);
        return new Scope(pkg, single, onDemand, own);
    }

    private static void ownTypes(JavaClass type, String qualified, Map<String, String> own) {
        own.putIfAbsent(type.name(), qualified);
        for (JavaClass nested : type.nestedClasses()) ownTypes(nested, qualified + "." + nested.name(), own);
    }

    /** The qualified names of all types in the unit, nested ones included, with the type each one is. */
    Map<String, JavaClass> declared() {
        Map<String, JavaClass> all = new LinkedHashMap<>();
        for (JavaClass t : types) declare(t, t.qualifiedName(), all);
        return all;
    }

    private static void declare(JavaClass type, String qualified, Map<String, JavaClass> all) {
        all.putIfAbsent(qualified, type);
        for (JavaClass nested : type.nestedClasses()) declare(nested, qualified + "." + nested.name(), all);
    }

    /** Every word that takes part in a dotted name of the unit: the units that use a word are looked at again when a type of that name appears or goes. */
    Set<String> words() {
        Set<String> words = new LinkedHashSet<>();
        for (JavaUses.Use use : uses) words.addAll(use.chain());
        return words;
    }

    JavaUnit with(String newText, DiskStamp newStamp, boolean newDirty) {
        return parse(path, newText, newStamp, newDirty);
    }

    JavaUnit flagged(boolean newDirty, boolean newDeleted, boolean newConflict) {
        return new JavaUnit(path, text, outline, types, error, uses, stamp, newDirty, newDeleted, newConflict, scope);
    }

    JavaUnit stamped(DiskStamp newStamp) {
        return new JavaUnit(path, text, outline, types, error, uses, newStamp, false, false, false, scope);
    }

    String md5() {
        return outline.md5();
    }
}
