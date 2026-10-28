package io.github.spendice.linkshortener.service;

import java.time.Instant;

/**
 * Resultado de {@link CuentaService#crearVinculacion()}: el código de un
 * solo uso y la URL de confirmación que el QR codifica, compuesta sobre
 * la dirección pública configurada ({@code public-base-url}) más
 * {@code /vincular/{codigo}}.
 */
public record CodigoVinculacionCreado(String codigo, String confirmacionUrl,
                                      Instant venceEn) {
}
