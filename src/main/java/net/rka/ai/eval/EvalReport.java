package net.rka.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The summary of a run (JSON, for comparing) and its report (Markdown, for reading), with the comparison against an earlier run. */
public final class EvalReport {

    /** @param run the name of the run folder; @param model the model name the agent used */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(String run, String startedAt, String model, List<CaseResult> cases) {

        public long passed() {
            return cases.stream().filter(CaseResult::passed).count();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private EvalReport() {
    }

    public static void write(Summary summary, Summary baseline, Path runFolder) throws IOException {
        Files.createDirectories(runFolder);
        JSON.writeValue(runFolder.resolve("summary.json").toFile(), summary);
        Files.writeString(runFolder.resolve("report.md"), markdown(summary, baseline));
    }

    public static Summary read(Path summaryFile) throws IOException {
        return JSON.readValue(summaryFile.toFile(), Summary.class);
    }

    public static String markdown(Summary now, Summary before) {
        StringBuilder out = new StringBuilder("# Evaluation ").append(now.run()).append("\n\n");
        out.append("Model: ").append(now.model()).append(" · ").append(now.passed()).append(" of ").append(now.cases().size())
                .append(" cases passed");
        if (before != null) out.append(" (").append(before.run()).append(": ").append(before.passed()).append(" of ").append(before.cases().size()).append(')');
        out.append("\n\n| case | result | time | model calls | tokens in | applied/refused | checks |\n|---|---|---|---|---|---|---|\n");
        Map<String, CaseResult> earlier = new HashMap<>();
        if (before != null) before.cases().forEach(c -> earlier.put(c.id(), c));
        for (CaseResult c : now.cases()) {
            CaseResult b = earlier.get(c.id());
            EvalMetrics m = c.metrics();
            out.append("| ").append(c.id()).append(" | ").append(c.passed() ? "pass" : (c.timedOut() ? "TIMEOUT" : "FAIL"))
                    .append(b == null ? "" : change(b.passed(), c.passed()))
                    .append(" | ").append(seconds(c.seconds())).append(delta(b == null ? null : b.seconds(), c.seconds(), true))
                    .append(" | ").append(m.llmCalls()).append(delta(b == null ? null : (double) b.metrics().llmCalls(), m.llmCalls(), false))
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", m.tokensIn()))
                    .append(delta(b == null ? null : (double) b.metrics().tokensIn(), m.tokensIn(), false))
                    .append(" | ").append(m.changesApplied()).append('/').append(m.changesRefused())
                    .append(" | ").append(c.checksPassed()).append('/').append(c.checks().size()).append(" |\n");
        }
        for (CaseResult c : now.cases()) {
            if (c.passed()) continue;
            out.append("\n## ").append(c.id()).append(" failed\n\n");
            if (!c.error().isEmpty()) out.append("- error: ").append(c.error()).append('\n');
            c.checks().stream().filter(r -> !r.ok()).forEach(r -> out.append("- ").append(r.check().describe()).append(": ").append(r.detail()).append('\n'));
            out.append("- ").append(c.metrics().summary()).append('\n');
            if (!c.answer().isBlank()) out.append("\nAnswer:\n\n> ").append(c.answer().strip().replace("\n", "\n> ")).append('\n');
        }
        return out.toString();
    }

    private static String change(boolean was, boolean is) {
        return was == is ? "" : (is ? " (fixed)" : " (REGRESSION)");
    }

    private static String seconds(double s) {
        return s >= 90 ? String.format(Locale.ROOT, "%.1f min", s / 60) : String.format(Locale.ROOT, "%.0f s", s);
    }

    private static String delta(Double was, double is, boolean time) {
        if (was == null || was == 0) return "";
        double change = (is - was) / was * 100;
        if (Math.abs(change) < 5) return "";
        return String.format(Locale.ROOT, " (%+.0f%%)", change);
    }
}
