package net.rka.ai.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.SimpleViewOnlyStyledModel;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns Markdown (CommonMark plus GitHub tables) into a read-only, selectable rich-text view for
 * the chat transcript: paragraphs with bold, italic, inline code and links; headings; nested bullet
 * and numbered lists; block quotes; code blocks with a Copy button; rules; and tables.
 * Plain bubbles (your questions) use the same view via {@link #plain}.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>commonmark-java parses the text into a tree: {@code Document} → blocks ({@code Paragraph},
 *       {@code BulletList}, …) → inlines ({@code Text}, {@code StrongEmphasis}, {@code Link}, …).</li>
 *   <li>The tree is written into a {@link SimpleViewOnlyStyledModel}, JavaFX's read-only rich-text
 *       document: {@code addSegment(text, style)} appends styled text to the current paragraph,
 *       {@code nl()} starts the next one, {@code setParagraphAttributes} sets bullets and indents, and
 *       {@code addParagraph(supplier)} embeds a node (code blocks, tables, rules) as its own paragraph.</li>
 *   <li>A {@link RichTextArea} (the JavaFX incubator rich-text control) shows the model. It provides
 *       mouse selection and Ctrl+C itself; {@code useContentHeight} makes it as tall as its text, so the
 *       transcript, not the bubble, scrolls.</li>
 *   <li>Links are styled segments; their positions are recorded while writing, and a click is mapped
 *       back to a link with {@link RichTextArea#getTextPosition}.</li>
 * </ol>
 * Nothing is rendered as HTML, so a model can't inject scripts or styles; raw HTML just shows as text.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Selecting across bubbles, and inside code blocks and tables (they're embedded nodes with
 *       their own Copy button instead).</li>
 *   <li>Syntax highlighting in code blocks, task lists, strikethrough, footnotes.</li>
 *   <li>Showing images inline instead of as links; rendering while streaming.</li>
 * </ul>
 */
final class MarkdownView {

    // Parsers are thread-safe and reusable; the tables extension adds GitHub-style | a | b | tables
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    // Text styles (rich-text segments are styled with attributes, not CSS); colors follow chat.css roles
    private static final double FONT_SIZE = 15;
    private static final Color TEXT = Color.web("#1f1f1f");
    private static final Color MUTED = Color.web("#444746");
    private static final Color LINK = Color.web("#0b57d0");
    private static final Color CODE_TEXT = Color.web("#001d35");
    private static final Color CODE_BACKGROUND = Color.web("#e9eef6");
    private static final double INDENT = 22; // px per list or quote level

    /** A link's place in the document: paragraph index and character range, for click handling. */
    private record LinkRange(int paragraph, int start, int end, String url) {
    }

    private final SimpleViewOnlyStyledModel model = new SimpleViewOnlyStyledModel();
    private final List<LinkRange> links = new ArrayList<>();
    private boolean pendingBreak; // the current text paragraph is finished; the next segment starts a new one

    private MarkdownView() {
    }

    /**
     * An assistant answer: Markdown rendered as selectable rich text.
     *
     * @param openLink called with a link's URL when the user clicks it
     */
    static RichTextArea render(String markdown, Consumer<String> openLink) {
        MarkdownView view = new MarkdownView();
        view.blocks(PARSER.parse(markdown), 0, base(TEXT));
        return view.area(markdown, openLink);
    }

    /** A plain message (your question, an error) as selectable text in the given color. */
    static RichTextArea plain(String text, Color color) {
        MarkdownView view = new MarkdownView();
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            view.segment(line, base(color));
            view.endParagraph(null);
        }
        return view.area(text, url -> { });
    }

    /** The read-only control around the finished model, with copy and link handling. */
    private RichTextArea area(String original, Consumer<String> openLink) {
        RichTextArea area = new RichTextArea(model);
        area.setEditable(false);
        area.setWrapText(true);           // wrap at the bubble's width
        area.setUseContentHeight(true);   // as tall as the text: the transcript scrolls, not the bubble
        area.setDisplayCaret(false);
        area.setHighlightCurrentParagraph(false);
        area.getStyleClass().add("message-text");

        // Ctrl+C: copy the selection, minus the invisible break characters added for wrapping
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
                copySelection(area);
                e.consume();
            }
        });
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copySelection(area));
        MenuItem copyAll = new MenuItem("Copy whole message");
        copyAll.setOnAction(e -> putOnClipboard(original)); // the original text (Markdown source for answers)
        area.setContextMenu(new ContextMenu(copy, copyAll));

        // Links: a pointer over them, and a click (not a drag-select) opens them. The cursor is set on
        // the hovered text node itself (the control's inner text cursor would win over one set on the control)
        area.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (e.getTarget() instanceof Node hovered) {
                hovered.setCursor(linkAt(area, e) != null ? Cursor.HAND : null); // null = inherit the text cursor
            }
        });
        area.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1 || hasSelection(area)) return;
            LinkRange link = linkAt(area, e);
            if (link != null) openLink.accept(link.url());
        });
        return area;
    }

    private LinkRange linkAt(RichTextArea area, MouseEvent e) {
        if (links.isEmpty()) return null;
        TextPos pos = area.getTextPosition(e.getScreenX(), e.getScreenY());
        if (pos == null) return null;
        for (LinkRange link : links) {
            if (link.paragraph() == pos.index() && pos.offset() >= link.start() && pos.offset() < link.end()) return link;
        }
        return null;
    }

    private static boolean hasSelection(RichTextArea area) {
        SelectionSegment selection = area.getSelection();
        return selection != null && !selection.isCollapsed();
    }

    /** Copies the selected text as plain text, without the zero-width break characters. */
    private static void copySelection(RichTextArea area) {
        if (!hasSelection(area)) return;
        area.copy(javafx.scene.input.DataFormat.PLAIN_TEXT);
        String copied = Clipboard.getSystemClipboard().getString();
        if (copied != null) putOnClipboard(copied.replace("​", ""));
    }

    private static void putOnClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // --- Writing the document ---

    private static StyleAttributeMap base(Color color) {
        return StyleAttributeMap.builder().setFontFamily("System").setFontSize(FONT_SIZE).setTextColor(color).build();
    }

    private static StyleAttributeMap with(StyleAttributeMap style, StyleAttributeMap extra) {
        return StyleAttributeMap.builder().merge(style).merge(extra).build();
    }

    /** Appends styled text to the current paragraph (starting a new one if the last was finished). */
    private void segment(String text, StyleAttributeMap style) {
        if (pendingBreak) {
            model.nl();
            pendingBreak = false;
        }
        model.addSegment(breakable(text), style);
    }

    /** Finishes the current text paragraph, with optional paragraph attributes (bullet, indent, spacing). */
    private void endParagraph(StyleAttributeMap attributes) {
        if (model.size() == 0) model.addSegment("");
        model.setParagraphAttributes(attributes != null ? attributes
                : StyleAttributeMap.builder().setSpaceBelow(6).build());
        pendingBreak = true;
    }

    /** Embeds a node as its own paragraph (it always starts a new paragraph). */
    private void embed(java.util.function.Supplier<Region> node) {
        model.addParagraph(node);
        pendingBreak = false; // the next text segment starts a new paragraph by itself
    }

    private void blocks(org.commonmark.node.Node parent, int level, StyleAttributeMap style) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            block(child, level, style, null);
        }
    }

    /**
     * One block. {@code marker} is set for the first block of a list item: "•" (bullet) or "3." (number).
     */
    private void block(org.commonmark.node.Node node, int level, StyleAttributeMap style, String marker) {
        if (node instanceof Paragraph) {
            // The list marker ("•" or "3.") as the first characters of the item's first paragraph
            if (marker != null) segment(marker + " ", with(style, bold()));
            inlines(node, style, false, false);
            endParagraph(paragraph(level));
        } else if (node instanceof Heading heading) {
            double size = heading.getLevel() == 1 ? 22 : heading.getLevel() == 2 ? 19 : 16;
            inlines(node, with(style, StyleAttributeMap.builder().setBold(true).setFontSize(size).build()), true, false);
            endParagraph(StyleAttributeMap.builder().setSpaceAbove(6).setSpaceBelow(4).build());
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            Integer start = node instanceof OrderedList list ? list.getMarkerStartNumber() : null;
            int number = start == null ? 1 : start;
            for (org.commonmark.node.Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                if (!(item instanceof ListItem)) continue;
                String itemMarker = node instanceof OrderedList ? (number++) + "." : "•";
                boolean first = true;
                for (org.commonmark.node.Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                    block(child, level + 1, style, first ? itemMarker : null); // the marker only on the first block
                    first = false;
                }
            }
        } else if (node instanceof FencedCodeBlock code) {
            String literal = code.getLiteral();
            String info = code.getInfo();
            embed(() -> codeBlock(literal, info));
        } else if (node instanceof IndentedCodeBlock code) {
            String literal = code.getLiteral();
            embed(() -> codeBlock(literal, ""));
        } else if (node instanceof BlockQuote) {
            blocks(node, level + 1, with(style, StyleAttributeMap.builder().setTextColor(MUTED).setItalic(true).build()));
        } else if (node instanceof ThematicBreak) {
            embed(Separator::new);
        } else if (node instanceof HtmlBlock html) {
            segment(html.getLiteral().strip(), style); // never interpret HTML from the model
            endParagraph(paragraph(level));
        } else if (node instanceof TableBlock) {
            embed(() -> table(node));
        } else if (node.getFirstChild() != null) {
            blocks(node, level, style);
        }
    }

    /** Paragraph attributes for a list/quote level: indent by level, tighter spacing inside lists. */
    private static StyleAttributeMap paragraph(int level) {
        StyleAttributeMap.Builder b = StyleAttributeMap.builder().setSpaceBelow(level > 0 ? 3 : 6);
        if (level > 0) b.setSpaceLeft(level * INDENT);
        return b.build();
    }

    private static StyleAttributeMap bold() {
        return StyleAttributeMap.builder().setBold(true).build();
    }

    /** Writes inline content (text, emphasis, code, links) as segments of the current paragraph. */
    private void inlines(org.commonmark.node.Node parent, StyleAttributeMap style, boolean bold, boolean italic) {
        for (org.commonmark.node.Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            // The surrounding style (heading size, quote color, …) plus the emphasis we're inside
            StyleAttributeMap current = with(style, StyleAttributeMap.builder()
                    .setBold(bold || style.isBold()).setItalic(italic || style.isItalic()).build());
            if (node instanceof org.commonmark.node.Text t) {
                textWithUrls(t.getLiteral(), current);
            } else if (node instanceof StrongEmphasis) {
                inlines(node, style, true, italic);                    // **...**
            } else if (node instanceof Emphasis) {
                inlines(node, style, bold, true);                      // *...*
            } else if (node instanceof Code code) {
                segment(code.getLiteral(), with(current, StyleAttributeMap.builder()
                        .setFontFamily("Monospaced").setFontSize(13).setTextColor(CODE_TEXT)
                        .setBackground(CODE_BACKGROUND).build()));     // `code`
            } else if (node instanceof Link link) {
                link(link.getDestination(), plainText(link), current); // [label](url)
            } else if (node instanceof Image image) {
                link(image.getDestination(), "🖼 " + plainText(image), current);
            } else if (node instanceof SoftLineBreak) {
                segment(" ", current);                                 // a single newline is just a space
            } else if (node instanceof HardLineBreak) {
                endParagraph(null);                                    // a real line break
            } else if (node instanceof HtmlInline html) {
                segment(html.getLiteral(), current);                   // shown as text, never rendered
            } else {
                inlines(node, style, bold, italic);
            }
        }
    }

    /** A bare http(s) URL in plain text; trailing punctuation is trimmed off in {@link #textWithUrls}. */
    private static final java.util.regex.Pattern BARE_URL = java.util.regex.Pattern.compile("https?://[^\\s<>\"]+");

    /** Plain text, with bare URLs ("see https://example.com/x.") turned into links. */
    private void textWithUrls(String literal, StyleAttributeMap style) {
        java.util.regex.Matcher m = BARE_URL.matcher(literal);
        int last = 0;
        while (m.find()) {
            String url = m.group();
            int end = url.length();
            while (end > 0 && ".,;:!?)]}'".indexOf(url.charAt(end - 1)) >= 0) end--; // sentence punctuation
            url = url.substring(0, end);
            if (m.start() > last) segment(literal.substring(last, m.start()), style);
            link(url, url, style);
            last = m.start() + end;
        }
        if (last < literal.length()) segment(literal.substring(last), style);
    }

    /** A link segment (blue, underlined) whose range is recorded for click handling. */
    private void link(String url, String label, StyleAttributeMap style) {
        String text = label.isBlank() ? url : label;
        segment(text, with(style, StyleAttributeMap.builder().setTextColor(LINK).setUnderline(true).build()));
        // Its range: the end of the current paragraph minus what we just added (incl. break characters)
        int paragraph = model.size() - 1;
        int end = model.getPlainText(paragraph).length();
        links.add(new LinkRange(paragraph, end - breakable(text).length(), end, url));
    }

    // --- Embedded blocks ---

    /** A dark code panel: language name and Copy button on top, the code below. */
    private static Region codeBlock(String code, String language) {
        String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        Label lang = new Label(language == null || language.isBlank() ? "code" : language.strip().split("\\s+")[0]);
        lang.getStyleClass().add("md-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.getStyleClass().add("md-code-copy");
        copy.setOnAction(e -> {
            putOnClipboard(body);
            copy.setText("Copied");
        });
        HBox header = new HBox(8, lang, spacer, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("md-code-header");

        Label text = new Label(body);
        text.getStyleClass().add("md-code");
        text.setMinHeight(Region.USE_PREF_SIZE);
        ScrollPane scroll = new ScrollPane(text); // long lines scroll sideways, so code keeps its shape
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("md-code-scroll");

        VBox box = new VBox(header, scroll);
        box.getStyleClass().addAll("markdown", "md-code-block");
        return box;
    }

    /** A table as a GridPane of labels, header row first. */
    private static Region table(org.commonmark.node.Node table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().addAll("markdown", "md-table");
        int row = 0;
        int columns = 0;
        for (org.commonmark.node.Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (org.commonmark.node.Node tr = section.getFirstChild(); tr != null; tr = tr.getNext()) {
                if (!(tr instanceof TableRow)) continue;
                int column = 0;
                for (org.commonmark.node.Node cell = tr.getFirstChild(); cell != null; cell = cell.getNext()) {
                    boolean header = cell instanceof TableCell tc && tc.isHeader();
                    Label label = new Label(plainText(cell));
                    label.setWrapText(true);
                    label.getStyleClass().addAll(header ? "md-th" : "md-td", row % 2 == 0 ? "md-row-even" : "md-row-odd");
                    label.setMaxWidth(Double.MAX_VALUE);
                    label.setMaxHeight(Double.MAX_VALUE);
                    grid.add(label, column++, row);
                }
                columns = Math.max(columns, column);
                row++;
            }
        }
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(constraints);
        }
        HBox wrapper = new HBox(grid); // keep the table at its natural width, left-aligned
        return wrapper;
    }

    // --- Helpers ---

    /** Words this long or longer (URLs, paths) get invisible break points. */
    private static final java.util.regex.Pattern LONG_WORD = java.util.regex.Pattern.compile("\\S{25,}");

    /**
     * Lets long words wrap: text only breaks between words, so a long URL would stick out of the
     * bubble. A zero-width space (U+200B) after {@code / - _ . ? & = \ :} inside long words is an
     * invisible line-break opportunity. Copying removes them again.
     */
    static String breakable(String value) {
        return LONG_WORD.matcher(value).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(m.group().replaceAll("([/\\-_.?&=\\\\:])", "$1​")));
    }

    private static String plainText(org.commonmark.node.Node node) {
        StringBuilder sb = new StringBuilder();
        for (org.commonmark.node.Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text t) sb.append(t.getLiteral());
            else if (child instanceof Code c) sb.append(c.getLiteral());
            else sb.append(plainText(child));
        }
        return sb.toString();
    }
}
