package net.rka.ai.ui;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.scene.Node;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import java.io.IOException;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import jfx.incubator.scene.control.richtext.RichTextArea;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.Tooltip;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.Approver;
import net.rka.ai.agent.ContextReset;
import net.rka.ai.agent.ConversationHealth;
import net.rka.ai.agent.PlanReview;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.SkillDraft;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;
import net.rka.ai.agent.ConversationSummary;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.Progress;
import net.rka.ai.agent.Thought;
import net.rka.ai.conversations.ConversationStore;
import net.rka.ai.conversations.ConversationStore.Conversation;
import net.rka.ai.conversations.ConversationStore.Summary;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.FactBook;
import net.rka.ai.ui.ToolOutputPanel.ToolEntry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The JavaFX chat window around {@link Agent}.
 *
 * <h2>Layout</h2>
 * <pre>
 *   ┌ header: ☰  title / model + tracing ...................... ⚙ Settings ┐
 *   │ sidebar      │ split: transcript (messages)   │ ToolOutputPanel (opt.) │
 *   │  New chat    │   bubbles, tool chips          │   call + output        │
 *   │  conversations│                               │                        │
 *   │              ├────────────────────────────────┴────────────────────────┤
 *   │              │ composer: input / 📁 folder ⚙ Conversation       Send    │
 *   └──────────────┴──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Threads (the most important thing to understand here)</h2>
 * JavaFX has one UI thread (the "FX Application Thread"). Everything that touches the UI must run
 * on it, and nothing slow may run on it, or the window freezes. So:
 * <ul>
 *   <li>{@link #init()} builds the {@link Agent} before the window exists (off the FX thread);</li>
 *   <li>{@link #sendMessage} wraps {@code agent.run} in a JavaFX {@link Task} and submits it to the
 *       single-threaded {@code worker}; {@code setOnSucceeded}/{@code setOnFailed} run back on the FX
 *       thread to render the result;</li>
 *   <li>approvals go the other way: the worker thread calls {@link #approve}, which schedules the
 *       dialogs on the FX thread with {@code Platform.runLater} and waits on a
 *       {@link CompletableFuture} until you have answered.</li>
 * </ul>
 *
 * <h2>State</h2>
 * The open conversation is {@code history} (the messages), {@code threadId} (its id), its title and
 * {@code conversationSettings}. It's saved to the {@link ConversationStore} after every answer. The
 * sidebar is a {@link TreeView}: a row per trusted folder with its {@link Summary} rows read from the store below it.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>A Stop button that cancels the running turn.</li>
 *   <li>Streaming answers token by token, and live tool chips ("running…") while the turn is busy;
 *       today everything appears when the turn finishes.</li>
 *   <li>Editing and resending an earlier question, and regenerating an answer.</li>
 *   <li>Dark mode (a second set of color roles in chat.css), font size setting, keyboard shortcuts
 *       (Ctrl+N new chat, Ctrl+K search conversations).</li>
 *   <li>Drag-and-drop files into the input, and image input for vision models.</li>
 *   <li>Remembering the sidebar's width (the window's place and size, the open conversation and the unsent text are remembered: {@link UiState}).</li>
 * </ul>
 */
public class ChatWindow extends Application {

    // One background thread for agent runs, so turns run one at a time and never on the FX thread.
    // Daemon: it doesn't keep the JVM alive after the window closes.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-worker");
        t.setDaemon(true);
        return t;
    });

    // Every call to the model passes this gate; the traffic sidebar (WireView) shows the calls and, with its hold boxes ticked, keeps a call
    // until the person has read and changed it
    private final WireGate wireGate = new WireGate();
    /** The sidebar on the right with the traffic of the model; its width is added to the window when it opens. */
    private final VBox trafficPanel = new VBox();
    private WireView traffic;
    private static final double TRAFFIC_WIDTH = 520;
    private final javafx.scene.control.ToggleButton trafficToggle = new javafx.scene.control.ToggleButton("⇄ Model traffic");

    // Where the person was (open conversation, unsent text, window place), written as it changes so a restart does not lose it
    private final UiState ui = UiState.load(Settings.globalFile().resolveSibling("ui-state.json"));
    private final javafx.animation.PauseTransition saveUiSoon = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    // The thread that runs the turn in progress (null between turns) and whether the Stop button was pressed for it
    private volatile Thread turnThread;
    private volatile boolean stopRequested;

    private Agent agent;   // created in init(), shared by all conversations
    private Stage stage;   // the main window; owner of all dialogs
    // Reads and writes conversations/<id>.json (folder from CONVERSATIONS_DIR)
    private final ConversationStore store = ConversationStore.fromEnv();
    // The open conversation's messages; starts with the system prompt
    private List<ChatMessage> history = Agent.newConversation();
    /** The open conversation's id: its file name in the store and its LangSmith thread id. */
    private String threadId = Agent.newThreadId();
    /** The open conversation's title; null until its first question is sent. */
    private String conversationTitle;
    /** Global settings ({@code ~/chat/settings.json}) and the open conversation's own settings (folder, extra commands). */
    private Settings globalSettings = Settings.loadGlobal();
    private Settings conversationSettings = Settings.newConversation(globalSettings);
    // The open conversation's memory: running summary of older messages, and long-term facts
    private ConversationSummary conversationSummary = ConversationSummary.EMPTY;
    private List<FactBook.Fact> conversationFacts = List.of();
    private final Button memoryButton = new Button("✦ Memory");
    private final Button healthButton = new Button("● Context"); // how fit the conversation is to work from; click for the reasons and a reset
    // Files attached to the next message, shown as removable chips above the input until it's sent
    private final List<Attachments.Attachment> pendingFiles = new ArrayList<>();
    private final HBox pendingChips = new HBox(6);
    private final Button attachButton = new Button("+ Attach");
    private final Button folderButton = new Button();
    private final Button conversationSettingsButton = new Button("⚙ Conversation");

    // The sidebar: a folder row per trusted folder (a Path), its saved conversations below it (Summary)
    private final TreeView<Object> conversations = new TreeView<>();
    private List<Summary> saved = List.of();                      // what the sidebar was built from, newest first
    private final Set<String> collapsedFolders = new HashSet<>(); // folder rows the user closed: they stay closed on refresh
    private final VBox sidebar = new VBox(4);
    // True while the code itself changes the list selection, so the listener doesn't treat it as a click
    private boolean selectingProgrammatically;

    private final VBox messages = new VBox(14);                  // the transcript: one row per bubble or tool chip
    private final ScrollPane scroll = new ScrollPane(messages);  // makes the transcript scrollable
    private final TextArea input = new TextArea();               // where you type
    private final Button send = new Button("Send");
    private final Button newChat = new Button("✎   New chat");
    private final ProgressIndicator busy = new ProgressIndicator(); // spinner while a turn runs
    private final Label status = new Label();                    // model, server and tracing info in the header
    // Transcript on the left, tool output panel on the right (only added while it's open)
    private final SplitPane split = new SplitPane(scroll);
    private final ToolOutputPanel toolPanel = new ToolOutputPanel(this::closeToolPanel);
    // Tool chips by call id, so a result can update the chip of the call it answers
    private final Map<String, ToolLink> toolLinks = new HashMap<>();
    // Key prefixes of the chips of a coding job's and of a skill's own transcript in toolLinks
    private static final String CODING_IDS = "coding:";
    private static final String SKILL_IDS = "skill:";
    // The live "Coding · Step 3/7 · ..." chip of a running coding job; null when no job reports progress
    private Label progressCard;
    // The model reply that is being generated right now, drawn as it streams in
    private final LiveReply streaming = new LiveReply();
    // How many of the running turn's new messages are already on screen (shown live as nodes add them)
    private int liveShown;
    private double dividerPosition = 0.6;  // remembered panel width (as a fraction of the window)

    /** A tool call's row in the transcript, updated when its result arrives. */
    /** A tool call on screen: its entry (side panel data) and the pill it is counted in. */
    private record ToolLink(ToolEntry entry, ToolPill pill) {}
    // The newest pill; the next call joins it while it is still the last thing in the transcript
    private ToolPill lastPill;
    // Plan files already linked in this transcript
    private final java.util.Set<String> planFiles = new java.util.HashSet<>();

    /**
     * Called by JavaFX before {@link #start}, on a launcher thread (not the FX thread), so slow
     * setup can happen here without a frozen window.
     */
    @Override
    public void init() throws Exception {
        // Builds the model client, tools and graph; this::approve is how the agent will ask us for approvals
        // An anonymous Approver instead of a method reference: it has to answer plans as well as tool calls
        agent = Agent.fromEnv(new Approver() { // runs off the FX thread, before the window opens
            @Override
            public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
                return approve(calls);
            }

            @Override
            public PlanDecision reviewPlan(PlanReview review) {
                return approvePlan(review);
            }

            @Override
            public SkillAnswer askUser(SkillQuestion question) {
                return askSkillQuestion(question);
            }

            @Override
            public SkillDecision reviewSkill(SkillDraft draft) {
                return reviewSkillDraft(draft);
            }
        }, wireGate);
    }

    /** Called by JavaFX on the FX thread to build and show the window. */
    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // --- Header: menu button, title and status line, settings button ---
        Label title = new Label("Lemonade Agent");
        title.getStyleClass().add("title"); // style classes connect nodes to rules in chat.css
        status.setText(agent.modelName() + " @ " + agent.baseUrl()
                + "   ·   Tracing: " + agent.tracingDescription());
        status.getStyleClass().add("status");
        Button toggleSidebar = new Button("☰");
        toggleSidebar.getStyleClass().add("sidebar-toggle");
        toggleSidebar.setOnAction(e -> {
            // visible=false hides it; managed=false also frees its space in the layout
            sidebar.setVisible(!sidebar.isVisible());
            sidebar.setManaged(sidebar.isVisible());
        });
        Button globalSettingsButton = new Button("⚙ Settings");
        globalSettingsButton.getStyleClass().add("header-button");
        globalSettingsButton.setTooltip(new Tooltip("Global settings: default trusted folder and trusted commands"));
        globalSettingsButton.setOnAction(e -> editGlobalSettings());
        // An empty Region that grows pushes the settings button to the right edge
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        trafficToggle.getStyleClass().add("header-button");
        trafficToggle.setTooltip(new Tooltip("Open the sidebar with every message to and from the model: read it, and hold a call to change it"));
        trafficToggle.setOnAction(e -> setTrafficVisible(trafficToggle.isSelected(), true));
        HBox header = new HBox(12, toggleSidebar, new VBox(2, title, status), headerSpacer, trafficToggle, globalSettingsButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");
        buildSidebar(); // fills the sidebar VBox and loads the saved conversations
        traffic = new WireView(wireGate, ui, WireLog.fromEnv(), this::revealTraffic, ui::save);
        traffic.showConversation(threadId, conversationTitle);
        VBox.setVgrow(traffic, Priority.ALWAYS);
        trafficPanel.getChildren().add(traffic);
        trafficPanel.setPrefWidth(TRAFFIC_WIDTH);
        trafficPanel.setMinWidth(360);
        trafficPanel.setStyle("-fx-border-color: #d0d0d0; -fx-border-width: 0 0 0 1;");

        // --- Transcript ---
        messages.setPadding(new Insets(20, 24, 20, 24));
        // Auto-scroll: whenever the transcript grows, jump to the bottom (vvalue 1.0 = end)
        messages.heightProperty().addListener((obs, old, h) -> scroll.setVvalue(1.0));
        scroll.setFitToWidth(true); // content is as wide as the viewport, so bubbles can wrap text
        scroll.getStyleClass().add("transcript");
        SplitPane.setResizableWithParent(toolPanel, false); // window resizes go to the transcript

        // --- Composer: input, folder/settings chips, Send ---
        input.setPromptText("Ask something…  (Enter to send, Shift+Enter for a new line)");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();   // stop the TextArea from also inserting a newline
                sendMessage();
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS); // the input takes all the width the button column leaves

        send.setDefaultButton(false); // Enter is handled above; a default button would also fire in dialogs
        send.setOnAction(e -> { // while a turn runs the button is the Stop button
            if (busy.isVisible()) stopTurn();
            else sendMessage();
        });
        send.getStyleClass().add("primary");
        busy.setPrefSize(24, 24);
        busy.setVisible(false); // shown by setBusy(true) while a turn runs

        VBox buttons = new VBox(8, send, busy);
        buttons.setAlignment(Pos.CENTER_LEFT);
        send.setMaxWidth(Double.MAX_VALUE);

        // Small trusted-folder button under the input line; click to pick another folder
        folderButton.getStyleClass().add("folder-button");
        folderButton.setOnAction(e -> chooseFolder());
        folderButton.setMnemonicParsing(false); // folder names may contain '_'
        conversationSettingsButton.getStyleClass().add("folder-button");
        conversationSettingsButton.setTooltip(new Tooltip("This conversation's trusted folder and trusted commands"));
        conversationSettingsButton.setOnAction(e -> editConversationSettings());
        showFolder(); // sets the chip texts from the current conversation settings
        memoryButton.getStyleClass().add("folder-button");
        memoryButton.setTooltip(new Tooltip("This conversation's memory: summary of older messages and remembered facts"));
        memoryButton.setOnAction(e -> editMemory());
        healthButton.getStyleClass().add("folder-button");
        healthButton.setOnAction(e -> showHealthDialog());
        // Attaching: the chip opens a file picker; files can also be dropped onto the input
        attachButton.getStyleClass().add("folder-button");
        attachButton.setTooltip(new Tooltip("Attach text files to your next message (or drop them on the input)"));
        attachButton.setOnAction(e -> chooseAttachments());
        input.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !busy.isVisible()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        input.setOnDragDropped(e -> {
            boolean ok = e.getDragboard().hasFiles();
            if (ok) attach(e.getDragboard().getFiles());
            e.setDropCompleted(ok);
            e.consume();
        });
        pendingChips.setAlignment(Pos.CENTER_LEFT);
        pendingChips.managedProperty().bind(pendingChips.visibleProperty()); // takes no space when empty
        pendingChips.setVisible(false);
        VBox inputColumn = new VBox(4, pendingChips, input,
                new HBox(6, attachButton, folderButton, conversationSettingsButton, memoryButton, healthButton));
        HBox.setHgrow(inputColumn, Priority.ALWAYS);

        HBox composer = new HBox(10, inputColumn, buttons);
        composer.setPadding(new Insets(12));
        composer.getStyleClass().add("composer");

        // BorderPane(center, top, right, bottom, left): two nested ones give the layout drawn in the class doc
        BorderPane chat = new BorderPane(split, null, null, composer, null);
        BorderPane root = new BorderPane(chat, header, trafficPanel, null, sidebar);
        Scene scene = new Scene(root, ui.hasBounds() ? ui.width : 1040, ui.hasBounds() ? ui.height : 680);
        // The Material theme; loaded from the classpath (src/main/resources/chat.css)
        scene.getStylesheets().add(getClass().getResource("/chat.css").toExternalForm());

        stage.setTitle("Lemonade Agent");
        stage.setScene(scene);
        restorePlace();
        stage.show();
        restoreSession();
        watchPlace();
        input.requestFocus(); // ready to type

    }

    

    /**
     * Sends the input as a new user message and runs one agent turn in the background.
     * Runs on the FX thread; the slow part ({@code agent.run}) runs on {@code worker}.
     */
    private void sendMessage() {
        String typed = input.getText().strip();
        if ((typed.isEmpty() && pendingFiles.isEmpty()) || busy.isVisible()) return; // one turn at a time
        input.clear();
        stopRequested = false;
        if (typed.isEmpty()) typed = "Please look at the attached file" + (pendingFiles.size() > 1 ? "s." : ".");
        // The model receives what you typed plus the attached files' text, all in one message
        String text = Attachments.compose(typed, pendingFiles);
        pendingFiles.clear();
        showPendingFiles();

        history.add(UserMessage.from(text)); // the question becomes part of the conversation...
        addBubble("You", text, "user");      // ...and appears right away, before the answer
        // The first question names the conversation in the sidebar
        if (conversationTitle == null) {
            conversationTitle = ConversationStore.titleFrom(typed);
            traffic.showConversation(threadId, conversationTitle);
        }

        // Capture everything the background task needs NOW, as immutable values. If the user switches
        // conversation or changes settings while it runs, the task still works on the original ones.
        List<ChatMessage> snapshot = List.copyOf(history);
        String thread = threadId;
        wireGate.conversation(thread); // the calls of this turn belong to this conversation in the traffic sidebar
        String title = conversationTitle;
        Settings own = conversationSettings;
        // What this run uses: the conversation's folder, global + conversation trusted commands
        Settings effective = Settings.effective(globalSettings, own);
        // The conversation's memory goes in with the turn and comes back updated
        ConversationSummary summary = conversationSummary;
        List<FactBook.Fact> facts = conversationFacts;
        // The conversation is saved now, with the question: a restart during the turn must not lose it (it was saved only after the answer)
        saveConversation(new Conversation(thread, title, Instant.now(), own, summary, facts, snapshot));
        ui.lastConversation = thread;
        ui.save();
        // ... and again with every message the turn adds, so a closed app leaves the conversation as far as it got
        List<ChatMessage> turnSoFar = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Live output: the listener runs on the worker thread after each graph node; everything it
        // shows is queued onto the FX thread, in order, before the turn's onSucceeded/onFailed
        liveShown = 0;
        Progress.ProgressListener live = new Progress.ProgressListener() {
            @Override
            public void onProgress(Progress progress) {
                showProgress(progress);
            }

            @Override
            public void onMessage(ChatMessage message, boolean codingJob) {
                // A finished reply replaces what was streamed of it
                if (message instanceof AiMessage) streaming.clear();
                turnSoFar.add(message);
                try {
                    List<ChatMessage> sofar = new ArrayList<>(snapshot);
                    sofar.addAll(turnSoFar);
                    store.save(new Conversation(thread, title, Instant.now(), own, summary, facts, sofar));
                } catch (RuntimeException e) {
                    // the save at the end of the turn reports a failing disk
                }
                Platform.runLater(() -> {
                    if (!thread.equals(threadId)) return; // another conversation is open now
                    render(message);        // part of the conversation: counted, not rendered again later
                    liveShown++;
                });
            }

            @Override
            public void onJobMessage(ChatMessage message, boolean codingTranscript, String source) {
                if (message instanceof AiMessage) streaming.clear();
                // A coding job's notes say "Coding job" (whatever skill runs the coding kinds), a skill's its name
                String label = codingTranscript ? "Coding job" : source == null || source.isBlank() ? "Skill" : source;
                String ids = codingTranscript ? CODING_IDS : SKILL_IDS;
                Platform.runLater(() -> {
                    if (thread.equals(threadId)) render(message, ids, label); // the job's own steps: not saved, shown muted
                });
            }

            // Real-time pieces: gathered on this worker thread, drawn on the FX thread (see LiveReply)
            // What the model writes is shown in the traffic sidebar as it comes; with the answers held it reaches the chat only when the person lets it through
            @Override public void onPartialThinking(String delta) { wireGate.live("thinking", delta); if (!wireGate.holdsResponses()) streaming.thinking(delta); }
            @Override public void onPartialText(String delta) { wireGate.live("text", delta); if (!wireGate.holdsResponses()) streaming.text(delta); }
            @Override public void onPartialToolCall(int index, String name, String delta) {
                wireGate.live("tool call " + name, delta);
                if (!wireGate.holdsResponses()) streaming.toolCall(index, name, delta);
            }
            @Override public void onPartialWork(String label, int characters) { if (!wireGate.holdsResponses()) streaming.work(label, characters); }
            @Override public void onStepDone() { streaming.clear(); }

            @Override
            public void onPlanFile(String path) {
                Platform.runLater(() -> {
                    if (thread.equals(threadId)) showPlanFile(path);
                });
            }

            @Override
            public void onToolOutput(String callId, String chunk) {
                Platform.runLater(() -> showRunningOutput(callId, chunk));
            }
        };
        // A JavaFX Task: call() runs on the worker thread; the onSucceeded/onFailed handlers run on the FX thread
        Task<Agent.Turn> task = new Task<>() {
            @Override
            protected Agent.Turn call() {
                turnThread = Thread.currentThread();
                try {
                    return agent.run(snapshot, thread, effective, summary, facts, live);
                } finally {
                    turnThread = null;
                    Thread.interrupted(); // a stop must not leave the worker thread interrupted for the next turn
                }
            }
        };
        task.setOnSucceeded(e -> {
            removeProgressCard(); // the job's tool chip with its report (rendered below) replaces it
            streaming.clear();  // anything still streaming is replaced by the saved messages below
            // Save first (always, even if another conversation is open now), then show it if it's still open
            Agent.Turn turn = task.getValue();
            List<ChatMessage> produced = turn.messages();
            List<ChatMessage> all = new ArrayList<>(snapshot);
            all.addAll(produced);
            // The settings may have changed during the turn ("Always" for this conversation in an approval),
            // so save the current ones; switching conversations is locked while busy, so they belong to this one
            Settings latest = thread.equals(threadId) ? conversationSettings : own;
            saveConversation(new Conversation(thread, title, Instant.now(), latest, turn.summary(), turn.facts(), all));
            if (thread.equals(threadId)) { // still the open conversation
                conversationSummary = turn.summary(); // the memory as the agent left it
                conversationFacts = turn.facts();
                showFolder();                         // the memory chip shows the fact count
                history.addAll(produced);        // keep our copy of the conversation in sync
                // Most messages were shown live already; render only what wasn't (normally nothing)
                produced.subList(Math.min(liveShown, produced.size()), produced.size()).forEach(this::render);
                if (stopRequested) addBubble("Stopped", "You stopped it. What it did until then is kept above.", "assistant");
            }
            setBusy(false);
        });
        task.setOnFailed(e -> {
            removeProgressCard();
            streaming.clear();
            // agent.run threw (Lemonade unreachable, timeout, ...): show the error instead of an answer
            Throwable error = task.getException();
            if (error instanceof Agent.TurnFailedException failed) {
                // The turn already ran tools (maybe changed files): keep the question and what it did, so the
                // next turn knows; open calls come back answered with an error, which marks their pills failed
                Agent.Turn turn = failed.partial();
                List<ChatMessage> all = new ArrayList<>(snapshot);
                all.addAll(turn.messages());
                Settings latest = thread.equals(threadId) ? conversationSettings : own;
                saveConversation(new Conversation(thread, title, Instant.now(), latest, turn.summary(), turn.facts(), all));
                if (thread.equals(threadId)) {
                    conversationSummary = turn.summary();
                    conversationFacts = turn.facts();
                    history.addAll(turn.messages());
                    List<ChatMessage> produced = turn.messages();
                    produced.subList(Math.min(liveShown, produced.size()), produced.size()).forEach(this::render);
                    showFailure(error);
                }
            } else if (thread.equals(threadId)) {
                history.remove(history.size() - 1); // nothing happened yet: drop the unanswered question
                showFailure(error);
            }
            setBusy(false);
        });
        setBusy(true);       // spinner on, conversation switching locked
        worker.submit(task); // start the turn in the background; this method returns immediately
    }

    /** The Stop button: interrupts the turn's thread; the model's stream is cancelled, a running command is stopped. */
    private void stopTurn() {
        stopRequested = true;
        if (agent != null) agent.stop(); // the graph's own threads: a job must not go on in the background
        Thread running = turnThread;
        if (running != null) running.interrupt();
    }

    /** What a failed turn shows: a note when you stopped it, else the error. */
    private void showFailure(Throwable error) {
        if (stopRequested) addBubble("Stopped", "You stopped it. What it did until then is kept above.", "assistant");
        else addBubble("Error", Agent.TurnFailedException.describe(error), "error");
    }



    /**
     * Shows one message in the transcript. Used for new answers and when reopening a saved
     * conversation. Tool calls become chips; their results update the matching chip (by call id)
     * instead of adding a bubble. System messages are not shown.
     */
    private void render(ChatMessage message) {
        render(message, "", null);
    }

    /**
     * @param ids      the prefix of the message's tool chip keys: "" for the conversation, {@link #CODING_IDS} /
     *                 {@link #SKILL_IDS} for a coding job's / a skill's own transcript (shown live while it runs,
     *                 never saved). The three transcripts number their calls independently, so each keeps
     *                 its chips apart
     * @param jobLabel null for the conversation; else what the muted notes of the transcript's replies say
     *                 ("Coding job", the skill's name)
     */
    private void render(ChatMessage message, String ids, String jobLabel) {
        boolean codingJob = jobLabel != null;
        if (message instanceof UserMessage user) {
            addBubble("You", user.hasSingleText() ? user.singleText() : String.valueOf(user.contents()), "user"); // splits off attachments
        } else if (message instanceof AiMessage ai) {
            Thought thought = Thought.of(ai);
            // A job's steps are shown as an overview only: the progress card says the phase, one pill the tool calls. Its thinking and
            // the notes it writes for itself are not shown (they are in the traces)
            if (!codingJob && !thought.thinking().isBlank()) addNote("Thinking", thought.thinking());
            if (!codingJob && !thought.text().isBlank()) addBubble("Assistant", thought.text(), "assistant");
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    addToolLink(ids + call.id(), new ToolEntry(call.name(), call.arguments()));
                }
            }
        } else if (message instanceof ToolExecutionResultMessage result) {
            // isError is lost for results saved by older versions, so also look at the text prefix
            boolean failed = Boolean.TRUE.equals(result.isError())
                    || result.text().startsWith("Error:") || result.text().startsWith("Denied:");
            ToolLink row = toolLinks.get(ids + result.id()); // the chip of the call this result answers
            if (row == null) { // a result without a rendered call: show it on its own
                row = addToolLink(ids + result.id(), new ToolEntry(result.toolName(), ""));
            }
            row.entry().complete(result.text(), failed);  // the side panel reads the output from here
            row.pill().refresh();                         // the pill's badge: running / done / n failed
            if (toolPanel.shows(row.entry())) toolPanel.refresh(); // redraw if it's open right now
        }
    }

    /**
     * Adds a tool call to the transcript. Calls that follow each other (nothing else shown in between)
     * share one pill, which counts them and names the latest; clicking it opens all of them in the side panel.
     */
    private ToolLink addToolLink(String id, ToolEntry entry) {
        List<Node> shown = messages.getChildren();
        ToolPill pill = lastPill != null && !shown.isEmpty() && shown.getLast() == lastPill.row
                ? lastPill        // still the last thing in the transcript: the call joins it
                : new ToolPill(); // something came in between (or nothing yet): a new pill
        lastPill = pill;
        pill.add(entry);
        ToolLink toolLink = new ToolLink(entry, pill);
        if (id != null) toolLinks.put(id, toolLink); // so render() can find it by call id
        return toolLink;
    }

    /**
     * One pill for consecutive tool calls: "⚙ 7 tool calls · listFiles {…}" (a single call reads
     * "⚙ listFiles {…}") with one badge for all of them: "running" (with the newest output line of a
     * running command), "2 failed", or "done". Clicking it opens the side panel with every call listed
     * and the latest one selected.
     */
    private final class ToolPill {
        private final List<ToolEntry> calls = new ArrayList<>();
        private final Label line = new Label();                  // the call that runs now (or ran last), on one line
        private final Hyperlink count = new Hyperlink();         // "7 calls": opens all of them in the side panel
        private final Label state = new Label("running");
        private final HBox row = new HBox(6, line, count, state);

        ToolPill() {
            line.getStyleClass().add("tool-line");
            line.setMinWidth(0);
            line.setOnMouseClicked(e -> openToolPanel(calls, calls.getLast()));
            count.getStyleClass().add("tool-count");
            count.setOnAction(e -> openToolPanel(calls, calls.getLast()));
            // Bound, not set: the line keeps following the window width (60% of the transcript)
            line.maxWidthProperty().bind(scroll.widthProperty().multiply(0.6));
            state.getStyleClass().add("tool-state");
            row.setAlignment(Pos.CENTER_LEFT);
            messages.getChildren().add(row); // appended at the bottom of the transcript
        }

        void add(ToolEntry entry) {
            calls.add(entry);
            refresh();
            // The side panel shows this pill's list itself: redraw so the new call appears there too
            if (toolPanel.shows(calls.getFirst())) toolPanel.refresh();
        }

        /** Redraws the text and badge from the calls' current state. */
        void refresh() {
            ToolEntry last = calls.getLast();
            line.setText("⚙ " + describe(last));
            count.setText(calls.size() == 1 ? "1 call" : calls.size() + " calls");
            long failed = calls.stream().filter(ToolEntry::failed).count();
            ToolEntry running = calls.stream().filter(c -> !c.hasResult()).reduce((a, b) -> b).orElse(null);
            String text;
            String kind;
            if (running != null) {
                String out = running.runningOutput().strip();
                out = out.substring(out.lastIndexOf('\n') + 1).strip();
                text = out.isEmpty() ? "running" : "running · " + (out.length() > 60 ? out.substring(0, 59) + "…" : out);
                kind = "running";
            } else if (failed > 0) {
                text = failed == calls.size() && failed == 1 ? "failed" : failed + " failed";
                kind = "failed";
            } else {
                text = "done";
                kind = "done";
            }
            state.setText(text);
            // setAll replaces the classes, so the badge switches its color rule (done = green, failed = red)
            state.getStyleClass().setAll("label", "tool-state", kind);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * A call on one line: its name and what it works on (the file and section, the pattern, the command), not its raw arguments.
     * The side panel has the full call and its output.
     */
    private static String describe(ToolEntry call) {
        StringBuilder on = new StringBuilder();
        try {
            com.fasterxml.jackson.databind.JsonNode args = JSON.readTree(call.arguments());
            String path = args.path("path").asText("");
            if (!path.isEmpty()) on.append(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1));
            for (String key : List.of("section", "pattern", "name", "command", "glob", "query", "url")) {
                String value = args.path(key).asText("");
                if (!value.isEmpty()) on.append(on.isEmpty() ? "" : " › ").append(value);
            }
        } catch (Exception e) {
            // not JSON: the arguments as they are
        }
        String shown = on.isEmpty() ? summarize(call.arguments()) : on.toString();
        shown = shown.replaceAll("\\s+", " ").strip();
        return call.name() + (shown.isEmpty() ? "" : "  " + (shown.length() > 80 ? shown.substring(0, 79) + "…" : shown));
    }

    /** The arguments on one line, cut to 70 characters for the chip. */
    private static String summarize(String arguments) {
        String flat = arguments.replaceAll("\\s+", " ").strip();
        return flat.length() > 70 ? flat.substring(0, 69) + "…" : flat;
    }

    /** Shows one entry (an attached file, a note) in the right-hand panel. */
    private void openToolPanel(ToolEntry entry) {
        openToolPanel(List.of(entry), entry);
    }

    /** Shows a pill's calls in the right-hand panel, adding the panel to the split pane if it's closed. */
    private void openToolPanel(List<ToolEntry> calls, ToolEntry selected) {
        toolPanel.show(calls, selected);
        if (!split.getItems().contains(toolPanel)) {
            split.getItems().add(toolPanel);
            split.setDividerPositions(dividerPosition); // restore the width it had last time
        }
    }

    /** Removes the panel (✕ button, New chat, opening another conversation). */
    private void closeToolPanel() {
        if (split.getItems().contains(toolPanel)) {
            dividerPosition = split.getDividerPositions()[0]; // reopen at the width the user left it
            split.getItems().remove(toolPanel);
        }
    }

    /**
     * The agent's {@link net.rka.ai.agent.Approver}: called on the worker thread while the graph
     * is paused; shows one dialog per call on the FX thread and waits for the answers.
     */
    private Map<String, Boolean> approve(List<ToolExecutionRequest> calls) {
        // A future is a one-time "mailbox": the FX thread puts the answers in, the worker thread waits for them
        CompletableFuture<Map<String, Boolean>> answers = new CompletableFuture<>();
        Platform.runLater(() -> { // queue this on the FX thread: dialogs may only be shown there
            Map<String, Boolean> decisions = new HashMap<>();
            try {
                for (ToolExecutionRequest call : calls) {
                    ApprovalDialog.Answer answer = ApprovalDialog.ask(stage, call);
                    decisions.put(call.id(), answer.run());
                    trust(answer); // "Always ..." also remembers the command for next time
                }
                answers.complete(decisions);         // wakes up the waiting worker thread
            } catch (RuntimeException e) {
                answers.completeExceptionally(e);    // the agent treats a failing approver as "deny all"
            }
        });
        return answers.join(); // blocks the worker (not the UI) until every dialog is answered
    }

    /**
     * Asks about a coding job's plan: called on the worker thread while the graph is paused; shows the
     * {@link PlanDialog} on the FX thread and waits for the answer, like {@link #approve}.
     */
    private PlanDecision approvePlan(PlanReview review) {
        CompletableFuture<PlanDecision> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(PlanDialog.ask(stage, review, this::openFile));
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);     // the agent treats a failing approver as "deny"
            }
        });
        return answer.join(); // blocks the worker (not the UI) until the dialog is answered
    }

    /**
     * Asks a skill's question: called on the worker thread while the graph is paused; shows the
     * {@link SkillQuestionDialog} on the FX thread and waits for the answer, like {@link #approvePlan}.
     * A failing dialog dismisses the question (the skill ends as cancelled).
     */
    private SkillAnswer askSkillQuestion(SkillQuestion question) {
        CompletableFuture<SkillAnswer> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(SkillQuestionDialog.ask(stage, question));
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);     // the agent treats a failing approver as "dismissed"
            }
        });
        return answer.join(); // blocks the worker (not the UI) until the dialog is answered
    }

    /**
     * Asks about a drafted skill: called on the worker thread while the graph is paused; shows the
     * {@link SkillReviewDialog} on the FX thread and waits for the answer. A failing dialog cancels
     * (nothing is saved).
     */
    private SkillDecision reviewSkillDraft(SkillDraft draft) {
        CompletableFuture<SkillDecision> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(SkillReviewDialog.ask(stage, draft));
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);     // the agent treats a failing approver as "cancel"
            }
        });
        return answer.join();
    }

    /**
     * The progress listener for {@code agent.run}: called on the worker thread after each coding node;
     * the card is created on the first event and updated afterwards, both on the FX thread.
     */
    private void showProgress(Progress progress) {
        String text = progress.label()
                + (progress.detail().isBlank() ? "" : " · `" + summarize(progress.detail()) + "`");
        Platform.runLater(() -> {
            if (progressCard == null) {
                progressCard = new Label();
                progressCard.getStyleClass().add("progress-card");
                messages.getChildren().add(progressCard); // appended at the bottom of the transcript
            }
            progressCard.setText(text);
        });
    }

    /**
     * Output a running command printed: added to its call's entry (the side panel shows it live if
     * that call is open) and its last line shown next to the chip. The output only names the call id, so
     * a coding job's and a skill's calls are looked up first under their prefixed keys (every transcript's
     * chips live in one map); the running one, not one that already has its result, gets the output.
     */
    private void showRunningOutput(String callId, String chunk) {
        ToolLink row = null;
        for (String ids : List.of(CODING_IDS, SKILL_IDS, "")) {
            ToolLink candidate = toolLinks.get(ids + callId);
            if (candidate != null && !candidate.entry().hasResult()) {
                row = candidate;
                break;
            }
        }
        if (row == null) return; // finished already, or not on screen
        row.entry().appendRunning(chunk);
        row.pill().refresh(); // the badge shows "running · <newest output line>"
        if (toolPanel.shows(row.entry())) toolPanel.refresh();
    }

    /**
     * The reply the model is generating right now: its thinking, its text and the tool calls it is
     * writing, shown in a box at the bottom of the transcript that grows as tokens arrive. The finished
     * reply (from {@code onMessage}) replaces the box with the normal bubbles and chips.
     * <p>
     * Tokens arrive on the worker thread, often hundreds per second. They are collected here and
     * drawn at most once per FX pulse: the first token after a draw schedules one {@code runLater},
     * later ones only add to the buffers. Plain labels are used while streaming (re-rendering Markdown
     * per token would be slow); the finished answer is rendered as Markdown.
     */
    private final class LiveReply {
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, String> callNames = new java.util.TreeMap<>();
        private final Map<Integer, Integer> callChars = new HashMap<>();
        private String work = "";         // progress of hidden output, e.g. "Writing the plan… 812 characters"
        private boolean scheduled;        // a draw is queued on the FX thread
        private VBox box;                 // FX thread only
        private Label thinkingLabel, textLabel, callsLabel;

        synchronized void thinking(String delta) { thinking.append(delta); schedule(); }
        synchronized void text(String delta) { text.append(delta); schedule(); }

        /** A step writing something that isn't shown (the plan JSON): "Writing the plan… 1,240 characters". */
        synchronized void work(String label, int characters) {
            work = label + "… " + String.format("%,d", characters) + " characters";
            schedule();
        }

        synchronized void toolCall(int index, String name, String delta) {
            if (name != null && !name.isBlank()) callNames.put(index, name);
            else callNames.putIfAbsent(index, "tool");
            callChars.merge(index, delta == null ? 0 : delta.length(), Integer::sum);
            schedule();
        }

        /** Drops what was streamed (its reply is finished, or it wasn't a reply worth keeping). */
        synchronized void clear() {
            thinking.setLength(0);
            text.setLength(0);
            work = "";
            callNames.clear();
            callChars.clear();
            schedule();
        }

        private void schedule() {
            if (scheduled) return;
            scheduled = true;
            Platform.runLater(this::draw);
        }

        /** FX thread: shows the buffers as they are now. */
        private void draw() {
            String thought, said, calls;
            synchronized (this) {
                scheduled = false;
                thought = thinking.toString().strip();
                said = text.toString().strip();
                StringBuilder c = new StringBuilder();
                callNames.forEach((i, name) -> c.append(c.isEmpty() ? "" : "\n")
                        .append("⚙ ").append(name).append("  writing… ").append(callChars.getOrDefault(i, 0)).append(" characters"));
                if (!work.isEmpty()) c.append(c.isEmpty() ? "" : "\n").append(work);
                calls = c.toString();
            }
            if (thought.isEmpty() && said.isEmpty() && calls.isEmpty()) {
                if (box != null) messages.getChildren().remove(box);
                box = null;
                return;
            }
            if (box == null) {
                thinkingLabel = new Label();
                textLabel = new Label();
                callsLabel = new Label();
                for (Label l : List.of(thinkingLabel, textLabel, callsLabel)) {
                    l.setWrapText(true);
                    l.maxWidthProperty().bind(scroll.widthProperty().multiply(0.75).subtract(32));
                }
                thinkingLabel.getStyleClass().add("live-thinking");
                textLabel.getStyleClass().add("live-text");
                callsLabel.getStyleClass().add("live-calls");
                box = new VBox(6);
                box.getStyleClass().add("live-reply");
                box.maxWidthProperty().bind(scroll.widthProperty().multiply(0.75));
                messages.getChildren().add(box); // at the bottom; the height listener scrolls to it
            }
            // Thinking can get long: show a window over its end, the newest whole sentences that fit (ThoughtWindow); the whole
            // text goes to the transcript when the reply is finished
            thinkingLabel.setText(ThoughtWindow.latest(thought));
            textLabel.setText(said);
            callsLabel.setText(calls);
            box.getChildren().setAll(List.of(thinkingLabel, textLabel, callsLabel).stream()
                    .filter(l -> !l.getText().isEmpty()).toList());
        }
    }

    /** Removes the progress card (if any) when the turn ends. */
    private void removeProgressCard() {
        if (progressCard != null) messages.getChildren().remove(progressCard);
        progressCard = null;
    }

    /**
     * Adds a message bubble. {@code kind} is a CSS class ("user", "assistant", "error") that sets
     * colors and alignment; assistant text is rendered as Markdown, everything else as plain text.
     * The text is selectable either way (drag, Ctrl+C, right-click → Copy / Copy whole message).
     */
    private void addBubble(String author, String text, String kind) {
        Label who = new Label(author);
        who.getStyleClass().add("author");
        // Your messages may carry attached files: show only what you typed, and a chip per file
        Attachments.Parts parts = "user".equals(kind) ? Attachments.split(text) : new Attachments.Parts(text, List.of());
        text = parts.typed();
        RichTextArea body = switch (kind) {
            case "assistant" -> MarkdownView.render(text, this::openLink); // the model answers in Markdown
            case "user" -> MarkdownView.plain(text, Color.WHITE);         // white on the blue bubble
            case "note" -> MarkdownView.plain(text, Color.web("#49454f"));  // thinking, job notes: muted gray
            default -> MarkdownView.plain(text, Color.web("#410e0b"));    // errors: dark red on light red
        };
        // Text width available inside a bubble: 75% of the transcript minus the bubble's padding
        DoubleBinding available = scroll.widthProperty().multiply(0.75).subtract(32);
        if ("assistant".equals(kind)) {
            body.prefWidthProperty().bind(available); // answers use the full bubble width and wrap there
        } else {
            // Short messages get a short bubble: as wide as their longest line, at most the available width
            body.prefWidthProperty().bind(Bindings.min(available, naturalWidth(text)));
        }
        forwardScrolling(body);

        VBox bubble = new VBox(4, who, body);
        if (!parts.files().isEmpty()) {
            // One chip per attached file; clicking it shows the file's text in the side panel
            FlowPane files = new FlowPane(6, 6);
            for (Attachments.Attachment file : parts.files()) {
                Button chip = new Button("▤ " + file.name());
                chip.getStyleClass().add("attachment-chip");
                chip.setMnemonicParsing(false);
                ToolEntry entry = new ToolEntry(file.name(), "Attached file");
                entry.complete(file.text(), false);
                chip.setOnAction(e -> openToolPanel(entry));
                files.getChildren().add(chip);
            }
            bubble.getChildren().add(files);
        }
        bubble.getStyleClass().addAll("bubble", kind); // e.g. ".bubble.user" = blue, right-aligned look
        // At most 75% of the transcript width; text wraps inside, and it follows window resizes
        bubble.maxWidthProperty().bind(scroll.widthProperty().multiply(0.75));
        // Never shrink below the height the content needs (otherwise long answers get clipped)
        bubble.setMinHeight(Region.USE_PREF_SIZE);

        // A row per bubble lets us align it: your messages right, everything else left
        HBox row = new HBox(bubble);
        row.setAlignment("user".equals(kind) ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messages.getChildren().add(row);
    }

    /**
     * A muted note in the transcript: the model's thinking, or a coding job's own words (findings, plan
     * drafts). Long notes show their start and a "Show all" link that opens the whole text in the side panel.
     */
    private void addNote(String author, String text) {
        String shown = text.length() > NOTE_CHARS ? text.substring(0, NOTE_CHARS).strip() + " …" : text;
        addBubble(author, shown, "note");
        if (shown.length() < text.length()) {
            ToolEntry entry = new ToolEntry(author, "");
            entry.complete(text, false);
            Hyperlink all = new Hyperlink("Show all (" + text.length() + " characters)");
            all.getStyleClass().add("tool-link");
            all.setOnAction(e -> openToolPanel(entry));
            messages.getChildren().add(all);
        }
    }

    private static final int NOTE_CHARS = 600; // a note longer than this is cut, with a "Show all" link

    /** + Attach: pick text files for the next message. */
    private void chooseAttachments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach files");
        Path folder = conversationSettings.workFolderOrDefault();
        if (Files.isDirectory(folder)) chooser.setInitialDirectory(folder.toFile()); // start in the trusted folder
        List<File> picked = chooser.showOpenMultipleDialog(stage); // null on Cancel
        if (picked != null) attach(picked);
        input.requestFocus();
    }

    /** Reads the files and adds them to the next message; files that can't be attached are reported. */
    private void attach(List<File> files) {
        List<String> refused = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) continue; // folders are skipped
            try {
                pendingFiles.add(Attachments.read(file.toPath()));
            } catch (IOException | RuntimeException e) {
                refused.add(e.getMessage());
            }
        }
        showPendingFiles();
        if (!refused.isEmpty()) {
            showError("Only text files can be attached", new IOException(String.join("\n", refused)));
        }
    }

    /** The chips above the input: one per file waiting to be sent, each with ✕ to remove it. */
    private void showPendingFiles() {
        pendingChips.getChildren().clear();
        for (Attachments.Attachment file : pendingFiles) {
            Button chip = new Button("▤ " + file.name() + "   ✕");
            chip.getStyleClass().add("folder-button");
            chip.setMnemonicParsing(false);
            chip.setTooltip(new Tooltip("Remove " + file.name()));
            chip.setOnAction(e -> {
                pendingFiles.remove(file);
                showPendingFiles();
            });
            pendingChips.getChildren().add(chip);
        }
        pendingChips.setVisible(!pendingFiles.isEmpty());
    }

    /** The width of the longest line of {@code text} in the bubble font, plus a little room for the control's padding. */
    private static double naturalWidth(String text) {
        double widest = 0;
        for (String line : text.split("\n")) {
            Text measure = new Text(line);
            measure.setFont(Font.font("System", 15));
            widest = Math.max(widest, measure.getLayoutBounds().getWidth());
        }
        return widest + 30; // the text control adds some inner padding; a little extra avoids an early wrap
    }

    /**
     * Mouse-wheel scrolling over a message scrolls the transcript. The text control would otherwise
     * take the wheel events itself, although it never scrolls (it's as tall as its text).
     */
    private void forwardScrolling(RichTextArea area) {
        area.addEventFilter(ScrollEvent.SCROLL, e -> {
            double hidden = messages.getHeight() - scroll.getViewportBounds().getHeight(); // scrollable distance
            if (hidden > 0) scroll.setVvalue(scroll.getVvalue() - e.getDeltaY() / hidden);
            e.consume();
        });
    }

    /** Opens web and mail links from answers in the default browser; other schemes are ignored. */
    /**
     * Shows a link to a coding job's plan file, once per file: "📄 Plan · 2026-10-18-…-goal.md" opens
     * it in the side panel (read fresh on every click, so it shows the latest plan or the final report),
     * "Open file" in the system's editor. The file isn't part of the conversation.
     */
    private void showPlanFile(String path) {
        if (!planFiles.add(path)) return; // already linked; clicking reads the current content
        Path file = Path.of(path);
        Hyperlink view = new Hyperlink("📄 Plan · " + file.getFileName());
        view.getStyleClass().add("tool-link");
        view.setOnAction(e -> {
            ToolEntry entry = new ToolEntry("Plan", path);
            try {
                entry.complete(Files.readString(file), false);
            } catch (IOException ex) {
                entry.complete("Could not read the plan file: " + ex.getMessage(), true);
            }
            openToolPanel(entry);
        });
        Hyperlink external = new Hyperlink("Open file");
        external.getStyleClass().add("tool-link");
        external.setOnAction(e -> openFile(path));
        HBox row = new HBox(6, view, external);
        row.setAlignment(Pos.CENTER_LEFT);
        messages.getChildren().add(row);
    }

    /** Opens a local file (a coding job's plan) in the app the system uses for it. */
    private void openFile(String path) {
        getHostServices().showDocument(Path.of(path).toUri().toString());
    }

    private void openLink(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            // HostServices: JavaFX's way to hand a URL to the operating system's default browser/mail app
            getHostServices().showDocument(url);
        }
    }

    /** New chat: an empty transcript and a fresh, not-yet-saved conversation (saved after its first answer). */
    private void resetConversation() {
        clearTranscript();
        history = Agent.newConversation();
        threadId = Agent.newThreadId();   // new id = new file and new LangSmith thread
        conversationTitle = null;         // set from the first question
        if (traffic != null) traffic.showConversation(threadId, null); // its own, still empty, calls
        conversationSettings = Settings.newConversation(globalSettings); // global default folder, no extra commands
        conversationSummary = ConversationSummary.EMPTY;                  // a new chat remembers nothing
        conversationFacts = List.of();
        showFolder();
        selectInList(null);               // nothing selected: this conversation isn't in the list yet
        input.requestFocus();
    }

    /** New chat whose trusted folder is {@code folder} (the folder menu in the sidebar). */
    private void newConversationIn(Path folder) {
        resetConversation();
        conversationSettings = conversationSettings.withWorkFolder(folder);
        showFolder();
    }

    /** Empties the transcript and forgets its tool chips. */
    private void clearTranscript() {
        messages.getChildren().clear();
        toolLinks.clear();
        lastPill = null;
        planFiles.clear();
        closeToolPanel();
    }

    // --- Conversations sidebar ---

    /** The left drawer: New chat, a heading and the saved conversations grouped by trusted folder (newest first). */
    private void buildSidebar() {
        newChat.setMaxWidth(Double.MAX_VALUE);
        newChat.getStyleClass().add("new-chat");
        newChat.setOnAction(e -> resetConversation());

        Label heading = new Label("Conversations");
        heading.getStyleClass().add("sidebar-heading");

        conversations.setShowRoot(false);
        conversations.getStyleClass().add("conversation-list");
        // A cell factory decides how each row looks; the tree reuses cells while scrolling
        conversations.setCellFactory(tree -> new ConversationCell());
        // Clicking a conversation selects it; opening happens in reaction to the selection change. A folder row is a
        // heading: the highlight goes back to the open conversation
        conversations.getSelectionModel().selectedItemProperty().addListener((obs, old, picked) -> {
            if (selectingProgrammatically || picked == null) return;
            if (picked.getValue() instanceof Summary summary) {
                if (!summary.id().equals(threadId)) open(summary);
            } else {
                Platform.runLater(() -> selectInList(threadId));
            }
        });
        VBox.setVgrow(conversations, Priority.ALWAYS); // the list takes the remaining height

        sidebar.getChildren().addAll(newChat, heading, conversations);
        sidebar.setPadding(new Insets(12));
        sidebar.setPrefWidth(ui.sidebarWidth != null ? ui.sidebarWidth : 260);
        // Persist the width whenever it changes (e.g. by a resize handle), not only on window resize
        sidebar.prefWidthProperty().addListener((obs, oldV, newV) -> {
            ui.sidebarWidth = newV.doubleValue();
            ui.save();
        });
        sidebar.getStyleClass().add("sidebar");
        refreshList(); // read the conversations folder
    }

    /**
     * A sidebar row. A folder: its name, a menu (new conversation in it, copy its path) and the same menu on right-click.
     * A conversation: title and last-updated time; right-click to rename or delete.
     */
    private final class ConversationCell extends TreeCell<Object> {
        private static final DateTimeFormatter WHEN =
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

        ConversationCell() {
            // Don't ask for any width of our own: the row is exactly as wide as the tree, so there's
            // never a horizontal scrollbar (also not when the vertical one takes some width)
            setPrefWidth(0);
        }

        /** Called whenever this cell is (re)used for another row, or for no row. */
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);          // the rows show a node instead of the default text
            setTooltip(null);
            if (empty || item == null) { // cells below the last row: clear them, or they show stale content
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (item instanceof Path folder) {
                folderRow(folder);
            } else {
                conversationRow((Summary) item);
            }
        }

        private void folderRow(Path folder) {
            Path name = folder.getFileName(); // null for a drive root
            Label title = new Label("📁 " + (name != null ? name : folder));
            title.getStyleClass().add("conversation-folder");
            title.setMinWidth(0);
            setTooltip(new Tooltip(folder.toString()));
            MenuItem create = new MenuItem("New conversation here");
            create.setOnAction(e -> newConversationIn(folder));
            MenuItem copy = new MenuItem("Copy path");
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(folder.toString());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            });
            MenuButton menu = new MenuButton("⋯", null, create, copy);
            menu.getStyleClass().add("folder-menu");
            menu.setTooltip(new Tooltip("New conversation in this folder"));
            HBox.setHgrow(title, Priority.ALWAYS);
            title.setMaxWidth(Double.MAX_VALUE);
            HBox box = new HBox(4, title, menu);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            setGraphic(box);
            MenuItem createAgain = new MenuItem("New conversation here");
            createAgain.setOnAction(e -> newConversationIn(folder));
            setContextMenu(new ContextMenu(createAgain));
        }

        private void conversationRow(Summary item) {
            Label title = new Label(item.title());
            title.getStyleClass().add("conversation-title");
            Label when = new Label(WHEN.format(item.updated()));
            when.getStyleClass().add("conversation-when");
            VBox box = new VBox(2, title, when);
            box.setMaxWidth(Double.MAX_VALUE);
            title.setMinWidth(0);   // may shrink below its text width: long titles end in "…"

            // Menu button with Rename and Delete actions
            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> rename(item));
            MenuItem delete = new MenuItem("Delete");
            delete.setOnAction(e -> delete(item));
            MenuButton menuButton = new MenuButton("⋯", null, rename, delete);
            menuButton.getStyleClass().add("folder-menu");

            HBox hbox = new HBox(5, box, menuButton);
            hbox.setMaxWidth(Double.MAX_VALUE);
            setGraphic(hbox);

            rename = new MenuItem("Rename…");
            rename.setOnAction(e -> rename(item));
            delete = new MenuItem("Delete");
            delete.setOnAction(e -> delete(item));

            // Right-click menu per row
            setContextMenu(new ContextMenu(rename, delete));
        }
    }

    /** Loads a saved conversation and replaces the transcript with it (on the FX thread; files are small). */
    private void open(Summary summary) {
        Conversation conversation;
        try {
            conversation = store.load(summary.id());
        } catch (RuntimeException e) {
            showError("Could not open the conversation", e);
            refreshList();
            return;
        }
        // Make it the open conversation: the next message continues it (same id = same file and thread)
        // A conversation saved in the middle of a turn can end with a call that has no result: the app was closed during it
        List<ChatMessage> repaired = net.rka.ai.agent.OpenCalls.closed(conversation.messages(), "the app was closed before this call finished.");
        boolean cutOff = repaired != conversation.messages() || (!repaired.isEmpty() && repaired.get(repaired.size() - 1) instanceof UserMessage);
        history = new ArrayList<>(repaired);
        threadId = conversation.id();
        if (traffic != null) traffic.showConversation(threadId, conversation.title());
        ui.lastConversation = threadId;
        ui.save();
        conversationTitle = conversation.title();
        conversationSettings = conversation.settings();
        conversationSummary = conversation.summary();
        conversationFacts = conversation.facts();
        showFolder();
        clearTranscript();
        history.forEach(this::render); // rebuild bubbles and tool chips from the saved messages
        if (cutOff) {
            addBubble("Context", "The app was closed while this conversation was working: the last request has no answer. Send it again, or say \"continue\".", "assistant");
        }
        input.requestFocus();
    }

    /** Asks for a new title and writes it to the conversation's file. */
    private void rename(Summary summary) {
        TextInputDialog dialog = new TextInputDialog(summary.title());
        dialog.initOwner(stage);
        dialog.setTitle("Rename conversation");
        dialog.setHeaderText(null);
        dialog.setContentText("Title:");
        // showAndWait returns an Optional: empty on Cancel; blank titles are ignored too
        dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).ifPresent(newTitle -> {
            try {
                Conversation c = store.load(summary.id()); // load, change the title, save back
                store.save(c.withTitle(newTitle));
                if (c.id().equals(threadId)) {
                    conversationTitle = newTitle;
                    traffic.showConversation(threadId, newTitle);
                }
            } catch (RuntimeException e) {
                showError("Could not rename the conversation", e);
            }
            refreshList();
        });
    }

    /** Deletes a conversation's file after confirmation; deleting the open one starts a new chat. */
    private void delete(Summary summary) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + summary.title() + "\"? This can't be undone.", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            store.delete(summary.id());
            traffic.forget(summary.id()); // its calls in the traffic sidebar go with it
        } catch (RuntimeException e) {
            showError("Could not delete the conversation", e);
        }
        if (summary.id().equals(threadId)) resetConversation();
        refreshList();
    }

    /** Picks the open conversation's trusted folder; a saved conversation keeps the new folder. */
    private void chooseFolder() {
        Path workFolder = conversationSettings.workFolderOrDefault();
        // The operating system's native folder picker
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Trusted folder for this conversation");
        if (Files.isDirectory(workFolder)) chooser.setInitialDirectory(workFolder.toFile());
        File picked = chooser.showDialog(stage); // blocks until chosen; null on Cancel
        if (picked == null) return;
        setConversationSettings(conversationSettings.withWorkFolder(picked.toPath().toAbsolutePath().normalize()));
        input.requestFocus();
    }

    /** ⚙ Conversation: edit this conversation's folder and extra trusted commands. */
    /** 🧠 Memory: shows the summary, and lets you add or delete this conversation's facts. */
    private void editMemory() {
        MemoryDialog.edit(stage, conversationSummary, conversationFacts).ifPresent(facts -> {
            conversationFacts = facts;
            showFolder(); // update the fact count on the chip
            // Saved conversations get the change right away; a new chat saves it with its first answer
            if (saved.stream().anyMatch(s -> s.id().equals(threadId))) {
                try {
                    store.save(store.load(threadId).withFacts(facts));
                } catch (RuntimeException e) {
                    showError("Could not save the conversation's facts", e);
                }
            }
        });
        input.requestFocus();
    }

    private void editConversationSettings() {
        // The dialog returns the edited settings, or nothing on Cancel
        SettingsDialog.editConversation(stage, conversationSettings, globalSettings)
                .ifPresent(this::setConversationSettings);
        input.requestFocus();
    }

    /**
     * Adds an "always allow" answer's entry to the conversation's or the global trusted commands.
     * Runs on the FX thread (inside {@link #approve}). It takes effect from the next message: the
     * running turn keeps the trusted list it started with.
     */
    private void trust(ApprovalDialog.Answer answer) {
        if (answer.scope() == ApprovalDialog.Scope.NONE || answer.entry() == null || answer.entry().isBlank()) return;
        if (answer.scope() == ApprovalDialog.Scope.CONVERSATION) {
            // Settings are immutable records: copy the list, add, build new settings (duplicates are dropped)
            List<String> commands = new ArrayList<>(conversationSettings.trustedCommands());
            commands.add(answer.entry());
            setConversationSettings(conversationSettings.withTrustedCommands(commands)); // also writes the file if saved
        } else {
            List<String> commands = new ArrayList<>(globalSettings.trustedCommands());
            commands.add(answer.entry());
            Settings updated = globalSettings.withTrustedCommands(commands);
            try {
                Settings.saveGlobal(updated); // ~/chat/settings.json
                globalSettings = updated;
            } catch (RuntimeException e) {
                showError("Could not save " + Settings.globalFile(), e);
            }
        }
    }

    /** ⚙ Settings: edit the global default folder and trusted commands. */
    private void editGlobalSettings() {
        SettingsDialog.editGlobal(stage, globalSettings, agent, busy.isVisible()).ifPresent(updated -> {
            try {
                Settings.saveGlobal(updated);   // write first...
                globalSettings = updated;       // ...then use it, so the window never shows unsaved settings
            } catch (RuntimeException e) {
                showError("Could not save " + Settings.globalFile(), e);
            }
        });
        input.requestFocus();
    }

    /** Applies the open conversation's settings, and writes them to its file if it's saved already. */
    private void setConversationSettings(Settings updated) {
        conversationSettings = updated;
        showFolder();
        // Only conversations already in the list have a file; a new chat saves its settings with the first answer
        if (saved.stream().anyMatch(s -> s.id().equals(threadId))) {
            try {
                store.save(store.load(threadId).withSettings(updated)); // load, replace settings, save
            } catch (RuntimeException e) {
                showError("Could not save the conversation settings", e);
            }
        }
    }

    /** Updates the two chips under the input from the open conversation's settings. */
    private void showFolder() {
        Path workFolder = conversationSettings.workFolderOrDefault();
        Path name = workFolder.getFileName(); // just the last part, e.g. "schlu"; null for a drive root
        folderButton.setText("📁 " + (name != null ? name : workFolder));
        folderButton.setTooltip(new Tooltip("Trusted folder: " + workFolder + "\nFile tools can read, write and "
                + "delete only inside it, without asking. Terminal commands start here and still ask.\n"
                + "Click to choose another folder for this conversation."));
        int extra = conversationSettings.trustedCommands().size();
        conversationSettingsButton.setText(extra == 0 ? "⚙ Conversation" : "⚙ Conversation · " + extra + " trusted");
        int facts = conversationFacts.size();
        memoryButton.setText(facts == 0 ? "✦ Memory" : "✦ Memory · " + facts + (facts == 1 ? " fact" : " facts"));
        showHealth();
    }

    /** The conversation's health on its chip: green, amber or red by what went wrong lately (see {@link ConversationHealth}). */
    private void showHealth() {
        ConversationHealth health = ConversationHealth.of(history, conversationSummary, Agent.contextWindowTokens());
        healthButton.getStyleClass().removeAll("health-ok", "health-watch", "health-drifting");
        healthButton.getStyleClass().add("health-" + health.level().name().toLowerCase());
        healthButton.setText(switch (health.level()) {
            case OK -> "● Context";
            case WATCH -> "● Context · watch";
            case DRIFTING -> "● Context · drifting";
        });
        healthButton.setTooltip(new Tooltip(health.reasons().isEmpty() ? "The conversation looks fine. Click to see how its context is built."
                : String.join("\n", health.reasons()) + "\nClick to see what you can do."));
    }

    /** The chip's window: what the level is based on, and the reset that rebuilds the model's context from the record. */
    private void showHealthDialog() {
        ConversationHealth health = ConversationHealth.of(history, conversationSummary, Agent.contextWindowTokens());
        javafx.scene.control.ButtonType reset = new javafx.scene.control.ButtonType("Reset context", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.NONE, "", reset, javafx.scene.control.ButtonType.CLOSE);
        alert.initOwner(stage);
        alert.setTitle("Conversation context");
        alert.setHeaderText(switch (health.level()) {
            case OK -> "The conversation looks fine";
            case WATCH -> "Something went wrong lately: worth watching";
            case DRIFTING -> "The conversation is drifting: a reset is advised";
        });
        alert.setContentText((health.reasons().isEmpty() ? "Nothing went wrong lately.\n" : "- " + String.join("\n- ", health.reasons()) + "\n")
                + "\nReset context rebuilds what the model knows from your own messages, the task and how the coding jobs ended: nothing the assistant"
                + " wrote is kept. The transcript on screen stays as it is.");
        if (alert.showAndWait().filter(reset::equals).isPresent()) resetContext();
        input.requestFocus();
    }

    private void resetContext() {
        conversationSummary = ContextReset.summary(history, conversationFacts);
        if (saved.stream().anyMatch(s -> s.id().equals(threadId))) {
            try {
                store.save(store.load(threadId).withSummary(conversationSummary));
            } catch (RuntimeException e) {
                showError("Could not save the conversation's context", e);
            }
        }
        showHealth();
        addBubble("Context", "The model's context was rebuilt from your messages, the task and the outcome of each coding job. The transcript above stays as it is.", "assistant");
    }

    /** Writes a conversation and refreshes the sidebar (its date changed, so it moves to the top). */
    private void saveConversation(Conversation conversation) {
        try {
            store.save(conversation);
        } catch (RuntimeException e) {
            showError("Could not save the conversation to " + store.dir().toAbsolutePath(), e);
        }
        refreshList();
    }

    /** Reloads the sidebar and keeps the open conversation selected. */
    private void refreshList() {
        List<Summary> found;
        try {
            found = store.list();
        } catch (RuntimeException e) {
            found = List.of();
            showError("Could not read " + store.dir().toAbsolutePath(), e);
        }
        saved = found;
        // One folder row per trusted folder, the one with the newest conversation first (the list is newest first); the
        // default folder and the open conversation's folder are always there, so a new conversation can start in them
        Map<String, List<Summary>> byFolder = new LinkedHashMap<>();
        for (Summary summary : found) byFolder.computeIfAbsent(summary.folder(), f -> new ArrayList<>()).add(summary);
        byFolder.computeIfAbsent(conversationSettings.workFolderOrDefault().toAbsolutePath().toString(), f -> new ArrayList<>());
        byFolder.computeIfAbsent(globalSettings.workFolderOrDefault().toAbsolutePath().toString(), f -> new ArrayList<>());
        TreeItem<Object> root = new TreeItem<>();
        byFolder.forEach((folder, summaries) -> {
            TreeItem<Object> folderItem = new TreeItem<>(Path.of(folder));
            summaries.forEach(s -> folderItem.getChildren().add(new TreeItem<>(s)));
            folderItem.setExpanded(!collapsedFolders.contains(folder));
            folderItem.expandedProperty().addListener((obs, was, expanded) -> {
                if (expanded) collapsedFolders.remove(folder);
                else collapsedFolders.add(folder);
            });
            root.getChildren().add(folderItem);
        });
        selectingProgrammatically = true; // replacing the rows changes the selection; that isn't a click
        try {
            conversations.setRoot(root);
        } finally {
            selectingProgrammatically = false;
        }
        selectInList(threadId); // highlight the open conversation again
    }

    /** Highlights the row with this id (or none), without triggering the "open" listener. */
    private void selectInList(String id) {
        selectingProgrammatically = true;
        try {
            conversations.getSelectionModel().clearSelection();
            if (id == null || conversations.getRoot() == null) return;
            for (TreeItem<Object> folder : conversations.getRoot().getChildren()) {
                for (TreeItem<Object> row : folder.getChildren()) {
                    if (row.getValue() instanceof Summary s && s.id().equals(id)) {
                        conversations.getSelectionModel().select(row);
                        return;
                    }
                }
            }
        } finally {
            selectingProgrammatically = false;
        }
    }

    /** Shows an error dialog (or prints it, if the window doesn't exist yet). */
    private void showError(String what, Exception e) {
        if (stage == null) {
            System.err.println("[conversations] " + what + ": " + e.getMessage());
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR, what + ":\n" + e.getMessage(), ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.show(); // show(), not showAndWait(): nothing needs to wait for it to be closed
    }

    /** While a turn runs: show the spinner and lock everything that would change the open conversation. */
    private void setBusy(boolean value) {
        busy.setVisible(value);
        send.setText(value ? "Stop" : "Send"); // the send button stops the turn while it runs
        newChat.setDisable(value); // a turn saves into the open conversation; don't switch mid-turn
        conversations.setDisable(value);
        folderButton.setDisable(value);
        conversationSettingsButton.setDisable(value);
        memoryButton.setDisable(value); // the turn may change the facts; edit them afterwards
        healthButton.setDisable(value);
        if (!value) showHealth();
        attachButton.setDisable(value);
        if (!value) input.requestFocus(); // ready for the next question
    }

    // --- Where the person was ---

    /** The window where it was (when that place is still on a screen), and the sidebar as it was. */
    private void restorePlace() {
        if (ui.hasBounds() && !javafx.stage.Screen.getScreensForRectangle(ui.x, ui.y, ui.width, ui.height).isEmpty()) {
            stage.setX(ui.x);
            stage.setY(ui.y);
        }
        if (ui.maximized) stage.setMaximized(true);
        sidebar.setVisible(ui.sidebarVisible);
        sidebar.setManaged(ui.sidebarVisible);
        setTrafficVisible(ui.trafficVisible, false); // the saved width already has its room
    }

    /**
     * Opens or closes the sidebar with the traffic of the model. Opening gives the window the sidebar's width (closing takes it back) so
     * the chat keeps its size, unless the window is maximized.
     */
    private void setTrafficVisible(boolean show, boolean resize) {
        boolean was = trafficPanel.isVisible();
        trafficPanel.setVisible(show);
        trafficPanel.setManaged(show);
        trafficToggle.setSelected(show);
        ui.trafficVisible = show;
        if (resize && show != was && stage != null && !stage.isMaximized() && !stage.isFullScreen()) {
            stage.setWidth(Math.max(stage.getMinWidth(), stage.getWidth() + (show ? TRAFFIC_WIDTH : -TRAFFIC_WIDTH)));
        }
        ui.save();
    }

    /** A call is held and waits for the person: the sidebar opens itself and the window comes to the front. */
    private void revealTraffic() {
        if (!trafficPanel.isVisible()) setTrafficVisible(true, true);
        if (stage != null) stage.toFront();
    }

    /** The text that was typed and not sent, and the conversation that was open. */
    private void restoreSession() {
        if (ui.draft != null && !ui.draft.isEmpty()) input.setText(ui.draft);
        if (ui.lastConversation != null) saved.stream().filter(s -> s.id().equals(ui.lastConversation)).findFirst().ifPresent(this::open);
    }

    /** From now on every change of place, size, sidebar or typed text is written (a moment after the last one). */
    private void watchPlace() {
        saveUiSoon.setOnFinished(e -> ui.save());
        Runnable changed = () -> {
            if (!stage.isMaximized() && !stage.isIconified()) {
                ui.x = stage.getX();
                ui.y = stage.getY();
                ui.width = stage.getWidth();
                ui.height = stage.getHeight();
            }
            ui.maximized = stage.isMaximized();
            ui.sidebarVisible = sidebar.isVisible();
            ui.draft = input.getText();
            saveUiSoon.playFromStart();
        };
        stage.xProperty().addListener((o, was, now) -> changed.run());
        stage.yProperty().addListener((o, was, now) -> changed.run());
        stage.widthProperty().addListener((o, was, now) -> changed.run());
        stage.heightProperty().addListener((o, was, now) -> changed.run());
        stage.maximizedProperty().addListener((o, was, now) -> changed.run());
        sidebar.visibleProperty().addListener((o, was, now) -> changed.run());
        input.textProperty().addListener((o, was, now) -> changed.run());
    }

    /** Called by JavaFX when the window closes. */
    @Override
    public void stop() {
        worker.shutdownNow(); // interrupt a running turn instead of waiting for it
        if (agent != null) {
            agent.close(); // flushes pending LangSmith spans
        }
        Platform.exit(); // end the JavaFX runtime
    }
}
