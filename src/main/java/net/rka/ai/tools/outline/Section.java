package net.rka.ai.tools.outline;

/**
 * One named part of a file: whole lines {@code start}..{@code end} (1-based, inclusive).
 *
 * @param id        what the model passes to {@code readSection} / {@code editSection}; stable while the part's
 *                  name stays the same, e.g. {@code imports}, {@code Foo.LIMIT}, {@code Foo.run(String,int)}
 * @param kind      what the part is: {@code package}, {@code imports}, {@code class}, {@code header},
 *                  {@code field}, {@code constructor}, {@code method}, {@code heading}, {@code block}, ...
 * @param parentId  the id of the section this one is inside, or null at the top
 * @param container whether other sections lie inside this one (a class): {@code editSection}'s {@code end} mode
 *                  adds to it
 * @param label     a few words that tell sections of an unstructured file apart (the first line of a block);
 *                  empty where the id says it all
 */
public record Section(String id, String kind, int start, int end, String parentId, boolean container, String label) {

    public Section(String id, String kind, int start, int end, String parentId, boolean container) {
        this(id, kind, start, end, parentId, container, "");
    }

    public int lines() {
        return end - start + 1;
    }
}
