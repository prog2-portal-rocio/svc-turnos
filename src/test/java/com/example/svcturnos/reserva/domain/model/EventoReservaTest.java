package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventoReservaTest {
    final Instant ahora = Instant.parse("2026-09-10T14:35:10Z");
    final EventoReserva evento = new EventoReserva("event", EventoReserva.SOLICITUD, "process", "hold", ahora, 1,
            new SolicitudInformacionAdicional("PHONE_NUMBER", "Informe telefono", ahora.plusSeconds(300)));

    ProcesoReserva proceso(String externo, String hold) {
        return new ProcesoReserva(1L, 7L, 15L, LocalDate.of(2026, 9, 14), LocalTime.of(9, 30),
                EstadoProcesoReserva.HOLD_REGISTRADO, hold, externo, ahora.plusSeconds(300), ahora, ahora, 0L);
    }

    @Test void correlacionaSinRequerirConfirmacionRestNiCambiarElProceso() {
        var local = proceso("process", "hold");
        var resultado = evento.correlacionar(List.of(local));
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, resultado.estado());
        assertEquals(local.id(), resultado.procesoLocalId());
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, local.estado());
        assertEquals(ahora.plusSeconds(300), local.vencimientoHold());
    }

    @Test void eventoAdelantadoPermanecePendiente() {
        assertEquals(EstadoEventoReserva.PENDIENTE_CORRELACION, evento.correlacionar(List.of()).estado());
    }

    @Test void noAsociaEventosConUnHoldIncoherenteNiProcesosAmbiguos() {
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE, evento.correlacionar(List.of(proceso("process", "otro"))).estado());
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE, evento.correlacionar(List.of(proceso("otro", "hold"))).estado());
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE, evento.correlacionar(List.of(proceso("process", "hold"), proceso("process", "hold"))).estado());
    }

    @Test void otrosEventosSoloQuedanConservadosParaOtroIssue() {
        var otro = new EventoReserva("event", "AppointmentConfirmed", "process", "hold", ahora, 1, null);
        assertEquals(EstadoEventoReserva.PENDIENTE_TIPO, otro.correlacionar(List.of()).estado());
    }

    @Test void invariantesDelDominioNoDependenDeJackson() {
        assertThrows(IllegalArgumentException.class, () -> new SolicitudInformacionAdicional("OTRO", "mensaje", ahora));
        var solicitud = evento.solicitud();
        assertThrows(IllegalArgumentException.class, () -> new EventoReserva(" ", EventoReserva.SOLICITUD, "p", "h", ahora, 1, solicitud));
        assertThrows(IllegalArgumentException.class, () -> new EventoReserva("e", EventoReserva.SOLICITUD, "p", "h", ahora, 2, solicitud));
        assertThrows(IllegalArgumentException.class, () -> new EventoReserva("e", EventoReserva.SOLICITUD, "p", "h", ahora, 1, null));
    }
}
