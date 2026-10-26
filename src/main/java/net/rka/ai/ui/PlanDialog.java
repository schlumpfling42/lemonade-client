package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;

import java.util.function.Consumer;

/**
 * Asks the user about a coding job's plan: the plan as Markdown in a scrollable area, a comment field,
 * and three buttons. Must be called on the FX thread.
 *
 * <p>How it works: like {@link ApprovalDialog} it is an {@link Alert} with its own button types and the
 * window's stylesheets, shown with {@code showAndWait} (which blocks the FX thread's current event, not
 * the UI). <b>Approve</b> (the default) runs the plan, <b>Revise</b> sends the comment back so the agent
 * writes a new plan (enabled only while the comment isn't blank), <b>Deny</b> ends the job; closing the
 * window counts as Deny.
 *
 * <p>A revised plan starts with a "Revised plan: ..." line (why it changed) and shows the steps
 * already done marked ✓, skipped ones ✗ ({@link PlanReview#toMarkdown()}).
 *
 * <p>Not implemented yet: showing which steps changed after a revision, and editing the plan by hand.
 */
final class PlanDialog {

    static final ButtonType APPROVE = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
    // YES places it right next to Approve and Deny (OTHER would push it to the far left of the button bar)
    static final ButtonType REVISE = new ButtonType("Revise", ButtonBar.ButtonData.YES);
    static final ButtonType DENY = new ButtonType("Deny", ButtonBar.ButtonData.CANCEL_CLOSE);

    private PlanDialog() {
    }

    /**
     * Shows the plan (for a revision with the reason and the done steps marked) and blocks until it's answered.
     *
     * @param openFile opens the plan's Markdown file in the system's editor ("Open plan file"); null hides the link
     */
    static PlanDecision ask(Window owner, PlanReview review, Consumer<String> openFile) {
        // The plan as formatted text; it is read-only and selectable, links aren't expected so none are opened
        RichTextArea markdown = MarkdownView.render(review.toMarkdown(), url -> {
        });
        ScrollPane scroll = new ScrollPane(markdown);
        scroll.setFitToWidth(true); // the text wraps at the dialog's width instead of scrolling sideways
        scroll.setPrefViewportHeight(320);
        markdown.prefWidthProperty().bind(scroll.widthProperty().subtract(30)); // room for the scroll bar

        Label caption = new Label("Comment");
        caption.getStyleClass().add("settings-caption");
        TextField comment = new TextField();
        comment.setPromptText("What should change?");
        // A handler that consumes the event keeps Enter from pressing the default button (Approve)
        // by accident while typing a comment
        comment.setOnAction(e -> e.consume());

        VBox content = new VBox(8, scroll, caption, comment);
        if (openFile != null && !review.planFile().isBlank()) {
            // The same plan as a Markdown file, to read in your editor, keep or share
            Hyperlink file = new Hyperlink("Open plan file  (" + java.nio.file.Path.of(review.planFile()).getFileName() + ")");
            file.setOnAction(e -> openFile.accept(review.planFile()));
            content.getChildren().add(1, file);
        }
        content.setPadding(new Insets(4, 0, 0, 0));

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.getDialogPane().getButtonTypes().setAll(APPROVE, REVISE, DENY); // replaces the Alert's own OK and Cancel
        // Revise needs something to revise with: it follows the comment field live
        alert.getDialogPane().lookupButton(REVISE).disableProperty()
                .bind(comment.textProperty().map(String::isBlank));
        alert.initOwner(owner); // modal over the chat window
        alert.setTitle("Approve plan");
        alert.setHeaderText(review.revision() ? "Revised coding plan" : "Coding plan");
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(720);
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        ButtonType pressed = alert.showAndWait().orElse(DENY); // closing the window = Deny
        if (pressed == APPROVE) return new PlanDecision(PlanDecision.Kind.APPROVE, "");
        if (pressed == REVISE) return new PlanDecision(PlanDecision.Kind.REVISE, comment.getText().strip());
        return PlanDecision.DENIED;
    }
}
