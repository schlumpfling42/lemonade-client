import java.util.List;

/** The renderers the program knows; a renderer that is not in the list cannot be used. */
public class Registry {

    private static final List<Renderer> RENDERERS = List.of(new UpperRenderer());

    public static String render(String name, String text) {
        for (Renderer renderer : RENDERERS) {
            if (renderer.name().equals(name)) return renderer.render(text);
        }
        return "unknown renderer: " + name;
    }
}
