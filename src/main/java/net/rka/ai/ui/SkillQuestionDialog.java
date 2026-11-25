package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;


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

        // One button per choice, one under the other across the whole width of the dialog, with the text wrapped: choices are whole
        // sentences, and in the dialog's button bar only the first few letters of each fitted. None is the default button, so
        // Enter never picks an answer by accident; the bar keeps only Cancel.
        String[] chosen = new String[1];
        VBox choices = new VBox(6);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        for (String choice : question.choices()) {
            Button button = new Button(choice);
            button.setWrapText(true);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinHeight(Region.USE_PREF_SIZE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setDefaultButton(false);
            button.setOnAction(e -> {
                chosen[0] = choice;
                alert.close();
            });
            choices.getChildren().add(button);
        }

        VBox content = new VBox(8, scroll, choices, caption, comment);
        content.setPadding(new Insets(4, 0, 0, 0));

        alert.getDialogPane().getButtonTypes().setAll(CANCEL);
        alert.initOwner(owner);
        alert.setTitle("Question");
        alert.setHeaderText(question.skill());
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(560);
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        alert.showAndWait(); // closing the window, or Cancel, leaves no choice
        String choice = chosen[0];
        return choice == null ? SkillAnswer.dismissed() : new SkillAnswer(choice, comment.getText().strip());
    }
}
