package com.example.svcturnos.reserva.domain.model;

/** Estado del intento local, no de la reserva: RECHAZADA tambien incluye fallos tecnicos sin efecto. */
public enum EstadoCancelacion { SOLICITADA, INCIERTA, RECHAZADA, CANCELADA }
