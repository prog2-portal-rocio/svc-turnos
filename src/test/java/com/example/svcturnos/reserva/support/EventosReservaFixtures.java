package com.example.svcturnos.reserva.support;

import com.example.svcturnos.reserva.domain.model.MensajeReserva;
import java.nio.charset.StandardCharsets;

public final class EventosReservaFixtures {
    private EventosReservaFixtures() { }

    public static String solicitud(String eventId, String proceso, String hold) {
        return """
                {"eventId":"%s","eventType":"AdditionalInformationRequested","reservationProcessId":"%s",
                 "holdId":"%s","requiredField":"PHONE_NUMBER","message":"Ingrese un numero de telefono para continuar.",
                 "expiresAt":"2026-09-10T14:44:50Z","occurredAt":"2026-09-10T14:35:10Z","schemaVersion":1}
                """.formatted(eventId, proceso, hold);
    }

    public static MensajeReserva mensaje(String body, String key, long offset) {
        return new MensajeReserva("acciones-prueba", 0, offset, key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }
}
