package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * The entry to coding jobs: one tool the model calls for multi-step coding work.
 *
 * <h2>How it works</h2>
 * The tool is only a signal. Its specification (name, description, the {@code goal} parameter) is
 * sent to the model like every other tool's, but a call never runs: {@code AgentGraph.route} sends
 * any reply with a {@code startCodingJob} call to the subgraph of the built-in skill {@code coding}
 * ({@code skill-coding}: {@code coding.json}, whose steps are {@code CodingGraph}'s; the skill's description
 * and input are this method's, and its tool specification replaces this one in the agent's tool list),
 * which finds the code, plans, asks the user to approve the plan, makes the changes and verifies them, and answers
 * the call with its report. The method body only exists because a {@code @Tool} needs one; should a
 * call ever reach it (a routing bug), it fails, and the model gets the error as the call's result.
 * Inside a coding job the tool is neither offered nor run ({@code CodingGraph.NOT_IN_JOB}).
 */
public class CodingTools {

    @Tool("Starts a coding job for coding work in the trusted folder: adding or changing a feature (for "
            + "example a button, dialog, setting or command in an app), creating a new project, adding a framework "
            + "or library, a refactoring, or design work across several files. Use it whenever the change needs "
            + "existing code to be read first or must still build afterwards. The job finds the code and, for work that can "
            + "be planned, writes a plan of the sections to change that the user approves, makes the changes, builds "
            + "and checks them; open-ended work (finding and fixing why a build or test fails, investigating a "
            + "problem) is done step by step with the tools instead, without a plan. It returns a report. Only for "
            + "tiny edits you can make right away (a typo, one value, a file you already read in this "
            + "conversation, a single command) use the file and command tools directly instead.")
    public String startCodingJob(@P("what to build or change, in one or two sentences") String goal) {
        throw new IllegalStateException("handled by the coding subgraph");
    }
}
