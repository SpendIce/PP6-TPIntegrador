package io.github.spendice.linkshortener.controller.dto;

/**
 * Cuerpo de {@code GET /api/vinculacion/{codigo}/confirmacion} (issue
 * #22): el estado del código desde la vista del que aprueba y el usuario
 * de su sesión, que la página muestra como namespace o email.
 */
public record ConfirmacionVinculacionResponse(String estado, UsuarioSesion usuario) {
}
