package net.rka.ai.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The text-only helpers of MarkdownView (rendering itself needs the JavaFX toolkit). */
class MarkdownViewTest {

    @Test
    void longWordsGetInvisibleBreakPoints() {
        String url = "https://www.motorcycle.com/bikes/new-model";
        String shown = MarkdownView.breakable("see " + url);
        assertEquals("see " + url, shown.replace("​", "")); // only zero-width spaces were added
        assertEquals("https:​/​/​www.​motorcycle.​com/​bikes/​new-​model",
                shown.substring(4));
    }

    @Test
    void shortWordsStayUnchanged() {
        assertEquals("a short sentence, with a.b and c/d", MarkdownView.breakable("a short sentence, with a.b and c/d"));
    }
}
