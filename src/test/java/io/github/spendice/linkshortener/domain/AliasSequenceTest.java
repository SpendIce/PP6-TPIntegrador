package io.github.spendice.linkshortener.domain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AliasSequenceTest {

    private final AliasSequence sequence = new AliasSequence(Set.of());

    @Test
    void agotaLos58CodigosDeUnCaracterAntesDePasaraDos() {
        Set<String> emitted = new HashSet<>();
        for (long i = 0; i < 58; i++) {
            String code = sequence.codeAt(i);
            assertThat(code).hasSize(1);
            emitted.add(code);
        }
        assertThat(emitted).hasSize(58);
        assertThat(sequence.codeAt(58)).hasSize(2);
    }

    @Test
    void recorreElAlfabetoEnOrden() {
        assertThat(sequence.codeAt(0)).isEqualTo("1");
        assertThat(sequence.codeAt(8)).isEqualTo("9");
        assertThat(sequence.codeAt(9)).isEqualTo("A");
        assertThat(sequence.codeAt(57)).isEqualTo("z");
        assertThat(sequence.codeAt(58)).isEqualTo("11");
        assertThat(sequence.codeAt(59)).isEqualTo("12");
        assertThat(sequence.codeAt(115)).isEqualTo("1z");
        assertThat(sequence.codeAt(116)).isEqualTo("21");
    }

    @Test
    void agotaLos3364CodigosDeDosCaracteresAntesDeTres() {
        long lastTwoCharIndex = 58 + 3364 - 1;
        assertThat(sequence.codeAt(lastTwoCharIndex)).isEqualTo("zz");
        assertThat(sequence.codeAt(lastTwoCharIndex + 1)).isEqualTo("111");
    }

    @Test
    void nuncaEmiteCodigosReservados() {
        String reserved = sequence.codeAt(0);
        AliasSequence withReserved = new AliasSequence(Set.of(reserved));

        assertThat(withReserved.indexOfNextCode(0)).isEqualTo(1);
    }

    @Test
    void saltaReservasConsecutivas() {
        AliasSequence withReserved = new AliasSequence(List.of("1", "2", "3"));

        assertThat(withReserved.indexOfNextCode(0)).isEqualTo(3);
        assertThat(withReserved.codeAt(3)).isEqualTo("4");
    }
}
