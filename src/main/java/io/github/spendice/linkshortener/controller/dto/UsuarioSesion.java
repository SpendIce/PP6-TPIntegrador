package io.github.spendice.linkshortener.controller.dto;

/**
 * Usuario de la sesión actual en el contrato de {@code GET /api/sesion}:
 * email de la cuenta de Google, namespace (vacío hasta que el usuario lo
 * elija) y tipo de sesión ({@code principal} hoy; la vinculación por QR
 * sumará {@code vinculada}).
 */
public record UsuarioSesion(String email, String namespace, String tipo) {
}
