package io.github.spendice.linkshortener.service;

import io.github.spendice.linkshortener.mappings.Usuario;

/**
 * Resultado de {@link CuentaService#consumirVinculacion(String,
 * jakarta.servlet.http.HttpServletRequest, jakarta.servlet.http.HttpSession,
 * java.time.Duration)}: el
 * estado efectivo del código y, solo cuando es {@code APROBADA}, el
 * usuario que lo aprobó para materializar su sesión vinculada.
 */
public record ResultadoVinculacion(EstadoVinculacion estado, Usuario usuario) {
}
