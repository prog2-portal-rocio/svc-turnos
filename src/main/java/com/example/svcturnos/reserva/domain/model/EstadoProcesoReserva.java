package com.example.svcturnos.reserva.domain.model;

/** Estados locales; ni el hold registrado ni la espera de teléfono confirman una reserva. */
public enum EstadoProcesoReserva {
    CREADO, HOLD_SOLICITADO, HOLD_RECHAZADO, HOLD_REGISTRADO,
    CONFIRMACION_SOLICITADA, CONFIRMACION_INCIERTA,
    /** Último intento no aceptado (también por configuración o credencial técnica); no es un estado final de reserva. */
    CONFIRMACION_RECHAZADA,
    /** Aceptación inicial observada. Su vigencia se consulta por separado mediante el vencimiento. */
    ESPERANDO_TELEFONO, HOLD_VENCIDO, HOLD_NO_RECUPERABLE;

    public boolean requiereDatosHold() {
        return this != CREADO && this != HOLD_SOLICITADO && this != HOLD_RECHAZADO;
    }
}
