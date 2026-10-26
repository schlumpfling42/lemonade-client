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
 * which investigates, plans, asks the user to approve the plan, implements and verifies, and answers
 * the call with its report. The method body only exists because a {@code @Tool} needs one; should a
 * call ever reach it (a routing bug), it fails, and the model gets the error as the call's result.
 * Inside a coding job the tool is neither offered nor run ({@code CodingGraph.NOT_IN_JOB}).
 */
public class CodingTools {

    @Tool("Starts a coding job for multi-step coding work in the trusted folder: creating a new project, "
            + "adding a framework or library, a larger refactoring, or design work across several files. "
            + "The job investigates the folder, writes a plan that the user approves, implements and verifies "
            + "it, and returns a report. For small edits (one file, a quick fix, a single command) use the "
            + "file and command tools directly instead.")
    public String startCodingJob(@P("what to build or change, in one or two sentences") String goal) {
        throw new IllegalStateException("handled by the coding subgraph");
    }
}
