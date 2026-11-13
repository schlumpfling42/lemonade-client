package net.rka.ai.tools.code.java;

/** The modifiers other than visibility. Which ones are allowed depends on what they are written on. */
public enum Modifier {
    STATIC("static"),
    FINAL("final"),
    ABSTRACT("abstract"),
    SYNCHRONIZED("synchronized"),
    TRANSIENT("transient"),
    VOLATILE("volatile"),
    NATIVE("native"),
    STRICTFP("strictfp"),
    /** An interface method with a body. */
    DEFAULT("default"),
    SEALED("sealed"),
    NON_SEALED("non-sealed");

    private final String keyword;

    Modifier(String keyword) {
        this.keyword = keyword;
    }

    /** The word written in source. */
    public String keyword() {
        return keyword;
    }
}
