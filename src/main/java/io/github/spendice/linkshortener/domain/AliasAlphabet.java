package io.github.spendice.linkshortener.domain;

/**
 * Alfabeto público de los alias: 58 símbolos sin los ambiguos
 * {@code 0}, {@code O}, {@code I} ni {@code l}, distinguiendo mayúsculas
 * de minúsculas.
 */
public final class AliasAlphabet {

    public static final String SYMBOLS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    public static final int SIZE = SYMBOLS.length();

    private AliasAlphabet() {
    }

    public static boolean isCode(String value) {
        return value != null
                && !value.isEmpty()
                && value.chars().allMatch(symbol -> SYMBOLS.indexOf(symbol) >= 0);
    }
}
