package net.rka.ai.tools.code.java;

import java.util.List;

/**
 * A piece of code with lines in a file and pieces inside it: a type holds its members, a method its statements, a loop
 * the statements it runs on each iteration. Types and members are blocks ({@link JavaMember}), and so are the statements
 * of a body ({@link Statement}); the statements of a member are built from its source on demand ({@link JavaBlocks}).
 */
public interface Block {

    /** The first line in the source, counted from 1; 0 for a block that was not read from source. */
    int startLine();

    /** The last line, both ends included. */
    int endLine();

    /** The blocks directly inside this one, in the order of the source. */
    List<? extends Block> children();
}
