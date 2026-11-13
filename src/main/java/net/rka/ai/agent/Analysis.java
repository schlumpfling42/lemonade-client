package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the model made of the user's latest message: the one thing that happens next, and for a route the goal, written so that it stands
 * alone. The model answers with a call of one function (the native way of a model to decide; the server checks the arguments against the
 * schema), and the program acts on that call; nothing else of what the model wrote matters.
 * <pre>
 * route(goal, edge)             go to that node (the step ends), with the goal
 * ask_user(question, options)   ask the user, then ask again
 * a read tool (name, args)      run the tool, then ask again
 * </pre>
 *
 * @param goal what the user wants, in one sentence that stands alone; "" unless the model routed
 * @param next what happens next
 */
record Analysis(String goal, Next next) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What happens next. */
    sealed interface Next permits Edge, Tool, Ask {
    }

    /** Go to a node of the graph: the name of one of the edges the node offers. */
    record Edge(String name) implements Next {
    }

    /** Run a tool of the registry with these arguments ({@code id}: the id of the call the model made, kept so the result answers it). */
    record Tool(String name, JsonNode args, String id) implements Next {
    }

    /** Ask the user; no options means a free answer. */
    record Ask(String question, List<String> options) implements Next {
        Ask {
            options = List.copyOf(options);
        }
    }

    /** What the model may name from the node: the edges and the tools it was offered. */
    record Offered(Set<String> edges, Set<String> tools, boolean ask) {
    }

    /** The question of an {@link Ask} as the JSON the graph keeps in its state ({@code {"question", "options"}}). */
    static String questionJson(Ask ask) {
        com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode().put("question", ask.question());
        ask.options().forEach(node.putArray("options")::add);
        return node.toString();
    }

    /** The question text of {@link #questionJson}. */
    static String questionOf(String json) {
        try {
            return JSON.readTree(json).path("question").asText("");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return json;
        }
    }

    /** The options of {@link #questionJson}. */
    static List<String> optionsOf(String json) {
        List<String> options = new ArrayList<>();
        try {
            JSON.readTree(json).path("options").forEach(o -> options.add(o.asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // no options: a free answer
        }
        return options;
    }

    /** The function that routes, as the model is offered it. */
    static final String ROUTE = "route";
    /** The function that asks the user, as the model is offered it. */
    static final String ASK_USER = "ask_user";

    /**
     * Reads the model's answer: its first function call ({@code route}, {@code ask_user} or one of the offered tools). Further calls are
     * ignored: one decision per answer.
     *
     * @throws IllegalArgumentException with a sentence for the model when the answer cannot be used
     */
    static Analysis fromReply(dev.langchain4j.data.message.AiMessage reply, Offered offered) {
        if (reply == null || !reply.hasToolExecutionRequests()) {
            throw new IllegalArgumentException("you answered in text: call " + ROUTE + ", " + ASK_USER + " or one of the tools");
        }
        dev.langchain4j.agent.tool.ToolExecutionRequest call = reply.toolExecutionRequests().get(0);
        JsonNode args;
        try {
            args = call.arguments() == null || call.arguments().isBlank() ? JSON.createObjectNode() : JSON.readTree(call.arguments());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the arguments of " + call.name() + " are not valid JSON");
        }
        if (!args.isObject()) throw new IllegalArgumentException("the arguments of " + call.name() + " must be an object");
        switch (call.name()) {
            case ROUTE -> {
                String goal = args.path("goal").asText("").strip();
                if (goal.isEmpty()) throw new IllegalArgumentException("\"goal\" is missing or empty");
                String name = args.path("edge").asText("").strip();
                if (!offered.edges().contains(name)) {
                    throw new IllegalArgumentException("the edge \"" + name + "\" is not offered: use one of " + String.join(", ", new TreeSet<>(offered.edges())));
                }
                return new Analysis(goal, new Edge(name));
            }
            case ASK_USER -> {
                if (!offered.ask()) throw new IllegalArgumentException("asking the user is not offered now: call " + ROUTE);
                String question = args.path("question").asText("").strip();
                if (question.isEmpty()) throw new IllegalArgumentException("\"question\" must be the question for the user");
                List<String> options = new ArrayList<>();
                args.path("options").forEach(o -> {
                    if (o.isTextual() && !o.asText().isBlank()) options.add(o.asText().strip());
                });
                return new Analysis("", new Ask(question, options));
            }
            default -> {
                if (!offered.tools().contains(call.name())) {
                    throw new IllegalArgumentException("the function \"" + call.name() + "\" is not offered: use " + ROUTE + ", " + ASK_USER
                            + " or one of " + String.join(", ", new TreeSet<>(offered.tools())));
                }
                return new Analysis("", new Tool(call.name(), args, call.id()));
            }
        }
    }
}
