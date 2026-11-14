package net.rka.ai.ui;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sidebar with the traffic of the model, built on the real JavaFX toolkit (skipped where there is none, e.g. a machine without a display). */
class WireViewTest {

    private static final ChatRequest REQUEST = ChatRequest.builder().messages(SystemMessage.from("system"), UserMessage.from("add subtract")).build();
    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("done")).build();

    @BeforeAll
    static void toolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by an earlier test
        } catch (RuntimeException | UnsatisfiedLinkError | InternalError noDisplay) {
            Assumptions.abort("no JavaFX toolkit here: " + noDisplay);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    /** The controls inside a SplitPane exist for lookups only once the view has a scene and its skins are made. */
    private static WireView inScene(WireView view) {
        new javafx.scene.Scene(view, 600, 700);
        view.applyCss();
        view.layout();
        return view;
    }

    private static Button button(WireView view, String text) {
        return view.lookupAll(".button").stream().filter(n -> n instanceof Button b && text.equals(b.getText())).map(Button.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void aCallIsListedWithItsSizeAndTokensAndNothingWaits() throws Exception {
        WireGate gate = new WireGate();
        WireView view = onFx(() -> inScene(new WireView(gate, new UiState(), () -> { }, () -> { })));

        onFx(() -> {
            view.showRequest(1, REQUEST);
            view.showResponse(1, RESPONSE);
            return null;
        });

        assertEquals(1, onFx(() -> view.callLines().size()));
        assertTrue(onFx(() -> view.callLines().getFirst()).startsWith("#1 · 2 messages · 0 tools"));
    }

    @Test
    void aHeldRequestWaitsUntilSendAndGoesOnWithTheChangedText() throws Exception {
        WireGate gate = new WireGate();
        boolean[] revealed = {false};
        WireView view = onFx(() -> inScene(new WireView(gate, new UiState(), () -> revealed[0] = true, () -> { })));
        CompletableFuture<ChatRequest> answer = new CompletableFuture<>();

        onFx(() -> {
            view.askRequest(2, REQUEST, answer);
            return null;
        });
        assertTrue(revealed[0], "a held call opens the sidebar");
        assertTrue(!answer.isDone());
        onFx(() -> {
            TextArea area = view.lookupAll(".text-area").stream().map(TextArea.class::cast).findFirst().orElseThrow();
            area.setText(area.getText().replace("add subtract", "add divide"));
            button(view, "Send").fire();
            return null;
        });

        ChatRequest sent = answer.get(5, TimeUnit.SECONDS);
        assertEquals("add divide", ((UserMessage) sent.messages().getLast()).singleText());
    }

    @Test
    void anOlderCallCanBeLookedAtAfterANewerOneArrived() throws Exception {
        WireGate gate = new WireGate();
        WireView view = onFx(() -> inScene(new WireView(gate, new UiState(), () -> { }, () -> { })));

        onFx(() -> {
            view.showRequest(1, REQUEST);
            view.showResponse(1, RESPONSE);
            view.showRequest(2, ChatRequest.builder().messages(UserMessage.from("second")).build());
            return null;
        });

        assertEquals(2, onFx(() -> view.callLines().size()));
    }
}
