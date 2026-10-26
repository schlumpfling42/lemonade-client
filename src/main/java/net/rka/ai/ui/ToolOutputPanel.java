package net.rka.ai.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Right-hand side panel that shows tool calls and their output in full: a list of the calls of one
 * tool pill (when it holds more than one), and the selected call's arguments and output below it.
 *
 * <p>How it works: the transcript only shows a one-line chip per tool call. {@code ChatWindow} keeps a
 * {@link ToolEntry} per call (name, arguments, and the result once it arrives); clicking a chip calls
 * {@link #show}, and {@code ChatWindow} adds this panel to its {@code SplitPane} (removing it again
 * when ✕ is clicked). The text areas are read-only but selectable, so parts can be copied with
 * Ctrl+C; each section's Copy button copies all of it.
 *
 * <p>Not implemented yet: pretty-printing JSON arguments, syntax highlighting, and a
 * "re-run this command" action.
 */
class ToolOutputPanel extends VBox {

    private final Label title = new Label();
    // The calls of the opened pill, one row each; hidden when the pill holds a single call
    private final VBox callList = new VBox(2);
    private final ScrollPane callScroll = new ScrollPane(callList);
    private List<ToolEntry> calls = List.of();
    private ToolEntry selected;
    private ToolEntry inAreas; // the call whose arguments and output are in the text areas now
    private final TextArea command = new TextArea();
    private final Label outputLabel = new Label("Output");
    private final TextArea output = new TextArea();

    /** @param onClose what ✕ does; ChatWindow removes the panel from its SplitPane */
    ToolOutputPanel(Runnable onClose) {
        super(8); // 8 px between the stacked parts
        getStyleClass().add("tool-panel");

        // Header: tool name on the left, ✕ on the right (the growing spacer pushes it there)
        title.getStyleClass().add("panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("panel-close");
        close.setOnAction(e -> onClose.run());
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label commandLabel = new Label("Call");
        commandLabel.getStyleClass().add("panel-section");
        outputLabel.getStyleClass().add("panel-section");
        // Read-only but selectable (drag to select, Ctrl+C or right-click → Copy); monospace via "command"
        for (TextArea area : new TextArea[] {command, output}) {
            area.setEditable(false);
            area.setWrapText(true);
            area.getStyleClass().add("command");
        }
        command.setPrefRowCount(3);
        VBox.setVgrow(output, Priority.ALWAYS); // the output area takes all remaining height

        callList.getStyleClass().add("call-list");
        callScroll.setFitToWidth(true);
        callScroll.setMaxHeight(200); // a long pill scrolls here instead of pushing the output away
        callScroll.getStyleClass().add("call-scroll");
        callScroll.managedProperty().bind(callScroll.visibleProperty()); // takes no space when hidden

        getChildren().addAll(header, callScroll, section(commandLabel, command), command, section(outputLabel, output), output);
        setMinWidth(0); // allow the SplitPane divider to make the panel narrow
    }

    /** A section caption with a Copy button on the right that copies the whole text area. */
    private static HBox section(Label caption, TextArea area) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.getStyleClass().add("panel-copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        HBox row = new HBox(8, caption, spacer, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Shows a single call (an attached file, a note), without a list. */
    void show(ToolEntry entry) {
        show(List.of(entry), entry);
    }

    /** Shows the calls of one pill with {@code selected} opened; clicking a row in the list opens that call. */
    void show(List<ToolEntry> calls, ToolEntry selected) {
        // The pill's own list, not a copy: calls that join the pill while the panel is open show up on refresh
        this.calls = calls;
        this.selected = selected;
        refresh();
    }

    /** Whether this call is on display (so a new result or running output should redraw the panel). */
    boolean shows(ToolEntry entry) {
        return calls.contains(entry);
    }

    /** Redraws with the current state of the calls (a result arrived, a command printed something). */
    void refresh() {
        if (selected == null) return;
        callScroll.setVisible(calls.size() > 1);
        callList.getChildren().clear();
        for (int i = 0; i < calls.size(); i++) {
            ToolEntry call = calls.get(i);
            Hyperlink row = new Hyperlink((i + 1) + ". " + call.name() + "  " + oneLine(call.arguments()) + "  · " + call.state());
            row.getStyleClass().add("call-row");
            if (call == selected) row.getStyleClass().add("selected");
            if (call.failed()) row.getStyleClass().add("failed");
            row.setMaxWidth(Double.MAX_VALUE);
            row.setOnAction(e -> {
                selected = call;
                refresh();
            });
            callList.getChildren().add(row);
        }
        showSelected(selected);
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 60 ? flat.substring(0, 59) + "…" : flat;
    }

    /** Fills the call and output areas with one call. */
    private void showSelected(ToolEntry entry) {
        outputLabel.setText(entry.hasResult() ? (entry.failed() ? "Output · failed" : "Output")
                : entry.runningOutput().isEmpty() ? "Output · pending" : "Output · running");
        String text = entry.hasResult() ? entry.output() : entry.runningOutput();
        if (entry == inAreas && !entry.hasResult() && text.startsWith(output.getText())) {
            // The same running command printed more: add only the new part, so where you scrolled to and
            // what you selected stay put; if you were at the end, keep following the output
            boolean following = output.getCaretPosition() == output.getLength() && output.getSelection().getLength() == 0;
            int anchor = output.getAnchor();
            int caret = output.getCaretPosition();
            output.appendText(text.substring(output.getLength()));
            if (following) output.positionCaret(output.getLength());
            else output.selectRange(anchor, caret);
            return;
        }
        inAreas = entry;
        title.setText(entry.name());
        command.setText(entry.arguments());
        output.setText(text);
        // Toggle the "failed" class: CSS gives the output a red background then
        output.getStyleClass().remove("failed");
        if (entry.failed()) output.getStyleClass().add("failed");
        // A finished result opens at its start; running output follows its end, like a terminal
        if (entry.hasResult()) output.positionCaret(0);
        else output.positionCaret(output.getLength());
    }

    /** One tool call; its result arrives later and is filled in by {@link ChatWindow}. */
    static final class ToolEntry {
        private final String name;
        private final String arguments;
        private String output;
        private boolean failed;
        // Output of a command that is still running, shown until the result arrives (FX thread only)
        private final StringBuilder running = new StringBuilder();

        ToolEntry(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        String name() { return name; }
        String arguments() { return arguments; }
        String output() { return output; }
        boolean failed() { return failed; }
        boolean hasResult() { return output != null; }
        /** "running", "done" or "failed", as the chip badges and the call list show it. */
        String state() { return !hasResult() ? "running" : failed ? "failed" : "done"; }
        String runningOutput() { return running.toString(); }

        /** Adds output the command printed while running; kept to the last 100,000 characters. */
        void appendRunning(String chunk) {
            running.append(chunk);
            if (running.length() > 100_000) running.delete(0, running.length() - 100_000);
        }

        /** Stores the result; until then {@link #hasResult()} is false and the panel says "pending". */
        void complete(String output, boolean failed) {
            this.output = output;
            this.failed = failed;
        }
    }
}
