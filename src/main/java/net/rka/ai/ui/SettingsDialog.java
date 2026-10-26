package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.config.Settings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Edits global settings or one conversation's settings: trusted folder and trusted commands. The global
 * settings also list the skills in use (with their errors) and reload them.
 *
 * <p>How it works: one form serves both levels (the public methods only change captions). It's a
 * JavaFX {@link Dialog} whose result converter turns the fields into a {@link Settings} record when
 * Save is pressed (or null for Cancel, giving an empty Optional). An event filter on Save keeps the
 * dialog open while the folder doesn't exist. The caller decides where to store the result
 * ({@code Settings.saveGlobal} or the conversation's file).
 *
 * <p>Not implemented yet: validating commands as you type (e.g. flag entries containing {@code ;}
 * that can never match), a list editor with add/remove buttons instead of a text area, and
 * showing which trusted commands were actually used recently.
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    /**
     * Global settings: the default folder for new conversations and commands trusted everywhere, and the
     * skills in use with a button to load them again.
     *
     * @param agent the agent whose skills are listed and reloaded
     * @param busy  whether a turn is running: the agent reloads its skills between turns only, so the button is off
     */
    static Optional<Settings> editGlobal(Window owner, Settings global, Agent agent, boolean busy) {
        return show(owner, "Global settings",
                "Apply to every conversation. The folder is the default for new conversations.",
                "Default trusted folder", global.workFolderOrDefault(),
                "Trusted commands in every conversation", global.trustedCommands(), List.of(), new SkillsSection(agent, busy));
    }

    /** One conversation's settings; the global commands are shown, since they apply too. */
    static Optional<Settings> editConversation(Window owner, Settings conversation, Settings global) {
        return show(owner, "Conversation settings",
                "Apply only to this conversation.",
                "Trusted folder", conversation.workFolderOrDefault(),
                "Extra trusted commands for this conversation", conversation.trustedCommands(),
                global.trustedCommands(), null);
    }

    /** Builds and shows the form; returns the edited settings on Save, or empty on Cancel. */
    private static Optional<Settings> show(Window owner, String title, String intro,
                                           String folderCaption, Path folder,
                                           String commandsCaption, List<String> commands,
                                           List<String> inherited, SkillsSection skills) {
        // Folder: a text field you can type into, plus Browse… for the native folder picker
        TextField folderField = new TextField(folder.toString());
        HBox.setHgrow(folderField, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.getStyleClass().add("browse");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(folderCaption);
            if (isDirectory(folderField.getText().strip())) {
                chooser.setInitialDirectory(new File(folderField.getText().strip())); // start where the field points
            }
            File picked = chooser.showDialog(browse.getScene().getWindow()); // null on Cancel
            if (picked != null) folderField.setText(picked.getAbsolutePath());
        });

        // Commands: one per line in a plain text area (the record's constructor drops blanks and duplicates)
        TextArea commandsArea = new TextArea(String.join("\n", commands));
        commandsArea.setPrefRowCount(8);
        commandsArea.getStyleClass().add("command");
        commandsArea.setPromptText("One command per line, e.g.\nGet-Date\ngit status*");

        Label folderHelp = help("File tools can read, write and delete inside this folder without asking; "
                + "paths outside it are refused. Terminal commands start here.");
        Label commandsHelp = help("Terminal and SSH commands that run without asking. One per line; * matches "
                + "any arguments. Commands containing ; & | < > ` $ ( ) { } always ask. "
                + "tool:<name> trusts a whole file tool, e.g. tool:writeFile (still only inside the trusted folder).");

        VBox content = new VBox(6, help(intro),
                caption(folderCaption), new HBox(6, folderField, browse), folderHelp,
                caption(commandsCaption), commandsArea, commandsHelp);
        if (!inherited.isEmpty()) {
            // In conversation settings, show which global commands apply anyway
            content.getChildren().add(help("Also trusted (global): " + String.join(", ", inherited)));
        }
        if (skills != null && skills.agent() != null) content.getChildren().add(skillsBox(skills));
        content.setPadding(new Insets(4, 0, 0, 0));

        // A Dialog<Settings>: its result type is what showAndWait() returns
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        Dialog<Settings> dialog = new Dialog<>();
        dialog.initOwner(owner); // centered over the main window, and modal to it
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(560);
        if (owner != null && owner.getScene() != null) {
            // Dialogs are separate windows; give them the app's stylesheet so they're themed too
            dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        // Keep the dialog open on a folder that doesn't exist
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        // An event filter runs before the button's own handler; consume() stops the dialog from closing
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!isDirectory(folderField.getText().strip())) {
                folderHelp.setText("This folder doesn't exist: " + folderField.getText().strip());
                folderHelp.getStyleClass().add("settings-error");
                e.consume();
            }
        });

        // Turns the pressed button into the dialog's result: new Settings on Save, null (= empty Optional) otherwise
        dialog.setResultConverter(button -> button == save
                ? new Settings(Path.of(folderField.getText().strip()).toAbsolutePath().normalize(),
                        commandsArea.getText().lines().toList())
                : null);
        return dialog.showAndWait(); // blocks until the dialog closes
    }

    /**
     * What the global settings show of the skills.
     *
     * @param agent the agent whose skills are listed and reloaded (null: no section)
     * @param busy  whether a turn is running; the agent reloads between turns only, so Reload is off then
     */
    record SkillsSection(Agent agent, boolean busy) {
    }

    /**
     * The Skills section: each loaded skill with its tool and where it comes from, the files that could not
     * be loaded with their errors, and a Reload button (which lists the result again).
     */
    /**
     * How a skill file's errors read in the list: "&lt;file&gt; is not used: ...", or, when the file replaces a
     * built-in that is in use instead, "Your coding.json can't be used: ...; the built-in coding skill is used".
     */
    static String errorText(String file, String name, List<String> problems, boolean builtInUsed) {
        String list = "\n- " + String.join("\n- ", problems);
        return builtInUsed
                ? "Your " + file + " can't be used:" + list + "\nThe built-in " + name + " skill is used."
                : file + " is not used:" + list;
    }

    private static VBox skillsBox(SkillsSection section) {
        Agent agent = section.agent();
        VBox list = new VBox(2);
        Label note = help(section.busy() ? "A turn is running; skills can be reloaded when it ends." : "");
        Button reload = new Button("Reload skills");
        reload.setDisable(section.busy());
        // Lists what is loaded now; also run after a reload
        Runnable refresh = () -> {
            list.getChildren().clear();
            SkillLoader.Loaded status = agent.skillStatus();
            for (SkillFile skill : status.skills()) {
                list.getChildren().add(help(skill.name() + " · " + agent.skillTool(skill) + " · "
                        + agent.skillOrigin(skill.name()).label()));
            }
            if (status.skills().isEmpty()) list.getChildren().add(help("No skills loaded."));
            status.errors().forEach((file, problems) -> {
                // A broken replacement of a built-in leaves the built-in in place: say so, not "not used"
                String name = file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file;
                boolean builtInUsed = agent.isBuiltInSkill(name) && status.skill(name).isPresent();
                Label error = help(errorText(file, name, problems, builtInUsed));
                error.getStyleClass().add("settings-error");
                list.getChildren().add(error);
            });
        };
        refresh.run();
        reload.setOnAction(e -> {
            try {
                agent.reloadSkills();
                note.setText("Skills reloaded.");
                note.getStyleClass().remove("settings-error");
            } catch (RuntimeException ex) {
                // The skills that were loaded before stay in use
                note.setText("Could not reload the skills: " + ex.getMessage());
                note.getStyleClass().add("settings-error");
            }
            refresh.run();
        });
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(110);
        VBox box = new VBox(6, caption("Skills"), scroll, new HBox(8, reload, note));
        box.getChildren().add(help("Skill files live in " + Settings.globalFile().resolveSibling("skills") + "."));
        return box;
    }

    /** True for an existing folder; false for anything else, including text that isn't a valid path. */
    private static boolean isDirectory(String path) {
        try {
            return !path.isEmpty() && Files.isDirectory(Path.of(path));
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-caption");
        return label;
    }

    private static Label help(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-help");
        return label;
    }
}
