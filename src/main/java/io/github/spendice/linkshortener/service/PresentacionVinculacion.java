package io.github.spendice.linkshortener.service;

/**
 * Resultado de presentar el código de vinculación a la sesión que abre
 * la confirmación (issue #22): combina el estado efectivo del código
 * con la ligadura al usuario que la ve — la primera sesión principal
 * que la abre lo registra en {@code presentado_a} y solo ese usuario
 * puede aprobarlo después.
 */
public enum PresentacionVinculacion {
    /** El código no existe, venció o ya se usó: la página pide uno nuevo. */
    INVALIDO,
    /** Pendiente y presentado al usuario de la sesión que consulta. */
    PENDIENTE,
    /** Ya aprobado: la página lo informa sin ofrecer la acción. */
    APROBADA,
    /**
     * Pendiente pero ya presentado a otro usuario: a esta sesión el
     * código le responde como uno que no sirve y no lo puede aprobar.
     */
    PRESENTADA_A_OTRO
}
