package net.rka.ai.agent;

/**
 * What a running coding job is doing right now, reported by {@link Agent} while it streams the graph
 * (one per finished coding node), so the window can show live progress.
 *
 * @param phase  what the job is doing, e.g. "Checking tools", "Step 2/4", "Verifying (attempt 1/3)"
 * @param step   the current step (implement) or verify attempt; 0 for the other phases
 * @param steps  the plan's step count, or the maximum of verify attempts; 0 for the other phases
 * @param detail the command the job ran last, or "" if it ran none yet
 */
public record Progress(String phase, int step, int steps, String detail) {

    /** The {@code source} of the skill builder's messages ({@link ProgressListener#onJobMessage}). */
    public static final String SKILL_BUILDER = "skill builder";

    /**
     * The phase as the window and the terminal show it. A skill's or the skill builder's phase already
     * names its source ("&lt;skill&gt; · &lt;node&gt;", "Skill builder · draft"); a coding job's phase
     * ("Planning", "Step 2/5") doesn't, so it gets the "Coding · " prefix.
     */
    public String label() {
        return phase.contains(" · ") ? phase : "Coding · " + phase;
    }

    /**
     * Receives what a turn does while it runs; called on the thread that runs the graph, so it must
     * not block (hand the work to the UI thread).
     */
    @FunctionalInterface
    public interface ProgressListener {
        /** A coding job finished one of its nodes. */
        void onProgress(Progress progress);

        /**
         * A node added a message: a reply of the model (its text, thinking and tool calls) or a tool
         * result, right when it happens instead of at the end of the turn.
         *
         * @param codingJob true for a coding job's own messages (a job keeps none any more: it answers in data),
         *                  which are not part of the saved conversation
         */
        default void onMessage(dev.langchain4j.data.message.ChatMessage message, boolean codingJob) {
        }

        /**
         * A message of a coding job's or a skill's own transcript (never part of the saved conversation),
         * with where it comes from, so a window can label it and keep the two transcripts' tool calls apart
         * (each numbers its calls on its own). By default it goes to {@link #onMessage onMessage(message, true)}.
         *
         * @param codingTranscript true for the coding job's transcript ({@code codingMessages}), false for a
         *                         skill's or the skill builder's ({@code skillMessages})
         * @param source           who wrote it: the running skill's name ({@code coding} for the coding job),
         *                         or {@link #SKILL_BUILDER} for the skill builder
         */
        default void onJobMessage(dev.langchain4j.data.message.ChatMessage message, boolean codingTranscript, String source) {
            onMessage(message, true);
        }

        /*
         * Real-time output while a model reply is being generated or a command runs. The finished
         * reply or result follows through onMessage, which should replace what these showed.
         */

        /** The next piece of the model's thinking (reasoning models only). */
        default void onPartialThinking(String delta) {
        }

        /** The next piece of the model's answer text. */
        default void onPartialText(String delta) {
        }

        /**
         * The next piece of a tool call the model is writing.
         *
         * @param index          which call of the reply (0, 1, ...)
         * @param toolName       the tool's name, once the model has written it (may be null at first)
         * @param argumentsDelta the next characters of its JSON arguments
         */
        default void onPartialToolCall(int index, String toolName, String argumentsDelta) {
        }

        /**
         * A step is writing something that isn't shown (the plan JSON, the memory summary): what it
         * is doing and how many characters it has written so far.
         */
        default void onPartialWork(String label, int characters) {
        }

        /**
         * A coding job wrote its plan (or its final report) to this Markdown file, for you to review.
         */
        default void onPlanFile(String path) {
        }

        /** Output a running command just printed (stdout or stderr), for the call with this id. */
        default void onToolOutput(String callId, String chunk) {
        }

        /**
         * A graph node finished (after its messages went to onMessage). Partial output the node
         * streamed but that didn't become a message (e.g. the memory node's summary) can be dropped now.
         */
        default void onStepDone() {
        }
    }

    /** Ignores all progress. */
    public static final ProgressListener NONE = progress -> {
    };
}
