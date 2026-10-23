package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de entrada de {@code POST /api/links}. El campo
 * {@code destination} es la URL de destino recibida verbatim; la
 * validación la aplican las reglas del service.
 */
public record CreateLinkRequest(String destination) {
}
