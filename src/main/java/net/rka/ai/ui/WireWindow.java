package net.rka.ai.ui;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.rka.ai.agent.WireCodec;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * The window of the manual mode: what is sent to the model and what comes back, as text that can be changed before it goes on.
 *
 * <pre>
 *   ┌ Call 7 · request: change it if you want, then send ─────────────┐
 *   │ settings of the call                       [Tools…]             │
 *   │ === system === …  (the whole request, editable)                 │
 *   │ [Send]  [Back to the original]  [Cancel the call]               │
 *   ├ Response ───────────────────────────────────────────────────────┤
 *   │ (while the model writes: its text as it arrives)                │
 *   │ === text === …  (the whole answer, editable)                    │
 *   │ [Use this answer]  [Back to the original]  [Cancel the call]    │
 *   └ ☑ hold requests   ☑ hold answers ────────────────────────────────┘
 * </pre>
 * It only shows and asks; the waiting is done by {@link WireGate}, which completes the futures the window is given. Everything here
 * runs on the FX thread. The text is read by {@link WireCodec}, which also turns the edited text back into a request or an answer; a
 * text that does not read is told back in the window and the call stays held.
 */
final class WireWindow {

    private final Stage stage = new Stage();
    private final Label status = new Label("Waiting for the first call of the model.");
    private final Label settings = new Label();
    private final TextArea requestArea = new TextArea();
    private final TextArea responseArea = new TextArea();
    private final Label requestProblem = new Label();
    private final Label responseProblem = new Label();
    private final Button send = new Button("Send");
    private final Button requestReset = new Button("Back to the original");
    private final Button requestCancel = new Button("Cancel the call");
    private final Button tools = new Button("Tools…");
    private final Button use = new Button("Use this answer");
    private final Button responseReset = new Button("Back to the original");
    private final Button responseCancel = new Button("Cancel the call");

    private ChatRequest shownRequest;
    private ChatResponse shownResponse;
    private String originalRequestText = "";
    private String originalResponseText = "";
    private CompletableFuture<ChatRequest> requestAnswer;
    private CompletableFuture<ChatResponse> responseAnswer;

    WireWindow(WireGate gate, Stage owner) {
        gate.attach(this);
        for (TextArea area : new TextArea[]{requestArea, responseArea}) {
            area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12;");
            area.setWrapText(false);
            VBox.setVgrow(area, Priority.ALWAYS);
        }
        requestProblem.setStyle("-fx-text-fill: #c62828;");
        responseProblem.setStyle("-fx-text-fill: #c62828;");
        settings.setStyle("-fx-text-fill: #555;");
        status.setStyle("-fx-font-weight: bold;");

        send.setOnAction(e -> sendRequest());
        send.setDefaultButton(false);
        requestReset.setOnAction(e -> requestArea.setText(originalRequestText));
        requestCancel.setOnAction(e -> cancel(requestAnswer));
        tools.setOnAction(e -> showTools());
        use.setOnAction(e -> useResponse());
        responseReset.setOnAction(e -> responseArea.setText(originalResponseText));
        responseCancel.setOnAction(e -> cancel(responseAnswer));

        CheckBox holdRequests = new CheckBox("hold every request");
        holdRequests.setSelected(true);
        holdRequests.selectedProperty().addListener((o, was, now) -> gate.holdRequests(now));
        CheckBox holdResponses = new CheckBox("hold every answer");
        holdResponses.setSelected(true);
        holdResponses.selectedProperty().addListener((o, was, now) -> gate.holdResponses(now));
        Label help = new Label("Unchecked: that side passes without waiting (and is only shown).");
        help.setStyle("-fx-text-fill: #777;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox request = new VBox(6, new HBox(8, settings, spacer, tools), requestArea, requestProblem,
                new HBox(8, send, requestReset, requestCancel));
        VBox response = new VBox(6, new Label("Answer"), responseArea, responseProblem, new HBox(8, use, responseReset, responseCancel));
        request.setPadding(new Insets(8));
        response.setPadding(new Insets(8));
        SplitPane split = new SplitPane(request, response);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox root = new VBox(6, status, split, new HBox(16, holdRequests, holdResponses, help));
        root.setPadding(new Insets(10));

        stage.setTitle("Model traffic · manual control");
        stage.setScene(new Scene(root, 900, 780));
        if (owner != null) {
            stage.setX(owner.getX() + owner.getWidth() + 8);
            stage.setY(owner.getY());
        }
        idle();
        stage.show();
    }

    // ---- what the gate shows ----

    /** A request that is held: the person reads it, changes it and sends it, or cancels the call. */
    void askRequest(int n, ChatRequest request, CompletableFuture<ChatRequest> answer) {
        showRequest(n, request);
        requestAnswer = answer;
        requestArea.setEditable(true);
        setRequestControls(true);
        status.setText("Call " + n + " · the request is held: change it if you want, then send it.");
        stage.toFront();
        send.requestFocus();
    }

    /** A request that goes without waiting, only shown. */
    void showRequest(int n, ChatRequest request) {
        shownRequest = request;
        originalRequestText = WireCodec.requestText(request);
        requestArea.setText(originalRequestText);
        requestArea.setEditable(false);
        settings.setText("Call " + n + " · " + WireCodec.settingsText(request));
        requestProblem.setText("");
        responseArea.clear();
        responseArea.setEditable(false);
        responseProblem.setText("");
        setRequestControls(false);
        setResponseControls(false);
    }

    /** The request has gone: the model writes, and what it writes appears as it arrives. */
    void waitingForModel(int n, ChatRequest sent) {
        setRequestControls(false);
        requestArea.setEditable(false);
        status.setText("Call " + n + " · sent, the model is writing…");
        responseArea.clear();
    }

    /** A piece of what the model writes ({@code kind}: text, thinking or a tool call). */
    void live(String kind, String delta) {
        if (responseAnswer != null) return; // the whole answer is already shown
        if (responseArea.getLength() == 0 || "thinking".equals(kind) != lastKindWasThinking) {
            responseArea.appendText((responseArea.getLength() == 0 ? "" : "\n") + "[" + kind + "] ");
        }
        lastKindWasThinking = "thinking".equals(kind);
        responseArea.appendText(delta);
    }

    private boolean lastKindWasThinking;

    /** An answer that is held: the person reads it, changes it and lets the application use it, or cancels the call. */
    void askResponse(int n, ChatResponse response, CompletableFuture<ChatResponse> answer) {
        showResponse(n, response);
        responseAnswer = answer;
        responseArea.setEditable(true);
        setResponseControls(true);
        status.setText("Call " + n + " · the answer is held: change it if you want, then let the application use it.");
        stage.toFront();
        use.requestFocus();
    }

    /** An answer that goes without waiting, only shown. */
    void showResponse(int n, ChatResponse response) {
        shownResponse = response;
        originalResponseText = WireCodec.responseText(response);
        responseArea.setText(originalResponseText);
        responseArea.setEditable(false);
        responseProblem.setText("");
        var usage = response.tokenUsage();
        status.setText("Call " + n + " · answered" + (usage == null ? "" : " (" + usage.inputTokenCount() + " tokens in, " + usage.outputTokenCount() + " out)"));
        lastKindWasThinking = false;
    }

    /** Nothing is held. */
    void idle() {
        requestAnswer = null;
        responseAnswer = null;
        setRequestControls(false);
        setResponseControls(false);
        requestArea.setEditable(false);
        responseArea.setEditable(false);
        if (shownRequest == null) status.setText("Waiting for the first call of the model.");
        else if (!status.getText().contains("failed")) status.setText(status.getText() + " · waiting for the next call");
    }

    /** The call failed (the server, a timeout, a stop). */
    void failed(String why) {
        status.setText("The call failed: " + why);
        setRequestControls(false);
        setResponseControls(false);
    }

    // ---- what the person does ----

    private void sendRequest() {
        if (requestAnswer == null) return;
        try {
            ChatRequest parsed = WireCodec.parseRequest(requestArea.getText(), shownRequest);
            requestProblem.setText("");
            CompletableFuture<ChatRequest> answer = requestAnswer;
            requestAnswer = null;
            setRequestControls(false);
            answer.complete(parsed);
        } catch (IllegalArgumentException e) {
            requestProblem.setText(e.getMessage()); // held: change the text and send again
        }
    }

    private void useResponse() {
        if (responseAnswer == null) return;
        try {
            ChatResponse parsed = WireCodec.parseResponse(responseArea.getText(), shownResponse);
            responseProblem.setText("");
            CompletableFuture<ChatResponse> answer = responseAnswer;
            responseAnswer = null;
            setResponseControls(false);
            responseArea.setEditable(false);
            answer.complete(parsed);
        } catch (IllegalArgumentException e) {
            responseProblem.setText(e.getMessage());
        }
    }

    private void cancel(CompletableFuture<?> answer) {
        if (answer == null) return;
        requestAnswer = null;
        responseAnswer = null;
        setRequestControls(false);
        setResponseControls(false);
        answer.completeExceptionally(new CancellationException("the call was cancelled by the user"));
    }

    private void showTools() {
        if (shownRequest == null) return;
        Alert alert = new Alert(Alert.AlertType.NONE, "", javafx.scene.control.ButtonType.CLOSE);
        alert.initOwner(stage);
        alert.setTitle("Tools of this request");
        TextArea text = new TextArea(WireCodec.toolsText(shownRequest));
        text.setEditable(false);
        text.setPrefSize(700, 420);
        alert.getDialogPane().setContent(text);
        alert.showAndWait();
    }

    private void setRequestControls(boolean on) {
        send.setDisable(!on);
        requestReset.setDisable(!on);
        requestCancel.setDisable(!on);
        tools.setDisable(shownRequest == null);
    }

    private void setResponseControls(boolean on) {
        use.setDisable(!on);
        responseReset.setDisable(!on);
        responseCancel.setDisable(!on);
    }
}
