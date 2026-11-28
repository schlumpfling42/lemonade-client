package net.rka.ai.ui;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import net.rka.ai.agent.WireCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sidebar of the chat window with the traffic of the model: the calls of the open conversation, what was sent and what came back, as
 * text. Every call belongs to the conversation whose turn made it ({@link WireGate#conversation}); the list shows the calls of the
 * conversation that is open, and another conversation brings its own (also after a restart: {@link WireLog}). The newest call is shown
 * while it happens, an older one when it is picked, and a call can be held: the request waits to be changed and sent, the answer waits to
 * be changed and used.
 *
 * <pre>
 *   ┌ Conversation: add subtract ─────────────────────────────────────┐
 *   │ Call 7 · answered (2,572 tokens in, 103 out)                     │
 *   │ #7 · 3 messages · 1 tools · 2,572 in / 103 out      ← the calls  │
 *   │ #6 · 2 messages · 0 tools · 601 in / 19 out                      │
 *   ├ Call 7 · settings of the call                      [Tools…]      │
 *   │ === system === …  (the whole request, editable while held)       │
 *   │ [Send]  [Back to the original]  [Cancel the call]                │
 *   ├ Answer ──────────────────────────────────────────────────────────┤
 *   │ === text === …  (the whole answer, editable while held)          │
 *   │ [Use this answer]  [Back to the original]  [Cancel the call]     │
 *   └ ☐ hold every request   ☐ hold every answer ──────────────────────┘
 * </pre>
 * It only shows and asks; the waiting is done by {@link WireGate}, which completes the futures the view is given. Everything here runs
 * on the FX thread. The text is read by {@link WireCodec}, which also turns the edited text back into a request or an answer; a text that
 * does not read is told back here and the call stays held. Nothing is held until a box is ticked, so the view can stay open as a log.
 * A held call keeps the view on its conversation until it is dealt with, whatever conversation is opened meanwhile.
 */
final class WireView extends VBox {

    /** The calls kept in the list of a conversation. */
    private static final int MAX_CALLS = 200;

    /** One call as the view keeps it: the texts shown for it, and its line in the list. */
    private static final class Call {
        final int n;
        final String conversation;
        ChatRequest request; // only while the app runs: a call loaded from disk has the texts only
        String requestText = "";
        String settingsText = "";
        String responseText = "";
        String toolsText = "";
        String line;

        Call(int n, String conversation) {
            this.n = n;
            this.conversation = conversation;
            this.line = "#" + n;
        }

        @Override
        public String toString() {
            return line;
        }

        WireLog.Entry entry() {
            return new WireLog.Entry(n, line, settingsText, requestText, responseText, toolsText);
        }
    }

    private final WireGate gate;
    private final WireLog log;
    private final Label conversationLabel = new Label();
    private final Label status = new Label("Waiting for the first call of the model.");
    private final Label settings = new Label();
    private final Map<String, ObservableList<Call>> byConversation = new HashMap<>();
    private final ListView<Call> list = new ListView<>();
    private final TextArea requestArea = new TextArea();
    private final TextArea responseArea = new TextArea();
    private final Label requestProblem = new Label();
    private final Label responseProblem = new Label();
    private final Button send = new Button("Send");
    private final CheckBox holdRequestsBox = new CheckBox("hold every request");
    private final CheckBox holdResponsesBox = new CheckBox("hold every answer");
    private final Button requestReset = new Button("Back to the original");
    private final Button requestCancel = new Button("Cancel the call");
    private final Button tools = new Button("Tools…");
    private final Button use = new Button("Use this answer");
    private final Button responseReset = new Button("Back to the original");
    private final Button responseCancel = new Button("Cancel the call");
    private final Runnable reveal;
    /** Writes the calls to disk one after the other, off the FX thread. */
    private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "traffic-saver");
        t.setDaemon(true);
        return t;
    });

    /** The conversation that is open in the chat, and the one whose calls the list shows (a held call of another one takes the list for a while). */
    private String openId = "";
    private String openTitle = "New conversation";
    private String displayed = "";
    private Call live;
    private Call shown;
    private ChatRequest shownRequest;
    private ChatResponse shownResponse;
    private String originalRequestText = "";
    private String originalResponseText = "";
    private CompletableFuture<ChatRequest> requestAnswer;
    private CompletableFuture<ChatResponse> responseAnswer;
    private boolean lastKindWasThinking;

    /**
     * @param gate   the gate whose calls are shown; the view attaches itself
     * @param ui     where the two hold boxes are remembered
     * @param log    where the calls of each conversation are kept
     * @param reveal called when a call is held, so the sidebar can open itself: a held call waits for the person
     * @param saved  called when a box changes, to save the state
     */
    WireView(WireGate gate, UiState ui, WireLog log, Runnable reveal, Runnable saved) {
        super(6);
        this.gate = gate;
        this.log = log;
        this.reveal = reveal;
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
        status.setWrapText(true);
        conversationLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        list.setPrefHeight(130);
        list.setMinHeight(60);
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> pick(now));

        send.setOnAction(e -> sendRequest());
        send.setDefaultButton(false);
        requestReset.setOnAction(e -> requestArea.setText(originalRequestText));
        requestCancel.setOnAction(e -> cancel(requestAnswer));
        tools.setOnAction(e -> showTools());
        use.setOnAction(e -> useResponse());
        responseReset.setOnAction(e -> responseArea.setText(originalResponseText));
        responseCancel.setOnAction(e -> cancel(responseAnswer));

        CheckBox holdRequests = holdRequestsBox;
        holdRequests.setSelected(ui.holdRequests);
        gate.holdRequests(ui.holdRequests);
        holdRequests.selectedProperty().addListener((o, was, now) -> {
            gate.holdRequests(now);
            ui.holdRequests = now;
            saved.run();
        });
        CheckBox holdResponses = holdResponsesBox;
        holdResponses.setSelected(ui.holdResponses);
        gate.holdResponses(ui.holdResponses);
        holdResponses.selectedProperty().addListener((o, was, now) -> {
            gate.holdResponses(now);
            ui.holdResponses = now;
            saved.run();
        });
        Label help = new Label("Ticked: the call waits here until you send it or use the answer. Unticked: it goes on and is only shown.");
        help.setStyle("-fx-text-fill: #777;");
        help.setWrapText(true);

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
        getChildren().addAll(conversationLabel, status, list, split, new VBox(4, new HBox(16, holdRequests, holdResponses), help));
        setPadding(new Insets(10));
        showOpen(); // the list shows the calls of the open conversation, from the start
        idle();
    }

    // ---- the conversations ----

    /**
     * Shows the calls of a conversation (the open one). A held call keeps the view where it is; the switch is made when it is dealt with.
     *
     * @param title the conversation's name, or null for a new one
     */
    void showConversation(String id, String title) {
        openId = id;
        openTitle = title == null || title.isBlank() ? "New conversation" : title;
        if (requestAnswer == null && responseAnswer == null) showOpen();
    }

    private void showOpen() {
        displayed = openId;
        conversationLabel.setText("Conversation: " + openTitle);
        ObservableList<Call> items = calls(openId);
        list.setItems(items);
        shown = null;
        if (items.isEmpty()) {
            settings.setText("");
            requestArea.clear();
            responseArea.clear();
            requestArea.setEditable(false);
            responseArea.setEditable(false);
            tools.setDisable(true);
            status.setText("No call of the model in this conversation yet.");
        } else {
            list.getSelectionModel().select(items.getLast());
            list.scrollTo(items.getLast());
            status.setText("Call " + items.getLast().n + " · " + items.size() + " calls of this conversation");
        }
    }

    /** Forgets the calls of a deleted conversation. */
    void forget(String id) {
        byConversation.remove(id);
        log.delete(id);
    }

    /** The calls of a conversation: those of this run, and before them those kept on disk. */
    private ObservableList<Call> calls(String id) {
        return byConversation.computeIfAbsent(id, key -> {
            ObservableList<Call> loaded = FXCollections.observableArrayList();
            for (WireLog.Entry e : log.load(key)) {
                Call call = new Call(e.n(), key);
                call.line = e.line();
                call.settingsText = e.settings();
                call.requestText = e.request();
                call.responseText = e.response();
                call.toolsText = e.tools() == null ? "" : e.tools();
                loaded.add(call);
            }
            return loaded;
        });
    }

    private void save(String conversation) {
        ObservableList<Call> items = byConversation.get(conversation);
        if (items == null) return;
        List<WireLog.Entry> entries = new ArrayList<>(items.size());
        for (Call call : items) entries.add(call.entry());
        saver.execute(() -> log.save(conversation, entries));
    }

    /** The lines of the list of the open conversation, oldest first (for the tests). */
    List<String> callLines() {
        return list.getItems().stream().map(Call::toString).toList();
    }

    // ---- what the gate shows ----

    /** A request that is held: the person reads it, changes it and sends it, or cancels the call. */
    void askRequest(int n, ChatRequest request, CompletableFuture<ChatRequest> answer) {
        show(n, request, true);
        requestAnswer = answer;
        requestArea.setEditable(true);
        setRequestControls(true);
        status.setText("Call " + n + " · the request is held: change it if you want, then send it.");
        reveal.run();
        send.requestFocus();
    }

    /** A request that goes without waiting, only shown. */
    void showRequest(int n, ChatRequest request) {
        show(n, request, false);
    }

    private void show(int n, ChatRequest request, boolean held) {
        String conversation = gate.conversation();
        Call call = new Call(n, conversation);
        call.request = request;
        call.requestText = WireCodec.requestText(request);
        call.settingsText = "Call " + n + " · " + WireCodec.settingsText(request);
        call.toolsText = WireCodec.toolsText(request);
        call.line = "#" + n + " · " + request.messages().size() + (request.messages().size() == 1 ? " message" : " messages")
                + " · " + (request.toolSpecifications() == null ? 0 : request.toolSpecifications().size()) + " tools";
        live = call;
        ObservableList<Call> items = calls(conversation);
        items.add(call);
        if (items.size() > MAX_CALLS) items.remove(0);
        originalRequestText = call.requestText;
        shownRequest = request;
        responseProblem.setText("");
        requestProblem.setText("");
        setRequestControls(false);
        setResponseControls(false);
        lastKindWasThinking = false;
        // A call of another conversation than the open one is kept for it and not shown, unless it is held: that one waits for the person
        if (!conversation.equals(displayed) && held) {
            displayed = conversation;
            conversationLabel.setText("Conversation of the held call");
            list.setItems(items);
        }
        if (conversation.equals(displayed)) {
            display(call);
            list.getSelectionModel().select(call); // follows the newest call; pick() ignores it as it is shown already
            list.scrollTo(call);
        }
    }

    /** The request has gone: the model writes, and what it writes appears as it arrives. */
    void waitingForModel(int n, ChatRequest sent) {
        setRequestControls(false);
        requestArea.setEditable(false);
        status.setText("Call " + n + " · sent, the model is writing…");
        if (shown == live) responseArea.clear();
    }

    /** A piece of what the model writes ({@code kind}: text, thinking or a tool call). */
    void live(String kind, String delta) {
        if (responseAnswer != null || live == null) return; // the whole answer is already shown
        boolean thinking = "thinking".equals(kind);
        if (live.responseText.isEmpty() || thinking != lastKindWasThinking) live.responseText += (live.responseText.isEmpty() ? "" : "\n") + "[" + kind + "] ";
        live.responseText += delta;
        lastKindWasThinking = thinking;
        if (shown == live) responseArea.setText(live.responseText);
    }

    /** An answer that is held: the person reads it, changes it and lets the application use it, or cancels the call. */
    void askResponse(int n, ChatResponse response, CompletableFuture<ChatResponse> answer) {
        showResponse(n, response);
        responseAnswer = answer;
        responseArea.setEditable(true);
        setResponseControls(true);
        status.setText("Call " + n + " · the answer is held: change it if you want, then let the application use it.");
        reveal.run();
        use.requestFocus();
    }

    /** An answer that goes without waiting, only shown. */
    void showResponse(int n, ChatResponse response) {
        Call call = live != null && live.n == n ? live : null;
        shownResponse = response;
        originalResponseText = WireCodec.responseText(response);
        var usage = response.tokenUsage();
        String tokens = usage == null ? "" : usage.inputTokenCount() + " in / " + usage.outputTokenCount() + " out";
        if (call != null) {
            call.responseText = originalResponseText;
            if (!tokens.isEmpty()) call.line += " · " + tokens;
            list.refresh();
            save(call.conversation);
        }
        if (call == null || shown == call) {
            responseArea.setText(originalResponseText);
            responseArea.setEditable(false);
        }
        responseProblem.setText("");
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
        if (!displayed.equals(openId)) showOpen(); // a held call of another conversation had the list; the open one gets it back
    }

    /** The call failed (the server, a timeout, a stop). */
    void failed(String why) {
        status.setText("The call failed: " + why);
        if (live != null) {
            live.line += " · failed";
            list.refresh();
            save(live.conversation);
        }
        setRequestControls(false);
        setResponseControls(false);
    }

    // ---- what the person does ----

    /** An older call picked in the list is shown as it was, read only; while a call is held the newest stays shown. */
    private void pick(Call call) {
        if (call == null || call == shown) return;
        if (requestAnswer != null || responseAnswer != null) {
            list.getSelectionModel().select(shown); // a held call is dealt with first
            return;
        }
        display(call);
    }

    private void display(Call call) {
        shown = call;
        settings.setText(call.settingsText);
        requestArea.setText(call.requestText);
        requestArea.setEditable(false);
        responseArea.setText(call.responseText);
        responseArea.setEditable(false);
        tools.setDisable(call.toolsText.isEmpty());
    }

    private void sendRequest() {
        if (requestAnswer == null) return;
        try {
            ChatRequest parsed = WireCodec.parseRequest(requestArea.getText(), shownRequest);
            requestProblem.setText("");
            CompletableFuture<ChatRequest> answer = requestAnswer;
            requestAnswer = null;
            setRequestControls(false);
            if (live != null) live.requestText = requestArea.getText(); // what was sent, as it was changed
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
            if (live != null) {
                live.responseText = responseArea.getText();
                save(live.conversation);
            }
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

    // ---- for the MCP server: what the person does with the buttons, done from outside (call all of these on the FX thread) ----

    /** The call that waits for the person: {@code kind} "request" or "answer", its number and its text as it stands in the window. */
    record Held(String kind, int n, String text) {
    }

    /** The held call, or null when none is held. */
    Held held() {
        int n = live == null ? 0 : live.n;
        if (requestAnswer != null) return new Held("request", n, requestArea.getText());
        if (responseAnswer != null) return new Held("answer", n, responseArea.getText());
        return null;
    }

    /**
     * Like the Send or the Use button, with {@code text} (when given) put into the window first. A text that does not parse is refused
     * with the reason, as the window shows it, and the call stays held.
     */
    String release(String text) {
        if (requestAnswer != null) {
            if (text != null) requestArea.setText(text);
            sendRequest();
            if (requestAnswer != null) throw new IllegalArgumentException("The request was not sent, it does not parse: " + requestProblem.getText());
            return "The request was sent.";
        }
        if (responseAnswer != null) {
            if (text != null) responseArea.setText(text);
            useResponse();
            if (responseAnswer != null) throw new IllegalArgumentException("The answer was not used, it does not parse: " + responseProblem.getText());
            return "The answer is used.";
        }
        throw new IllegalArgumentException("No call is held.");
    }

    /** Like the Cancel button of the held call. */
    String cancelHeld() {
        if (requestAnswer == null && responseAnswer == null) throw new IllegalArgumentException("No call is held.");
        cancel(requestAnswer != null ? requestAnswer : responseAnswer);
        return "The call was cancelled.";
    }

    boolean holdsRequests() {
        return holdRequestsBox.isSelected();
    }

    boolean holdsAnswers() {
        return holdResponsesBox.isSelected();
    }

    /** Ticks or unticks the hold boxes (null: leave as it is); the boxes do the rest, as when clicked. */
    void setHolds(Boolean requests, Boolean answers) {
        if (requests != null) holdRequestsBox.setSelected(requests);
        if (answers != null) holdResponsesBox.setSelected(answers);
    }

    /** The calls of a conversation (the open one when {@code id} is empty), oldest first, as they are kept. */
    List<WireLog.Entry> entries(String id) {
        List<WireLog.Entry> entries = new ArrayList<>();
        for (Call call : calls(id == null || id.isBlank() ? openId : id)) entries.add(call.entry());
        return entries;
    }

    private void showTools() {
        if (shown == null || shown.toolsText.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.NONE, "", javafx.scene.control.ButtonType.CLOSE);
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.setTitle("Tools of this request");
        TextArea text = new TextArea(shown.toolsText);
        text.setEditable(false);
        text.setPrefSize(700, 420);
        alert.getDialogPane().setContent(text);
        alert.showAndWait();
    }

    private void setRequestControls(boolean on) {
        send.setDisable(!on);
        requestReset.setDisable(!on);
        requestCancel.setDisable(!on);
        tools.setDisable(shown == null || shown.toolsText.isEmpty());
    }

    private void setResponseControls(boolean on) {
        use.setDisable(!on);
        responseReset.setDisable(!on);
        responseCancel.setDisable(!on);
    }
}
