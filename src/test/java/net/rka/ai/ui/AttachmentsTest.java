package net.rka.ai.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentsTest {

    @TempDir
    Path dir;

    @Test
    void composedMessagesSplitBackIntoTypedTextAndFiles() {
        List<Attachments.Attachment> files = List.of(
                new Attachments.Attachment("notes.md", "# Notes\n- one\n- two"),
                new Attachments.Attachment("data.csv", "a,b\n1,2\n"));
        String message = Attachments.compose("summarize these", files);

        assertTrue(message.startsWith("summarize these\n\n--- Attached file: notes.md ---\n# Notes"));
        Attachments.Parts parts = Attachments.split(message);
        assertEquals("summarize these", parts.typed());
        assertEquals(List.of(new Attachments.Attachment("notes.md", "# Notes\n- one\n- two"),
                new Attachments.Attachment("data.csv", "a,b\n1,2")), parts.files());
    }

    @Test
    void plainMessagesHaveNoFiles() {
        Attachments.Parts parts = Attachments.split("just a question");
        assertEquals("just a question", parts.typed());
        assertTrue(parts.files().isEmpty());
    }

    @Test
    void readsTextAndRefusesBinary() throws IOException {
        Path text = Files.writeString(dir.resolve("hello.txt"), "héllo wörld");
        assertEquals(new Attachments.Attachment("hello.txt", "héllo wörld"), Attachments.read(text));

        Path binary = Files.write(dir.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        assertThrows(IOException.class, () -> Attachments.read(binary));
        Path latin1 = Files.write(dir.resolve("old.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9});
        assertThrows(IOException.class, () -> Attachments.read(latin1)); // not valid UTF-8
    }

    @Test
    void longFilesAreCut() throws IOException {
        Path big = Files.writeString(dir.resolve("big.txt"), "x".repeat(Attachments.MAX_CHARS + 10));
        String text = Attachments.read(big).text();
        assertTrue(text.startsWith("x".repeat(Attachments.MAX_CHARS)));
        assertTrue(text.contains("truncated"));
    }
}
