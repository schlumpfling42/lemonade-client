package net.rka.ai.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.SkillDraft;

/**
 * Asks the user about a skill the skill builder drafted: the draft as Markdown (steps, prompts, tools with
 * the ones that ask for approval, and what saving replaces), a "Show JSON" toggle for the file itself, a
 * comment field and three buttons. Must be called on the FX thread.
 *
 * <p>How it works: like {@link PlanDialog} it is an {@link Alert} with its own button types and the
 * window's stylesheets, shown with {@code showAndWait}. No button is the default, so Enter does nothing:
 * <b>Save</b> has to be clicked. <b>Save</b> writes the skill,
 * <b>Revise</b> sends the comment back so the builder drafts it again (enabled only while the comment isn't
 * blank), <b>Cancel</b> ends the builder without saving; closing the window counts as Cancel.
 *
 * <p>Not implemented yet: editing the JSON by hand before saving.
 */
final class SkillReviewDialog {

    static final ButtonType SAVE = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
    // YES places it right next to Save and Cancel (OTHER would push it to the far left of the button bar)
    static final ButtonType REVISE = new ButtonType("Revise", ButtonBar.ButtonData.YES);
    static final ButtonType CANCEL = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

    private SkillReviewDialog() {
    }

    /** Shows the draft and blocks until it's answered; closing the window cancels. */
    static SkillDecision ask(Window owner, SkillDraft draft) {
        RichTextArea markdown = MarkdownView.render(draft.toMarkdown(), url -> {
        });
        ScrollPane scroll = new ScrollPane(markdown);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        markdown.prefWidthProperty().bind(scroll.widthProperty().subtract(30)); // room for the scroll bar

        Label caption = new Label("Comment");
        caption.getStyleClass().add("settings-caption");
        TextField comment = new TextField();
        comment.setPromptText("What should change?");
        comment.setOnAction(e -> e.consume()); // Enter in the field does nothing either (there is no default button)

        VBox content = new VBox(8);
        if (!draft.toolsNeedingApproval().isEmpty()) {
            // Said above the text too: these are what the skill can do to the machine, so they stand out
            Label approval = new Label("Asks before running: " + String.join(", ", draft.toolsNeedingApproval()));
            approval.setWrapText(true);
            approval.getStyleClass().addAll("settings-help", "settings-error");
            content.getChildren().add(approval);
        }
        content.getChildren().addAll(caption, comment);
        // The draft's node graph as a flowchart, in its own tab next to the Markdown
        ImageView diagram = DiagramView.render(draft.skill());
        TabPane tabs = new TabPane(new Tab("Markdown", scroll), new Tab("Diagram", diagram));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        content.getChildren().add(0, tabs);
        content.setPadding(new Insets(4, 0, 0, 0));

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.getDialogPane().getButtonTypes().setAll(SAVE, REVISE, CANCEL);
        // No default button: Enter must never save (OK_DONE and YES would both make one); Save has to be clicked
        for (ButtonType type : alert.getDialogPane().getButtonTypes()) {
            ((Button) alert.getDialogPane().lookupButton(type)).setDefaultButton(false);
        }
        // Revise needs something to revise with: it follows the comment field live
        alert.getDialogPane().lookupButton(REVISE).disableProperty()
                .bind(comment.textProperty().map(String::isBlank));
        alert.initOwner(owner);
        alert.setTitle("Review skill");
        alert.setHeaderText(draft.replaces() ? "Replace skill " + draft.skill().name() : "New skill " + draft.skill().name());
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(760);
        if (owner != null && owner.getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        ButtonType pressed = alert.showAndWait().orElse(CANCEL); // closing the window = Cancel
        if (pressed == SAVE) return new SkillDecision(SkillDecision.Kind.SAVE, "");
        if (pressed == REVISE) return new SkillDecision(SkillDecision.Kind.REVISE, comment.getText().strip());
        return SkillDecision.CANCELLED;
    }
}
