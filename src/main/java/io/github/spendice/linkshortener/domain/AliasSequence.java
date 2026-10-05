package io.github.spendice.linkshortener.domain;

import java.util.Collection;
import java.util.Set;

/**
 * Política de generación de códigos nuevos: enumera el espacio del
 * alfabeto en orden determinista, agotando los códigos de un carácter
 * antes de pasar a dos, y así sucesivamente. Los códigos reservados
 * (rutas de la propia aplicación) se saltan sin emitirse.
 *
 * <p>Esta política solo decide qué código corresponde a cada índice;
 * la reserva atómica del índice es responsabilidad del mecanismo
 * transaccional del adaptador de persistencia (ADR 0002).</p>
 */
public final class AliasSequence {

    private final Set<String> reservedCodes;

    public AliasSequence(Collection<String> reservedCodes) {
        this.reservedCodes = Set.copyOf(reservedCodes);
    }

    /**
     * Primer índice, a partir de {@code fromIndex}, cuyo código no está
     * reservado.
     */
    public long indexOfNextCode(long fromIndex) {
        long index = fromIndex;
        while (reservedCodes.contains(codeAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * Código del alfabeto que ocupa la posición {@code index} en la
     * enumeración: posiciones 0..57 son códigos de un carácter,
     * 58..3421 de dos, y así sucesivamente. Dentro de cada longitud el
     * orden es el del alfabeto, por lo que el desempate es determinista.
     */
    public String codeAt(long index) {
        if (index < 0) {
            throw new IllegalArgumentException("El índice de código no puede ser negativo: " + index);
        }
        long offset = index;
        int length = 1;
        long band = AliasAlphabet.SIZE;
        while (offset >= band) {
            offset -= band;
            length++;
            band *= AliasAlphabet.SIZE;
        }
        char[] code = new char[length];
        for (int i = length - 1; i >= 0; i--) {
            code[i] = AliasAlphabet.SYMBOLS.charAt((int) (offset % AliasAlphabet.SIZE));
            offset /= AliasAlphabet.SIZE;
        }
        return new String(code);
    }
}
