package net.rka.ai.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the live box shows of the model's thinking: a sliding window over its end. Thinking runs to thousands of characters and arrives
 * token by token, so the box shows the newest whole sentences that fit a budget, not the whole text and not a cut in the middle of a
 * word. The sentence still being written is always shown (it is the newest); older sentences are added from the end while they fit, and a
 * leading {@code …} says that earlier thinking has scrolled out. A single sentence longer than the budget is shown from a word boundary
 * near its end.
 */
final class ThoughtWindow {

    /** The budget of the live box: about five lines of the transcript. */
    static final int DEFAULT_CHARS = 600;

    /** A sentence ends at . ! ? or : followed by white space and a new sentence (a capital, a digit, a quote or a list mark), or at a line end. */
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?:])\\s+(?=[A-Z0-9\"'(\\[*\\-`])|\\s*\\n+\\s*");

    private ThoughtWindow() {
    }

    /** The newest whole sentences of {@code thinking} within {@code maxChars} characters ({@link #DEFAULT_CHARS}); "" for no thinking. */
    static String latest(String thinking, int maxChars) {
        String text = thinking == null ? "" : thinking.strip();
        if (text.isEmpty()) return "";
        if (text.length() <= maxChars) return text;
        List<String> sentences = sentences(text);
        StringBuilder window = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            int added = sentence.length() + (window.isEmpty() ? 0 : 1);
            if (window.length() + added > maxChars) {
                if (window.isEmpty()) return "… " + tail(sentence, maxChars - 2); // the newest sentence alone is too long
                break;
            }
            window.insert(0, window.isEmpty() ? sentence : sentence + " ");
        }
        return "… " + window;
    }

    /** {@link #latest(String, int)} with the default budget. */
    static String latest(String thinking) {
        return latest(thinking, DEFAULT_CHARS);
    }

    private static List<String> sentences(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        var matcher = BOUNDARY.matcher(text);
        while (matcher.find()) {
            String part = text.substring(start, matcher.start()).strip();
            if (!part.isEmpty()) parts.add(part);
            start = matcher.end();
        }
        String last = text.substring(start).strip();
        if (!last.isEmpty()) parts.add(last);
        return parts;
    }

    /** The end of a sentence that does not fit, from a word boundary. */
    private static String tail(String sentence, int maxChars) {
        String end = sentence.substring(sentence.length() - maxChars);
        int space = end.indexOf(' ');
        return space >= 0 && space < end.length() - 1 ? end.substring(space + 1) : end;
    }
}
