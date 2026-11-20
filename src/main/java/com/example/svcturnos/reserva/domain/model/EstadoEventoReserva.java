package com.example.svcturnos.reserva.domain.model;

/** Estados de recepción/correlación, independientes del estado de la reserva. */
public enum EstadoEventoReserva {
    RECIBIDO, SOLICITUD_REGISTRADA, PENDIENTE_CORRELACION, CORRELACION_INCONSISTENTE,
    PENDIENTE_TIPO, INVALIDO, DUPLICADO, EVENT_ID_EN_CONFLICTO,
    RESULTADO_APLICADO, RESULTADO_OBSOLETO, REQUIERE_RECONCILIACION
}
