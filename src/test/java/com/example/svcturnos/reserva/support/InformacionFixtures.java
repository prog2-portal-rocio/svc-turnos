package com.example.svcturnos.reserva.support;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.*;
import java.util.UUID;

public final class InformacionFixtures {
    private InformacionFixtures() { }
    public static final Instant AHORA = Instant.parse("2026-11-19T12:00:00Z");
    public static final String NUMERO = "+5492615551234";
    public static final String GRUPO = "prueba-integracion";
    public static final String TOPIC = "catedra.turnos.telefono." + GRUPO;
    public static ProcesoReserva proceso() {
        return new ProcesoReserva(42L, 7L, 15L, LocalDate.of(2026,12,1), LocalTime.of(9,30),
                EstadoProcesoReserva.ESPERANDO_TELEFONO, "hold", "externo", AHORA.plusSeconds(300), AHORA, AHORA, 2L);
    }
    public static EventoInbox solicitud() {
        return new EventoInbox(10L, new EventoReserva("request-original", EventoReserva.TIPO_SOLICITUD_INFORMACION,
                "externo", "hold", AHORA, 1, new SolicitudInformacionAdicional("PHONE_NUMBER", "Ingrese telefono", AHORA.plusSeconds(200))),
                EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null);
    }
    public static EnvioInformacion envio(EstadoEnvioInformacion estado) {
        return new EnvioInformacion(UUID.randomUUID(), 42L, 10L, GRUPO, TOPIC, "externo", "hold", "request-original",
                new Telefono(NUMERO), AHORA, AHORA.plusSeconds(200), estado, UUID.randomUUID());
    }
}
