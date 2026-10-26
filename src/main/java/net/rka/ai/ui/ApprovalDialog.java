package net.rka.ai.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import net.rka.ai.tools.TrustedCommands;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Asks the user whether one tool call may run: terminal and SSH commands, and file changes
 * (including the conversation's memory) that aren't trusted. Must be called on the FX thread.
 *
 * <p>How it works: the call's JSON arguments are parsed; a {@code command} argument is shown in a
 * read-only, selectable text area (other arguments like host become labels). Calls without a
 * command (file changes) show the pretty-printed arguments instead. {@code showAndWait} blocks the
 * FX thread's current event (not the UI: JavaFX keeps processing events in a nested loop) until a
 * button is pressed; closing the window counts as Deny.
 *
 * <p><b>Always</b> runs the call now and returns an {@link Answer} carrying the entry to trust: the
 * command (editable in the "Trust as" field, e.g. {@code git status} → {@code git status*}) or, for
 * file tools, {@code tool:<name>}. Always is a split button: its main part stores the entry for this
 * conversation, its arrow offers "everywhere" too; the caller ({@code ChatWindow.approve}) adds it to
 * the conversation's or the global trusted commands. Always is disabled while the entry contains shell
 * syntax, because such an entry could never match.
 *
 * <p>Not implemented yet: editing the command itself before running it, showing a diff for file
 * overwrites, and keyboard shortcuts (R / D).
 */
final class ApprovalDialog {

    /** Where an "always allow" answer stores the trusted command. */
    enum Scope { NONE, CONVERSATION, GLOBAL }

    /**
     * The user's answer.
     *
     * @param run   whether the call may run now
     * @param scope where to remember it as a trusted command ({@code NONE} = just this once)
     * @param entry the trusted-command entry to add when {@code scope} isn't {@code NONE}
     */
    record Answer(boolean run, Scope scope, String entry) {
        static final Answer DENIED = new Answer(false, Scope.NONE, null);
    }

    static final ButtonType RUN = new ButtonType("Run", ButtonBar.ButtonData.OK_DONE);
    // YES places it right next to Run and Deny (OTHER would push it to the far left of the button bar)
    static final ButtonType ALWAYS = new ButtonType("Always", ButtonBar.ButtonData.YES);
    static final ButtonType DENY = new ButtonType("Deny", ButtonBar.ButtonData.CANCEL_CLOSE);

    // The choices in the Always button's dropdown
    private static final String IN_CONVERSATION = "Conversation";
    private static final String EVERYWHERE = "Everywhere";

    private ApprovalDialog() {
    }

    /** Shows the dialog for one call and blocks until it's answered. */
    static Answer ask(Window owner, ToolExecutionRequest call) {
        Map<String, Object> arguments = parse(call.arguments()); // the model's JSON arguments as a map
        Object command = arguments.remove("command");            // terminal/SSH calls have one; file tools don't

        VBox content = new VBox(8);
        content.setPadding(new Insets(4, 0, 0, 0));
        if (command != null) {
            // Terminal/SSH: the other arguments (host, working directory) as labels, the command below
            arguments.forEach((name, value) -> {
                if (value != null && !String.valueOf(value).isBlank()) content.getChildren().add(new Label(name + ": " + value));
            });
        } else {
            // File tools (and remember/forget): changes inside the trusted folder or the conversation's memory
            Label why = wrapping(call.name() + " changes files in the trusted folder (or this conversation's "
                    + "memory). It runs only with your approval, or if you trust the tool.");
            content.getChildren().add(why);
        }
        TextArea commandText = new TextArea(command != null ? String.valueOf(command) : prettyArguments(call.arguments()));
        commandText.setEditable(false);
        commandText.setWrapText(true);
        commandText.setPrefRowCount(command != null ? 3 : 6); // JSON arguments need more lines than a command
        commandText.getStyleClass().add("command");
        content.getChildren().add(commandText);

        // "Trust as": for commands, prefilled with the exact command and editable to add a * wildcard;
        // for other tools, the fixed entry tool:<name> that trusts the whole tool
        TextField trustAs = new TextField(command != null
                ? String.valueOf(command).strip()
                : TrustedCommands.toolEntry(call.name()));
        trustAs.setEditable(command != null);
        trustAs.getStyleClass().add("command");
        Label trustHelp = wrapping("");
        trustHelp.getStyleClass().add("settings-help");
        // A binding is recomputed whenever the field's text changes, so the buttons and the help text
        // below follow your edits live
        BooleanBinding trustable = Bindings.createBooleanBinding(
                () -> TrustedCommands.canBeTrusted(trustAs.getText()), trustAs.textProperty());
        if (command != null) {
            trustHelp.textProperty().bind(Bindings.when(trustable)
                    .then("Always: run now and don't ask again. * matches any arguments.")
                    .otherwise("Commands with ; & | < > ` $ ( ) { } can only be run once."));
        } else {
            trustHelp.setText("Always: run now and don't ask again for " + call.name() + ".");
        }
        Label caption = new Label("Trust as");
        caption.getStyleClass().add("settings-caption");

        content.getChildren().addAll(caption, trustAs, trustHelp);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        // Where "Always" stores the entry, set by the split button below (this conversation by default)
        Scope[] chosen = {Scope.CONVERSATION};
        // A DialogPane makes one Button per ButtonType; for ALWAYS it makes a split button instead:
        // the main part means "this conversation", the arrow opens both choices with their full text
        alert.setDialogPane(new DialogPane() {
            @Override
            protected Node createButton(ButtonType type) {
                if (type != ALWAYS) return super.createButton(type);
                MenuItem here = new MenuItem(IN_CONVERSATION);
                MenuItem everywhere = new MenuItem(EVERYWHERE);
                // A check marks the default (what the main part of the button does); style classes for chat.css
                here.setGraphic(new Label("✓"));
                everywhere.setGraphic(new Label(" "));
                here.getStyleClass().add("always-item");
                everywhere.getStyleClass().add("always-item");
                SplitMenuButton always = new SplitMenuButton(here, everywhere);
                always.setText("Always");
                always.setTooltip(new Tooltip("Don't ask again in this conversation; the arrow offers Everywhere (all conversations)"));
                always.getStyleClass().add("always-button");
                ButtonBar.setButtonData(always, type.getButtonData());
                // Each choice records the scope, then closes the dialog with ALWAYS as its result
                Runnable close = () -> {
                    alert.setResult(ALWAYS);
                    alert.close();
                };
                always.setOnAction(e -> {
                    chosen[0] = Scope.CONVERSATION;
                    close.run();
                });
                here.setOnAction(e -> {
                    chosen[0] = Scope.CONVERSATION;
                    close.run();
                });
                everywhere.setOnAction(e -> {
                    chosen[0] = Scope.GLOBAL;
                    close.run();
                });
                // "Always" only makes sense for an entry that could ever match
                always.disableProperty().bind(trustable.not());
                return always;
            }
        });
        alert.getDialogPane().getButtonTypes().addAll(RUN, ALWAYS, DENY);
        alert.initOwner(owner); // modal over the chat window
        alert.setTitle("Approve command");
        alert.setHeaderText("The agent wants to run " + call.name());
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(640);
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        ButtonType pressed = alert.showAndWait().orElse(DENY); // closing the window = Deny
        if (pressed == RUN) return new Answer(true, Scope.NONE, null);
        if (pressed == ALWAYS) return new Answer(true, chosen[0], trustAs.getText().strip());
        return Answer.DENIED;
    }

    /**
     * A label that wraps onto as many lines as it needs. Wrapping alone isn't enough inside a dialog:
     * without a minimum height of its preferred size the label is squeezed to one line and ends in "…".
     */
    private static Label wrapping(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** The call's JSON arguments, indented for reading; the raw text if it isn't valid JSON. */
    private static String prettyArguments(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    /** The arguments as an ordered map (keeps the model's order); empty if the JSON is broken. */
    private static Map<String, Object> parse(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
