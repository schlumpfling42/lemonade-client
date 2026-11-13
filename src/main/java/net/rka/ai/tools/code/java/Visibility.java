package net.rka.ai.tools.code.java;

/** Who can see a class, field, method or constructor. */
public enum Visibility {
    PUBLIC("public"),
    PROTECTED("protected"),
    /** No keyword: visible inside the package. */
    PACKAGE(""),
    PRIVATE("private");

    private final String keyword;

    Visibility(String keyword) {
        this.keyword = keyword;
    }

    /** The word written in source, or "" for package visibility. */
    public String keyword() {
        return keyword;
    }
}
