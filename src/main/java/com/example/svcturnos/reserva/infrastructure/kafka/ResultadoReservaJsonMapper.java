package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import tools.jackson.databind.JsonNode;
import java.time.*;

/** Lectura estricta de campos conocidos y tolerante a extensiones v1. */
public final class ResultadoReservaJsonMapper {
    private static final String CAMPO_MOTIVO = "reason";
    private ResultadoReservaJsonMapper() { }
    public static ResultadoReserva leer(TipoResultadoReserva tipo, JsonNode json) {
        String grupo = texto(json, "groupId");
        String request = texto(json, "requestEventId");
        return switch (tipo) {
            case CONFIRMACION, CANCELACION -> new ResultadoReserva(tipo, grupo, request, positivo(json, "reservationId"),
                    positivo(json, "professionalId"), LocalDate.parse(texto(json, "date")), hora(texto(json, "startTime")),
                    texto(json, "status"), tipo == TipoResultadoReserva.CANCELACION ? opcional(json, CAMPO_MOTIVO) : null, null, null);
            case RECHAZO_TELEFONO -> new ResultadoReserva(tipo, grupo, request, null, null, null, null, null,
                    texto(json, CAMPO_MOTIVO), texto(json, "message"), Instant.parse(texto(json, "expiresAt")));
            case EXPIRACION, INVALIDEZ -> new ResultadoReserva(tipo, grupo, request, null, null, null, null, null,
                    texto(json, CAMPO_MOTIVO), null, null);
        };
    }
    private static LocalTime hora(String dato) {
        if (dato.length() != 5 && dato.length() != 8) { throw new IllegalArgumentException("Hora inválida"); }
        return LocalTime.parse(dato);
    }
    private static String texto(JsonNode json, String campo) {
        String dato = opcional(json, campo);
        if (dato == null || dato.isBlank()) { throw new IllegalArgumentException("Campo de resultado obligatorio"); }
        return dato;
    }
    private static String opcional(JsonNode json, String campo) {
        var dato = json.path(campo);
        if (dato.isMissingNode() || dato.isNull()) { return null; }
        if (!dato.isString() || dato.asString().indexOf(0) >= 0) { throw new IllegalArgumentException("Texto inválido"); }
        return dato.asString();
    }
    private static Long positivo(JsonNode json, String campo) {
        var dato = json.path(campo);
        if (!dato.isIntegralNumber() || !dato.canConvertToLong() || dato.longValue() <= 0) {
            throw new IllegalArgumentException("Identificador de resultado inválido");
        }
        return dato.longValue();
    }
}
