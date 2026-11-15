package com.example.svcturnos.reserva.domain.exception;

public class ProcesoReservaNoActualizableException extends RuntimeException {
    public ProcesoReservaNoActualizableException(String mensaje) {
        super(mensaje);
    }
}
