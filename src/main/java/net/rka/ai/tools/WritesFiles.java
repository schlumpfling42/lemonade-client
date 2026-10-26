package net.rka.ai.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Tool} method that creates, changes, moves or deletes files (or the conversation's
 * memory). Every call needs the user's approval unless the whole tool is trusted with a
 * {@code tool:<name>} entry ({@link TrustedCommands#trustsTool}); even then it asks once
 * {@link WebContent} has entered the current turn. The file tools are also confined to the trusted
 * folder, trusted or not.
 *
 * <p>Like the other marker annotations it has no behavior itself: {@link ToolRegistry#of} records
 * which tools carry it, and {@link ToolRegistry#needsApproval} uses that list. Used on
 * {@code writeFile}, {@code createDirectory}, {@code movePath}, {@code copyFile} and {@code deletePath}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WritesFiles {
}
