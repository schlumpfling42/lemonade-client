package net.rka.ai.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Tool} method that the user must approve before each call, because it can
 * change things outside the app (run commands, write files, call paid APIs). The agent graph
 * routes such calls through the approval subgraph instead of running them directly.
 *
 * <h2>How it works</h2>
 * This annotation has no behavior of its own. {@link ToolRegistry#of} reads it with reflection
 * (hence {@code RUNTIME} retention) and remembers the tool's name. The graph's router then asks
 * {@link ToolRegistry#needsApproval} for every tool call; marked calls go to the "approval" node,
 * where the graph pauses and {@code Agent} asks the user. Exception: commands on the trusted list
 * ({@link TrustedCommands}) skip the question, unless web content is in the turn.
 * Used today on {@code runCommand} and {@code runRemoteCommand}.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>A reason or risk level ({@code @RequiresApproval(reason = "...", risk = HIGH)}) that the dialog
 *       could show, or that could pick between "ask every time" and "ask once per conversation".</li>
 *   <li>"Always allow this in this conversation" in the dialog, which would add the command to the
 *       conversation's trusted commands.</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME) // must survive to run time: ToolRegistry reads it with reflection
@Target(ElementType.METHOD)
public @interface RequiresApproval {
}
