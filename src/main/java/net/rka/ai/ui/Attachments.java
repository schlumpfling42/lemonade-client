package net.rka.ai.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Files attached to a message. Their text is included in the user message itself, between
 * marker lines, so the model sees it, it's saved with the conversation, and summary memory can
 * compress it like any other text. The chat window shows only what you typed plus a chip per file.
 *
 * <h2>How it works</h2>
 * <pre>
 *   what you typed
 *
 *   --- Attached file: notes.md ---
 *   (file text)
 *   --- End of notes.md ---
 * </pre>
 * {@link #compose} builds that text, {@link #split} takes it apart again for display. Only UTF-8 text
 * files are accepted (the model reads text); anything that isn't valid UTF-8 or contains NUL bytes is
 * refused. Long files are cut at {@link #MAX_CHARS}.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Images for vision models (LangChain4j's ImageContent), and PDF/Word text extraction.</li>
 *   <li>Attachments that stay with the conversation as reference files instead of one message.</li>
 * </ul>
 */
final class Attachments {

    static final int MAX_CHARS = 100_000; // per file: about 25k tokens
    private static final Pattern BLOCK = Pattern.compile(
            "\n\n--- Attached file: (.+?) ---\n(.*?)\n--- End of \\1 ---", Pattern.DOTALL);

    /** One attached file: its name and text. */
    record Attachment(String name, String text) {
    }

    /** What a stored user message consists of: the typed text and the attached files. */
    record Parts(String typed, List<Attachment> files) {
    }

    private Attachments() {
    }

    /** Reads a text file for attaching; throws with a readable reason for binary or unreadable files. */
    static Attachment read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) throw new IOException(name + " is not a text file");
        }
        String text;
        try {
            // A strict decoder: invalid UTF-8 is an error instead of replacement characters
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(name + " is not a UTF-8 text file");
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n… truncated, the file has " + text.length() + " characters";
        }
        return new Attachment(name, text);
    }

    /** The message text the model receives: what you typed, then each file between marker lines. */
    static String compose(String typed, List<Attachment> files) {
        StringBuilder out = new StringBuilder(typed);
        for (Attachment file : files) {
            out.append("\n\n--- Attached file: ").append(file.name()).append(" ---\n")
                    .append(file.text().stripTrailing())
                    .append("\n--- End of ").append(file.name()).append(" ---");
        }
        return out.toString();
    }

    /** The inverse of {@link #compose}: typed text and files, for showing a stored message. */
    static Parts split(String message) {
        Matcher m = BLOCK.matcher(message);
        List<Attachment> files = new ArrayList<>();
        int typedEnd = -1;
        while (m.find()) {
            if (typedEnd < 0) typedEnd = m.start();
            files.add(new Attachment(m.group(1), m.group(2)));
        }
        return new Parts(typedEnd < 0 ? message : message.substring(0, typedEnd), files);
    }
}
