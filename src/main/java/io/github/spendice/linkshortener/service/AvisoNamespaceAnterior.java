package io.github.spendice.linkshortener.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Datos del aviso que sirve la dirección de un namespace anterior dentro
 * de su período de gracia (issue #25): la nueva dirección completa del
 * enlace personal, la hora límite en que la anterior deja de funcionar
 * —la que ocurra primero entre el fin de la gracia de ese nombre y el
 * vencimiento del enlace— y la demora de la redirección automática
 * ({@code cuenta.namespace-notice-delay}).
 */
public record AvisoNamespaceAnterior(String nuevaDireccion, Instant horaLimite,
                                     Duration demora) {
}
