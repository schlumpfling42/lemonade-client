/** A renderer turns a text into another text. */
public interface Renderer {

    /** The name the renderer is found by. */
    String name();

    String render(String text);
}
