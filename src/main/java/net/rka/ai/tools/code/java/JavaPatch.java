package net.rka.ai.tools.code.java;

import net.rka.ai.tools.TextPatch;

import java.util.List;

/**
 * A partial change to one member of a Java type, written like a patch ({@link TextPatch}): hunks found by their context, which
 * must be in the member exactly once. The result goes through the same checks as every other change to a Java file (valid Java, the
 * same declarations, no variable lost or declared twice).
 */
public final class JavaPatch {

    private JavaPatch() {
    }

    /**
     * @param memberSpec the member to patch, named like {@link JavaMembers#find}
     * @param patch      the hunks
     * @return what was done, in a sentence
     * @throws IllegalArgumentException when the patch is not understood, does not fit, or leaves the member in a state a change may not
     */
    public static String apply(JavaWorkspace workspace, JavaWorkspace.TypeInfo type, String memberSpec, String patch) {
        JavaClass root = type.type();
        JavaMembers.Found found = JavaMembers.find(root, memberSpec);
        int from = found.member().startLine();
        int to = found.member().endLine();
        TextPatch.Result patched = TextPatch.apply(List.of(root.lines(from, to).split("\n", -1)), from, patch);
        String newMember = String.join("\n", patched.lines());

        String before = root.source().replace("\r\n", "\n");
        String after = JavaStructure.splice(before, from, to, newMember);
        String problem = JavaStructure.problem(before, after, from, to, newMember, JavaStructure.Effect.REPLACES);
        if (problem == null) problem = JavaBlockEdit.memberProblem(before, after, from, to);
        if (problem != null) throw new IllegalArgumentException("The patch would not leave " + JavaStructure.signature(found.member()) + " as it should be: " + problem);
        workspace.changeLines(type.path(), from, to, newMember, JavaStructure.Effect.REPLACES);
        return "Patched " + JavaStructure.signature(found.member()) + " of " + found.owner().name() + " with " + patched.hunks() + " hunk(s): +"
                + patched.added() + " −" + patched.removed() + " lines";
    }
}
