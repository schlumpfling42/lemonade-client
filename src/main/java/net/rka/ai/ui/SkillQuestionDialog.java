package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the user a skill's question: the question as Markdown, one button per choice and a comment field.
 * Must be called on the FX thread.
 *
 * <p>How it works: like {@link PlanDialog} it is an {@link Alert} with its own button types and the
 * window's stylesheets, shown with {@code showAndWait}. The choices come from the skill file, so there is
 * a {@link ButtonType} per choice (kept in a map to find the pressed one) and none is the default button:
 * Enter never picks an answer by accident. A <b>Cancel</b> button, and closing the window, dismiss the
 * question, which finishes the skill as cancelled ({@link SkillAnswer#dismissed()}).
 *
 * <p>Not implemented yet: keyboard shortcuts for the choices.
 */
final class SkillQuestionDialog {

    private static final ButtonType CANCEL = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

    private SkillQuestionDialog() {
    }

    /** Shows the question and blocks until it's answered; closing the window dismisses it. */
    static SkillAnswer ask(Window owner, SkillQuestion question) {
        // The question as formatted text (it may use Markdown); read-only, links aren't expected so none are opened
        RichTextArea markdown = MarkdownView.render(question.question(), url -> {
        });
        ScrollPane scroll = new ScrollPane(markdown);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(180);
        markdown.prefWidthProperty().bind(scroll.widthProperty().subtract(30)); // room for the scroll bar

        Label caption = new Label("Comment");
        caption.getStyleClass().add("settings-caption");
        TextField comment = new TextField();
        comment.setPromptText("Optional");
        comment.setOnAction(e -> e.consume()); // Enter in the field must not press a button

        // One button per choice; YES is not a default button type, so none is pressed by Enter
        Map<ButtonType, String> byButton = new HashMap<>();
        List<ButtonType> buttons = new ArrayList<>();
        for (String choice : question.choices()) {
            ButtonType button = new ButtonType(choice, ButtonBar.ButtonData.YES);
            byButton.put(button, choice);
            buttons.add(button);
        }
        buttons.add(CANCEL);

        VBox content = new VBox(8, scroll, caption, comment);
        content.setPadding(new Insets(4, 0, 0, 0));

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.getDialogPane().getButtonTypes().setAll(buttons);
        alert.initOwner(owner);
        alert.setTitle("Question");
        alert.setHeaderText(question.skill());
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        ButtonType pressed = alert.showAndWait().orElse(CANCEL); // closing the window = Cancel
        String choice = byButton.get(pressed);
        return choice == null ? SkillAnswer.dismissed() : new SkillAnswer(choice, comment.getText().strip());
    }
}
