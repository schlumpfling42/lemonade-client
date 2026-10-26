package net.rka.ai.cli;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.ConversationSummary;
import net.rka.ai.agent.Progress;
import net.rka.ai.agent.Thought;
import net.rka.ai.config.Settings;

import java.util.List;

/**
 * Command-line entry point: asks one question and prints the answer.
 * Run with {@code ./gradlew runCli --args="your question"}.
 *
 * <h2>How it works</h2>
 * It's the smallest possible use of {@link Agent}: build the agent with a console approver, start a
 * history with the system prompt, add the question, run one turn while printing its tool calls and
 * thinking as they happen ({@link LiveOutput}), then print the last message (the final answer).
 * The global settings ({@code settings.json}) apply: trusted folder and trusted commands.
 * {@code try-with-resources} closes the agent at the end, which flushes pending trace spans.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>An interactive mode (a read–eval–print loop that keeps the conversation going).</li>
 *   <li>Saving/continuing conversations from the CLI ({@code --conversation <id>}), reusing ConversationStore.</li>
 *   <li>Flags for the folder, model, and a {@code --yes} option to approve everything (for scripts).</li>
 * </ul>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Gradle passes --args="a b c" as separate words; join them back into one question
        String question = args.length > 0
                ? String.join(" ", args)
                : "What is today's date, and what is 1234.5 multiplied by 42?";

        try (Agent agent = Agent.fromEnv(new ConsoleApprover())) {
            System.out.println("Model: " + agent.modelName() + " @ " + agent.baseUrl());
            printSkills(agent);
            System.out.println("Q: " + question);

            List<ChatMessage> history = Agent.newConversation();
            history.add(UserMessage.from(question));
            // Same as run(history, threadId), printing what happens as it happens (LiveOutput)
            Settings global = Settings.loadGlobal();
            LiveOutput live = new LiveOutput();
            List<ChatMessage> produced = agent.run(history, Agent.newThreadId(),
                    Settings.effective(global, Settings.newConversation(global)),
                    ConversationSummary.EMPTY, List.of(), live).messages();

            // The turn always ends with the model's plain answer (an AiMessage without tool calls);
            // with streaming it is on screen already
            if (!live.answerStreamed()) {
                System.out.println("A: " + Thought.of((AiMessage) produced.get(produced.size() - 1)).text());
            }
        }
    }

    /** Prints the loaded skills with their tools, then the files that couldn't be loaded with their errors. */
    private static void printSkills(Agent agent) {
        String names = agent.skillStatus().skills().stream().map(skill -> skill.name() + " (" + agent.skillTool(skill) + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        System.out.println("Skills: " + (names.isEmpty() ? "none" : names));
        agent.skillStatus().errors().forEach((file, problems) -> {
            System.out.println("Skill file " + file + " is not used:");
            problems.forEach(problem -> System.out.println("  - " + problem));
        });
    }

    /**
     * Prints what the turn does as it happens: the model's thinking and words token by token, each
     * tool call while the model writes it, the output of a running command line by line, each result's
     * first line, and coding-job progress. Lines from a coding job's own transcript start with "[job]".
     * The final answer streams like the rest; "A: ..." is printed at the end only when nothing streamed
     * (MODEL_STREAMING=false).
     */
    private static final class LiveOutput implements Progress.ProgressListener {
        private boolean inThinking;   // the current line is streamed thinking
        private boolean inText;       // the current line is streamed answer text
        private boolean streamed;     // the current reply streamed anything
        private boolean textStreamed; // the current reply streamed answer text
        private boolean answerStreamed;
        private String work = "";     // the hidden work announced last ("Writing the plan")
        private final java.util.Set<Integer> announcedCalls = new java.util.HashSet<>();

        @Override
        public void onProgress(Progress progress) {
            endLine();
            System.out.println(progress.label() + (progress.detail().isBlank() ? "" : " · " + progress.detail()));
        }

        @Override
        public synchronized void onPartialThinking(String delta) {
            if (!inThinking) {
                endLine();
                System.out.print("~ thinking: ");
                inThinking = true;
            }
            System.out.print(delta);
            System.out.flush();
            streamed = true;
        }

        @Override
        public synchronized void onPartialText(String delta) {
            if (!inText) {
                endLine();
                inText = true;
            }
            System.out.print(delta);
            System.out.flush();
            streamed = true;
            textStreamed = true;
        }

        @Override
        public synchronized void onPartialToolCall(int index, String toolName, String argumentsDelta) {
            if (toolName != null && !toolName.isBlank() && announcedCalls.add(index)) {
                endLine();
                System.out.println("⚙ " + toolName + " (writing…)");
            }
            streamed = true;
        }

        @Override
        public synchronized void onPartialWork(String label, int characters) {
            // The plan's JSON isn't printed (it goes to the plan file); say once what is being written
            if (!label.equals(work)) {
                endLine();
                System.out.println(label + "…");
                work = label;
            }
        }

        @Override
        public synchronized void onPlanFile(String path) {
            endLine();
            System.out.println("Plan file: " + path);
        }

        @Override
        public synchronized void onToolOutput(String callId, String chunk) {
            endLine();
            System.out.print(chunk.endsWith("\n") ? chunk : chunk + "\n"); // raw, as the command prints it
            System.out.flush();
        }

        @Override
        public synchronized void onMessage(ChatMessage message, boolean codingJob) {
            endLine();
            String job = codingJob ? "[job] " : "";
            if (message instanceof AiMessage ai) {
                Thought thought = Thought.of(ai);
                if (!streamed) {
                    // Nothing streamed (MODEL_STREAMING=false): show the reply now, the answer comes as "A:"
                    if (!thought.thinking().isBlank()) System.out.println(job + "~ thinking: " + oneLine(thought.thinking(), 300));
                    if (!thought.text().isBlank() && (codingJob || ai.hasToolExecutionRequests())) {
                        System.out.println(job + "… " + oneLine(thought.text(), 300));
                    }
                } else if (textStreamed && !codingJob && !ai.hasToolExecutionRequests()) {
                    answerStreamed = true; // the final answer is already on screen
                }
                if (ai.hasToolExecutionRequests()) {
                    ai.toolExecutionRequests().forEach(call ->
                            System.out.println(job + "⚙ " + call.name() + " " + oneLine(call.arguments(), 120)));
                }
                streamed = false;
                textStreamed = false;
                announcedCalls.clear();
            } else if (message instanceof ToolExecutionResultMessage result) {
                String state = Boolean.TRUE.equals(result.isError()) ? "failed" : "done";
                System.out.println(job + "  → " + result.toolName() + " " + state + ": " + oneLine(result.text(), 120));
            }
        }

        @Override
        public synchronized void onStepDone() {
            endLine();
            work = "";
            streamed = false;     // e.g. the memory node's summary streamed but isn't a reply
            textStreamed = false;
            announcedCalls.clear();
        }

        boolean answerStreamed() {
            return answerStreamed;
        }

        /** Ends a streamed line before printing something else. */
        private void endLine() {
            if (inThinking || inText) System.out.println();
            inThinking = false;
            inText = false;
        }

        private static String oneLine(String text, int max) {
            String flat = text.replaceAll("\s+", " ").strip();
            return flat.length() > max ? flat.substring(0, max - 1) + "…" : flat;
        }
    }
}
