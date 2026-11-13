package net.rka.ai.tools.outline;

import java.util.List;

/**
 * Splits one kind of file into {@link Section}s. An outliner reads the text once, keeps no state between
 * files and gives the same answer for the same text, so an outline can be made again whenever it is needed
 * instead of being kept and kept in sync.
 */
interface Outliner {

    /** The sections, parents before their parts, in file order. */
    List<Section> sections(String text, List<String> lines);

    /** Whether the sections follow the file's own structure (a class's members) rather than plain blocks of lines. */
    boolean structured();
}
