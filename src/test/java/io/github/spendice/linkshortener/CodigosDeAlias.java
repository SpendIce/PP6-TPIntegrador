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
     * Índice del código en la enumeración, la inversa de
     * {@link #codigoEn}: suma los tramos completos de las longitudes
     * anteriores más el desplazamiento del código dentro del suyo.
     */
    static long indiceDe(String codigo) {
        long indice = 0;
        for (int l = 1; l < codigo.length(); l++) {
            indice += potencia(ALFABETO.length(), l);
        }
        for (int i = 0; i < codigo.length(); i++) {
            int posicion = ALFABETO.indexOf(codigo.charAt(i));
            if (posicion < 0) {
                throw new IllegalArgumentException(
                        "El código '" + codigo + "' no es del alfabeto por defecto");
            }
            indice += (long) posicion
                    * potencia(ALFABETO.length(), codigo.length() - 1 - i);
        }
        return indice;
    }

    private static long potencia(int base, int exponente) {
        long resultado = 1;
        for (int i = 0; i < exponente; i++) {
            resultado *= base;
        }
        return resultado;
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
