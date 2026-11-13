package net.rka.ai;

import net.rka.ai.ui.ChatWindow;

/**
 * The chat window with manual control of the model: a second window shows every request before it is sent, so it can be changed, and
 * every answer before the application uses it, so it can be changed too. Everything else is as in {@link Launcher}.
 * Start it with {@code ./gradlew runManual}.
 */
public class ManualLauncher {

    public static void main(String[] args) {
        System.setProperty("chat.manual", "true"); // read by ChatWindow, which builds the gate and the window
        javafx.application.Application.launch(ChatWindow.class, args);
    }
}
