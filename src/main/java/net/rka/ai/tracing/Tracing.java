package net.rka.ai.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import net.rka.ai.config.Env;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry tracing with up to two destinations:
 * <ul>
 *   <li>LangSmith, when {@code LANGSMITH_API_KEY} is set</li>
 *   <li>local JSONL files in {@code TRACE_DIR} (default {@code traces}), unless {@code LOCAL_TRACES=false}</li>
 * </ul>
 * With neither, the tracer is a no-op.
 *
 * <h2>How it works</h2>
 * OpenTelemetry (OTel) is a vendor-neutral tracing standard. The pieces:
 * <ul>
 *   <li>a <b>span</b> is one timed step (a graph node, a model call, a tool call) with attributes;
 *       spans form a tree per question (a <b>trace</b>);</li>
 *   <li>the <b>tracer</b> creates spans; the <b>tracer provider</b> owns span processors;</li>
 *   <li>a <b>BatchSpanProcessor</b> collects finished spans in memory and hands them to an
 *       <b>exporter</b> in batches, on a background thread, so tracing never slows the agent down.</li>
 * </ul>
 * LangSmith accepts OTel traces over OTLP/HTTP at {@code /otel/v1/traces}; it maps attributes like
 * {@code langsmith.span.kind} and {@code gen_ai.*} onto its own run types and fields. The local exporter
 * ({@link JsonlFileSpanExporter}) writes the same spans as JSON lines, which is handy without an account.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Other OTel backends (Jaeger, Grafana Tempo, Langfuse, Phoenix) — just another OTLP endpoint.</li>
 *   <li>Sampling or redaction: today full prompts, file contents and command output are recorded,
 *       which can include secrets. A span processor could mask them.</li>
 *   <li>A trace viewer inside the app (read the JSONL files and show the tree for a conversation).</li>
 *   <li>Deleting old local trace files after N days.</li>
 * </ul>
 */
public final class Tracing implements AutoCloseable {

    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final String description;

    private Tracing(SdkTracerProvider tracerProvider, Tracer tracer, String description) {
        this.tracerProvider = tracerProvider;
        this.tracer = tracer;
        this.description = description;
    }

    public static Tracing fromEnv() {
        // The "resource" describes who produces the spans; service.name is what backends group by
        // A model call records two attributes per message of its prompt: the default limit of 128 cut the later calls of a long job
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setSpanLimits(io.opentelemetry.sdk.trace.SpanLimits.builder().setMaxNumberOfAttributes(4096).build())
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "lemonade-client"))));
        List<String> destinations = new ArrayList<>();

        String apiKey = Env.get("LANGSMITH_API_KEY", null);
        if (apiKey != null) {
            String endpoint = Env.get("LANGSMITH_ENDPOINT", "https://api.smith.langchain.com");
            String project = Env.get("LANGSMITH_PROJECT", "lemonade-client");
            // Each processor is an independent destination; both may be active at once
            builder.addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint + "/otel/v1/traces")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("Langsmith-Project", project)
                    .build()).build());
            destinations.add("LangSmith project '" + project + "'");
        }

        if (!"false".equalsIgnoreCase(Env.get("LOCAL_TRACES", "true"))) {
            Path dir = Path.of(Env.get("TRACE_DIR", "traces"));
            builder.addSpanProcessor(BatchSpanProcessor.builder(new JsonlFileSpanExporter(dir))
                    .setScheduleDelay(1, TimeUnit.SECONDS) // write within a second, so files are current while debugging
                    .build());
            destinations.add("files in " + dir.toAbsolutePath().normalize());
        }

        if (destinations.isEmpty()) {
            System.err.println("[tracing] off (no LANGSMITH_API_KEY and LOCAL_TRACES=false)");
            return off();
        }
        String description = String.join(" + ", destinations);
        System.err.println("[tracing] " + description);
        SdkTracerProvider provider = builder.build();
        return new Tracing(provider, provider.get("net.rka.ai"), description);
    }

    /** No destinations: spans are created but go nowhere (also used by tests that run an {@code Agent}). */
    public static Tracing off() {
        return new Tracing(null, OpenTelemetrySdk.builder().build().getTracer("noop"), "off");
    }

    public boolean enabled() {
        return tracerProvider != null;
    }

    /** Where traces go, e.g. "LangSmith project 'x' + files in /path/traces", or "off". */
    public String description() {
        return description;
    }

    public Tracer tracer() {
        return tracer;
    }

    @Override
    public void close() {
        if (tracerProvider != null) {
            // Batched spans would be lost on exit; wait up to 10 s for them to be sent/written
            tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
            tracerProvider.shutdown();
        }
    }
}
