package net.rka.ai.tools.outline;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Makes the {@link Outline} of a file: its md5 and its sections, by file type (Java, Markdown, anything
 * else as plain blocks).
 * <p>
 * Why an outline instead of reading the file: a local model is slow at reading a big file (every character is
 * prefilled again when the prompt changes) and unreliable at quoting old text exactly to replace it. With
 * an outline it names a part ({@code Foo.run(String)}), reads just that part and replaces it as a whole.
 * <p>
 * Nothing is cached: an outline is made in one pass over the text (milliseconds for a file of a few
 * thousand lines), always matches the file as it is now, and is the same for the same file every time
 * (no clock, no randomness, no order that depends on a hash map). The md5, taken over the file's bytes,
 * says which version an outline belongs to.
 */
public final class Outlines {

    private static final Outliner JAVA = new JavaOutliner();
    private static final Outliner MARKDOWN = new MarkdownOutliner();
    private static final Outliner BLOCKS = new BlockOutliner();

    private Outlines() {
    }

    /**
     * @param fileName the file's name, for its type (the extension)
     * @param bytes    the file's content
     * @throws IllegalArgumentException when the content isn't UTF-8 text
     */
    public static Outline of(String fileName, byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(fileName + " is not a UTF-8 text file");
        }
        List<String> lines = text.lines().toList();
        Outliner outliner = outlinerFor(fileName);
        return new Outline(md5(bytes), lines.size(), outliner.structured(), outliner.sections(text, lines));
    }

    /** Like {@link #of(String, byte[])} for a file already read as text (UTF-8, as {@code readFile} reads it). */
    public static Outline of(String fileName, String text) {
        return of(fileName, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Outliner outlinerFor(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return JAVA;
        if (name.endsWith(".md") || name.endsWith(".markdown")) return MARKDOWN;
        return BLOCKS;
    }

    /** The md5 of the bytes as 32 lowercase hex digits. */
    public static String md5(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java runtime has MD5
        }
    }
}
