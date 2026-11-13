package net.rka.ai.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a run cost and how it went, read from the agent log of the run ({@link net.rka.ai.tracing.AgentLog}): model calls, time in the
 * model, tokens, tool calls and their errors, changes applied and refused, repeats the graph caught, the sizes of the openings.
 * Read from text, so the same parser reads any log written earlier.
 *
 * @param llmSeconds      seconds spent in model calls
 * @param tokensIn        prompt tokens the server reported (calls without a count add nothing)
 * @param openingKchars   thousands of characters in the openings of the coding job's own calls ({@code ctx} lines)
 * @param callsByPhase    model calls by phase ({@code agent}, {@code triage}, {@code skill/coding/edit}, ...)
 * @param triagedCoding   whether the turn was started as a coding job by triage
 */
public record EvalMetrics(int llmCalls, double llmSeconds, long tokensIn, long tokensOut, int llmFailures, int toolCalls, int toolErrors,
                          int changesApplied, int changesRefused, int repeats, double openingKchars, boolean triagedCoding,
                          Map<String, Integer> callsByPhase) {

    private static final Pattern LLM = Pattern.compile("← LLM #\\d+ \\[([^\\]]+)\\] ([\\d.]+) s in all, tokens in ([\\d,?]+) / out ([\\d,?]+)");
    private static final Pattern TOOL = Pattern.compile("⚙ tool (\\w+) .*→ (ok|error)");
    private static final Pattern CTX = Pattern.compile("ctx \\w+: .*= ([\\d.]+)k chars");

    public static EvalMetrics parse(List<String> lines) {
        int calls = 0;
        double seconds = 0;
        long in = 0;
        long out = 0;
        int failures = 0;
        int tools = 0;
        int toolErrors = 0;
        int applied = 0;
        int refused = 0;
        int repeats = 0;
        double opening = 0;
        boolean coding = false;
        Map<String, Integer> byPhase = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher llm = LLM.matcher(line);
            if (llm.find()) {
                calls++;
                seconds += Double.parseDouble(llm.group(2));
                in += number(llm.group(3));
                out += number(llm.group(4));
                byPhase.merge(llm.group(1), 1, Integer::sum);
                continue;
            }
            Matcher tool = TOOL.matcher(line);
            if (tool.find()) {
                tools++;
                if (tool.group(2).equals("error")) toolErrors++;
                continue;
            }
            Matcher ctx = CTX.matcher(line);
            if (ctx.find()) {
                opening += Double.parseDouble(ctx.group(1));
                continue;
            }
            if (line.contains("✗ LLM")) failures++;
            else if (line.contains("✎✗")) refused++;
            else if (line.contains("✎ ")) applied++;
            else if (line.contains("↺")) repeats++;
            else if (line.contains("⇢ triage: coding work")) coding = true;
        }
        return new EvalMetrics(calls, seconds, in, out, failures, tools, toolErrors, applied, refused, repeats, opening, coding, byPhase);
    }

    private static long number(String text) {
        String digits = text.replace(",", "");
        return digits.equals("?") || digits.isEmpty() ? 0 : Long.parseLong(digits);
    }

    /** One line for the report. */
    public String summary() {
        return String.format(Locale.ROOT, "%d model calls, %.0f s in the model, %,d tokens in / %,d out, %d tool calls (%d errors), "
                        + "%d changes applied / %d refused, %d repeats, %d model failures",
                llmCalls, llmSeconds, tokensIn, tokensOut, toolCalls, toolErrors, changesApplied, changesRefused, repeats, llmFailures);
    }
}
