package net.rka.ai.tools.code.java;

/** What a Java type declaration is. */
public enum JavaKind {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    RECORD("record"),
    ANNOTATION("@interface");

    private final String keyword;

    JavaKind(String keyword) {
        this.keyword = keyword;
    }

    /** The word written in source before the type's name. */
    public String keyword() {
        return keyword;
    }
}
