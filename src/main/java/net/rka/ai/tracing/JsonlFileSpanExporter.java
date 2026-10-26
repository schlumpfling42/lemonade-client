package net.rka.ai.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Writes finished spans to local files, one JSON object per line, one file per day
 * ({@code <dir>/traces-YYYY-MM-DD.jsonl}). Spans of one run share a {@code trace_id};
 * {@code parent_id} links them into a tree.
 *
 * <h2>How it works</h2>
 * The OTel SDK calls {@link #export} with a batch of finished spans (from {@code BatchSpanProcessor}'s
 * background thread). Each span becomes one JSON line and the batch is appended to today's file.
 * JSON Lines is easy to process: {@code jq}, PowerShell's {@code ConvertFrom-Json}, or a log tool can
 * read it line by line. Note that spans are written when they <em>end</em>, so a parent (which ends
 * last) appears after its children in the file.
 * <p>
 * {@code synchronized}: the SDK may call export from several threads; appending must not interleave.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Size-based rotation and retention (today one file per day, kept forever).</li>
 *   <li>Writing on a background executor instead of the exporter thread, and compressing old files.</li>
 * </ul>
 */
public final class JsonlFileSpanExporter implements SpanExporter {

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    public JsonlFileSpanExporter(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Called by the BatchSpanProcessor (on its own thread) with a batch of finished spans. */
    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        try {
            Files.createDirectories(dir);
            // Build all lines first, then append them in one write
            StringBuilder lines = new StringBuilder();
            for (SpanData span : spans) {
                lines.append(json.writeValueAsString(toJson(span))).append('\n');
            }
            Path file = dir.resolve("traces-" + LocalDate.now() + ".jsonl"); // a new file each day
            // CREATE + APPEND: make the file if needed, never overwrite earlier lines
            Files.writeString(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return CompletableResultCode.ofSuccess();
        } catch (IOException | UncheckedIOException e) {
            // Tracing must never break the app: report and drop this batch
            System.err.println("[traces] could not write to " + dir.toAbsolutePath() + ": " + e.getMessage());
            return CompletableResultCode.ofFailure();
        }
    }

    /** One span as a flat JSON object: timing, ids, name, kind, status, attributes and events. */
    private ObjectNode toJson(SpanData span) {
        ObjectNode node = json.createObjectNode();
        // Span times are nanoseconds since 1970; shown as local time with offset, e.g. 2026-10-18T09:15:00.1+02:00
        node.put("start", Instant.ofEpochSecond(0, span.getStartEpochNanos())
                .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
        node.put("duration_ms", TimeUnit.NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos()));
        node.put("trace_id", span.getTraceId());   // same for every span of one question
        node.put("span_id", span.getSpanId());     // this span
        if (span.getParentSpanContext().isValid()) node.put("parent_id", span.getParentSpanId()); // absent on the root
        node.put("name", span.getName());
        // Pull the LangSmith kind (chain / llm / tool) up to a top-level field for easy filtering
        String kind = span.getAttributes().get(AttributeKey.stringKey("langsmith.span.kind"));
        if (kind != null) node.put("kind", kind);
        node.put("status", span.getStatus().getStatusCode().name()); // UNSET, OK or ERROR
        if (!span.getStatus().getDescription().isEmpty()) node.put("error", span.getStatus().getDescription());

        // All other attributes (prompts, completions, token counts, ...) as they are
        ObjectNode attributes = node.putObject("attributes");
        span.getAttributes().forEach((key, value) -> {
            if (!key.getKey().equals("langsmith.span.kind")) attributes.putPOJO(key.getKey(), value);
        });
        // Events are timestamped notes inside a span; recordException() adds one per exception
        if (!span.getEvents().isEmpty()) {
            ArrayNode events = node.putArray("events");
            for (EventData event : span.getEvents()) {
                ObjectNode e = events.addObject();
                e.put("name", event.getName());
                event.getAttributes().forEach((key, value) -> e.putPOJO(key.getKey(), value));
            }
        }
        return node;
    }

    /** Nothing is buffered here (each export writes immediately), so there's nothing to flush. */
    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    /** No open files or threads to release. */
    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
