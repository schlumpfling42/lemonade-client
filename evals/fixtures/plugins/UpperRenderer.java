/** Renders a text in capital letters. */
public class UpperRenderer implements Renderer {

    @Override
    public String name() {
        return "upper";
    }

    @Override
    public String render(String text) {
        return text.toUpperCase();
    }
}
