package net.rka.ai.agent;

import net.rka.ai.config.Env;

import java.util.HashMap;
import java.util.Map;

/**
 * How the model picks its words: {@code MODEL_TEMPERATURE} (default 0.6), {@code MODEL_TOP_P} (0.95) and {@code MODEL_TOP_K} (20), the usual
 * values of Qwen's thinking mode. A low temperature makes a thinking model repeat itself and get stuck. {@code top_k} is not part of the
 * OpenAI request, so it travels as an extra field of the request body, which every request has to carry (a request's own extra fields, as
 * the reasoning effort, replace the model's), see {@link #with}.
 */
final class Sampling {

    private Sampling() {
    }

    static double temperature() {
        return Double.parseDouble(Env.get("MODEL_TEMPERATURE", "0.6"));
    }

    static double topP() {
        return Double.parseDouble(Env.get("MODEL_TOP_P", "0.95"));
    }

    /** 0 or less: the server's own. */
    static int topK() {
        return Integer.parseInt(Env.get("MODEL_TOP_K", "20"));
    }

    /** The extra fields of a request with {@code top_k} added (when it is set). */
    static Map<String, Object> with(Map<String, Object> extra) {
        Map<String, Object> all = new HashMap<>(extra);
        if (topK() > 0) all.put("top_k", topK());
        return all;
    }
}
