package net.rka.ai;

import net.rka.ai.ui.ChatWindow;

/**
 * Entry point for the JavaFX UI. It must not extend javafx.application.Application,
 * otherwise the JVM refuses to start JavaFX from the classpath ("JavaFX runtime
 * components are missing").
 *
 * <p>Why: when the main class extends {@code Application}, the Java launcher checks that JavaFX is
 * loaded as named <em>modules</em>. This project puts JavaFX on the plain classpath (simpler with
 * Gradle), so a separate main class that only calls {@code Application.launch} sidesteps the check.
 * {@code launch} then creates the JavaFX thread and calls {@link ChatWindow#init()} and
 * {@link ChatWindow#start}.
 *
 * <p>Not implemented yet: packaging as a native app ({@code jpackage}) with its own runtime, so it
 * starts from the Start menu without Gradle; that would also allow proper JavaFX modules.
 */
public class Launcher {

    public static void main(String[] args) {
        javafx.application.Application.launch(ChatWindow.class, args);
    }
}
