package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de entrada de {@code POST /api/links}. El campo
 * {@code destination} es la URL de destino recibida verbatim; la
 * validación la aplican las reglas del service. El campo opcional
 * {@code aliasPersonalizado} (issue #21) pide un enlace personal del
 * usuario de la sesión; ausente o vacío produce el enlace acortado de
 * siempre.
 */
public record CreateLinkRequest(String destination, String aliasPersonalizado) {
}
