package io.github.spendice.linkshortener.service;

/**
 * Estado efectivo de un código de vinculación por QR (issue #22) en el
 * instante del servidor que lo consulta. No es el estado persistido:
 * combina la columna {@code estado} de {@code codigo_vinculacion} con el
 * vencimiento, que se evalúa al consultar (como las asignaciones).
 *
 * <ul>
 *   <li>{@code PENDIENTE}: existe, está vigente y nadie lo aprobó.</li>
 *   <li>{@code APROBADA}: una sesión principal lo aprobó y todavía no se
 *   consume; la consulta del dispositivo que espera lo convierte en la
 *   apertura de la sesión vinculada.</li>
 *   <li>{@code VENCIDA}: venció su {@code link-code-ttl} o ya se usó; no
 *   abre ninguna sesión.</li>
 *   <li>{@code INEXISTENTE}: no hay código con ese valor.</li>
 * </ul>
 */
public enum EstadoVinculacion {
    PENDIENTE,
    APROBADA,
    VENCIDA,
    INEXISTENTE
}
