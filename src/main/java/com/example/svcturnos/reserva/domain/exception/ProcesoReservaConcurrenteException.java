package com.example.svcturnos.reserva.domain.exception;

public class ProcesoReservaConcurrenteException extends RuntimeException {
    public ProcesoReservaConcurrenteException() {
        super("El proceso fue actualizado por otra operación");
    }
}
