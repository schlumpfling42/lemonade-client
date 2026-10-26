package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import net.rka.ai.agent.ConversationSummary;
import net.rka.ai.tools.FactBook;

import java.util.List;
import java.util.Optional;

/**
 * Shows one conversation's memory: the running summary (read-only) and the long-term facts,
 * which you can add to or delete from.
 *
 * <p>How it works: the facts are copied into a {@link FactBook} (which assigns ids the same way the
 * remember tool does), edited there, and returned on Save; Cancel returns an empty Optional. The
 * caller saves them with the conversation.
 *
 * <p>Not implemented yet: editing a fact's text in place, editing or resetting the summary, and
 * showing how many messages the summary currently replaces in tokens.
 */
final class MemoryDialog {

    private MemoryDialog() {
    }

    /** Returns the edited facts on Save, or empty on Cancel. */
    static Optional<List<FactBook.Fact>> edit(Window owner, ConversationSummary summary, List<FactBook.Fact> facts) {
        FactBook book = new FactBook(facts); // a working copy; the conversation changes only on Save

        // --- Facts: list + delete + add ---
        ListView<FactBook.Fact> list = new ListView<>();
        list.getItems().setAll(book.list());
        list.setPrefHeight(180);
        list.setPlaceholder(new Label("No facts yet. The assistant adds them with its remember tool, or add one below."));
        // Each row shows "[f1] the fact"
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(FactBook.Fact fact, boolean empty) {
                super.updateItem(fact, empty);
                setText(empty || fact == null ? null : "[" + fact.id() + "] " + fact.text());
            }
        });

        Button delete = new Button("Delete");
        // Disabled until a row is selected
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> {
            book.remove(list.getSelectionModel().getSelectedItem().id());
            list.getItems().setAll(book.list());
        });

        TextField newFact = new TextField();
        newFact.setPromptText("A fact to remember in this conversation");
        HBox.setHgrow(newFact, Priority.ALWAYS);
        Button add = new Button("Add");
        add.disableProperty().bind(newFact.textProperty().isEmpty());
        add.setOnAction(e -> {
            book.add(newFact.getText());          // same ids and de-duplication as the remember tool
            list.getItems().setAll(book.list());
            newFact.clear();
        });
        newFact.setOnAction(e -> add.fire());     // Enter in the field adds too

        // --- Summary: read-only ---
        TextArea summaryText = new TextArea(summary.isEmpty()
                ? "(No summary yet. Older messages are summarized automatically once the conversation gets long.)"
                : summary.text());
        summaryText.setEditable(false);
        summaryText.setWrapText(true);
        summaryText.setPrefRowCount(8);

        VBox content = new VBox(6,
                caption("Facts"),
                help("Kept for the whole conversation and shown to the assistant on every turn."),
                list, new HBox(6, newFact, add, delete),
                caption("Summary of older messages"),
                help(summary.isEmpty() ? "" : "Replaces the first " + (summary.covered() - 1)
                        + " messages when talking to the model; the full history stays in the conversation."),
                summaryText);
        content.setPadding(new Insets(4, 0, 0, 0));

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        Dialog<List<FactBook.Fact>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Conversation memory");
        dialog.setHeaderText("Conversation memory");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(620);
        if (owner != null && owner.getScene() != null) {
            dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets()); // themed like the window
        }
        dialog.setResultConverter(button -> button == save ? book.list() : null);
        return dialog.showAndWait();
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
