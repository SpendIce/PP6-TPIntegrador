package io.github.spendice.linkshortener.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AliasRecyclingTest {

    @Test
    void sinCandidatosVencidosNoEligeNada() {
        assertThat(AliasRecycling.chooseRecyclable(List.of())).isEmpty();
    }

    @Test
    void eligeElVencidoDeMenorLongitud() {
        assertThat(AliasRecycling.chooseRecyclable(List.of("zz", "1"))).contains("1");
        assertThat(AliasRecycling.chooseRecyclable(List.of("zzz", "ab", "c"))).contains("c");
    }

    @Test
    void laLongitudPesaMasQueElOrdenDelCodigo() {
        // En orden natural "11" precede a "2": elegir "2" demuestra que la
        // prioridad es la menor longitud y no el orden lexicográfico.
        assertThat(AliasRecycling.chooseRecyclable(List.of("11", "2"))).contains("2");
    }

    @Test
    void entreIgualLongitudDesempataPorOrdenNaturalDelCodigo() {
        assertThat(AliasRecycling.chooseRecyclable(List.of("z", "9", "A"))).contains("9");
        assertThat(AliasRecycling.chooseRecyclable(List.of("zy", "aa", "11"))).contains("11");
    }

    @Test
    void elDesempateDistingueMayusculasDeMinusculas() {
        // 'A' precede a 'a' en el orden natural: son códigos distintos y
        // el desempate sigue siendo determinista.
        assertThat(AliasRecycling.chooseRecyclable(List.of("a", "A"))).contains("A");
    }
}
