package com.example.svcturnos.reserva.domain.model;

/** Estados locales; registrar un hold no confirma una reserva. */
public enum EstadoProcesoReserva {
    CREADO, HOLD_SOLICITADO, HOLD_RECHAZADO, HOLD_REGISTRADO
}
