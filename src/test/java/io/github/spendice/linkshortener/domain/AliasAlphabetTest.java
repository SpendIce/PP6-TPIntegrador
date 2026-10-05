package io.github.spendice.linkshortener.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliasAlphabetTest {

    @Test
    void tiene58SimbolosSinAmbiguos() {
        assertThat(AliasAlphabet.SYMBOLS)
                .hasSize(58)
                .doesNotContain("0", "O", "I", "l");
    }

    @Test
    void distingueMayusculasDeMinusculas() {
        assertThat(AliasAlphabet.SYMBOLS).contains("A", "a", "Z", "z");
    }

    @Test
    void reconoceCodigosDelAlfabeto() {
        assertThat(AliasAlphabet.isCode("1")).isTrue();
        assertThat(AliasAlphabet.isCode("zY9")).isTrue();
        assertThat(AliasAlphabet.isCode("0")).isFalse();
        assertThat(AliasAlphabet.isCode("lO5")).isFalse();
        assertThat(AliasAlphabet.isCode("")).isFalse();
        assertThat(AliasAlphabet.isCode(null)).isFalse();
        assertThat(AliasAlphabet.isCode("a.b")).isFalse();
    }
}
