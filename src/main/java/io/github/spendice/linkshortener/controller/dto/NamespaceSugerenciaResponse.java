package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de {@code GET /api/namespace/sugerencia}: nombre derivado de la
 * parte local del email y adaptado al formato del namespace. Es solo una
 * sugerencia: no se reserva hasta que el usuario confirme.
 */
public record NamespaceSugerenciaResponse(String sugerencia) {
}
