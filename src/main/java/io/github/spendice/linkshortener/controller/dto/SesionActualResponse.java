package io.github.spendice.linkshortener.controller.dto;

/**
 * DTO de {@code GET /api/sesion}: el usuario de la sesión actual o
 * {@code null} cuando no hay sesión o la sesión no tiene usuario.
 */
public record SesionActualResponse(UsuarioSesion usuario) {
}
