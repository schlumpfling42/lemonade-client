/** Text with characters that look like code. */
public class Text {

    /** A banner with awkward characters. */
    public static String banner() {
        String open = "{ // not a comment }";
        char brace = '}';
        String block = """
                /* still text */
                Welcome to {the} // show
                """;
        return open + brace + block.strip();
    }

    /** The length of the banner. */
    public static int length() {
        return banner().length();
    }
}
