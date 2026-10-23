package io.github.spendice.linkshortener;

import java.util.Collection;
import java.util.Set;

/**
 * Enumeración de códigos del alfabeto por defecto para las pruebas HTTP:
 * replica la cuenta del generador para sembrar alias por índice o
 * anticipar el próximo código, sin depender del service ni de clases
 * internas del esquema. Todas las suites corren con el alfabeto por
 * defecto y el desempate de reciclaje se verifica por respuesta HTTP.
 */
final class CodigosDeAlias {

    private static final String ALFABETO =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private CodigosDeAlias() {
    }

    /**
     * Código del alfabeto que ocupa la posición {@code indice} en la
     * enumeración: posiciones 0..57 son códigos de un carácter,
     * 58..3421 de dos, y así sucesivamente.
     */
    static String codigoEn(long indice) {
        if (indice < 0) {
            throw new IllegalArgumentException("El índice de código no puede ser negativo: " + indice);
        }
        long offset = indice;
        int longitud = 1;
        long tramo = ALFABETO.length();
        while (offset >= tramo) {
            offset -= tramo;
            longitud++;
            tramo *= ALFABETO.length();
        }
        char[] codigo = new char[longitud];
        for (int i = longitud - 1; i >= 0; i--) {
            codigo[i] = ALFABETO.charAt((int) (offset % ALFABETO.length()));
            offset /= ALFABETO.length();
        }
        return new String(codigo);
    }

    /**
     * Primer índice, a partir de {@code desde}, cuyo código no está
     * entre los {@code reservados} configurados en el contexto de prueba.
     */
    static long indiceDelSiguienteCodigo(long desde, Collection<String> reservados) {
        Set<String> reservado = Set.copyOf(reservados);
        long indice = desde;
        while (reservado.contains(codigoEn(indice))) {
            indice++;
        }
        return indice;
    }
}
