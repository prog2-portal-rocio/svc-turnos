package com.example.svcturnos.reserva.support;

import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

public final class ResultadosReservaFixtures {
    private ResultadosReservaFixtures() { }
    public static final String CONFIRMACION = "AppointmentConfirmed";
    public static final String RECHAZO = "AdditionalInformationRejected";
    public static final String EXPIRACION = "AppointmentProcessExpired";
    public static final String INVALIDEZ = "AppointmentProcessInvalid";
    public static final String CANCELACION = "AppointmentCancelled";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static ObjectNode resultado(String tipo, String id, String externo, String hold, String request, Instant ocurrido) {
        var json = JSON.createObjectNode();
        json.put("eventId",id).put("eventType",tipo).put("reservationProcessId",externo).put("holdId",hold)
                .put("groupId",GRUPO).put("requestEventId",request).put("occurredAt",ocurrido.toString()).put("schemaVersion",1);
        switch (tipo) {
            case CONFIRMACION, CANCELACION -> json.put("reservationId",8251).put("professionalId",15)
                    .put("date","2026-12-01").put("startTime","09:30")
                    .put("status",tipo.equals(CONFIRMACION) ? "CONFIRMED" : "CANCELLED");
            case RECHAZO -> json.put("reason","INVALID_PHONE_NUMBER").put("message","El telefono ingresado no es valido.")
                    .put("expiresAt",AHORA.plusSeconds(200).toString());
            case EXPIRACION -> json.put("reason","HOLD_EXPIRED");
            case INVALIDEZ -> json.put("reason","REQUEST_EVENT_MISMATCH");
            default -> throw new IllegalArgumentException("Fixture de resultado desconocida");
        }
        return json;
    }
}
