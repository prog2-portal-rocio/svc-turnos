package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcesoReservaTest {
    private ProcesoReserva nuevo() {
        return ProcesoReserva.crear(1L, 2L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));
    }

    @Test
    void creaProcesoSinConfundirloConReservaConfirmada() {
        var p = nuevo();
        assertNull(p.id());
        assertNull(p.holdId());
        assertEquals(EstadoProcesoReserva.CREADO, p.estado());
        var conHold = p.registrarHold("hold", "externo", Instant.now().plusSeconds(300));
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, conHold.estado());
        assertEquals(p.usuarioId(), conHold.usuarioId());
        Instant vencimiento = conHold.vencimientoHold();
        assertThrows(IllegalStateException.class,
                () -> conHold.registrarHold("otro", "externo", vencimiento));
    }

    @Test
    void rechazaSolicitudInvalidaYProgresoIncompleto() {
        LocalDate fecha = LocalDate.of(2026, 12, 1);
        LocalTime hora = LocalTime.of(9, 30);
        assertThrows(IllegalArgumentException.class, () -> ProcesoReserva.crear(null, 2L, fecha, hora));
        assertThrows(IllegalArgumentException.class, () -> ProcesoReserva.crear(1L, 0L, fecha, hora));
        assertThrows(IllegalArgumentException.class, () -> ProcesoReserva.crear(1L, 2L, null, hora));
        assertThrows(IllegalArgumentException.class, () -> ProcesoReserva.crear(1L, 2L, fecha, null));
        var p = nuevo();
        Instant vence = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> p.registrarHold(" ", "externo", vence));
        assertThrows(IllegalArgumentException.class, () -> p.registrarHold("hold", null, vence));
        assertThrows(IllegalArgumentException.class, () -> p.registrarHold("hold", "externo", null));
    }
    @Test
    void intentoInciertoNoPuedeReiniciarseYRechazoNoConfirmaReserva() {
        var solicitado = nuevo().solicitarHold();
        assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, solicitado.estado());
        assertNull(solicitado.holdId());
        assertThrows(IllegalStateException.class, solicitado::solicitarHold);
        var rechazado = solicitado.rechazarHold();
        assertEquals(EstadoProcesoReserva.HOLD_RECHAZADO, rechazado.estado());
        assertThrows(IllegalStateException.class, rechazado::solicitarHold);
        Instant vence = Instant.now();
        assertThrows(IllegalStateException.class, () -> rechazado.registrarHold("h", "p", vence));
        var registrado = solicitado.registrarHold("h", "p", vence);
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, registrado.estado());
        assertEquals(vence.truncatedTo(java.time.temporal.ChronoUnit.MICROS), registrado.vencimientoHold());
    }

    @Test
    void solicitudRechazaParametrosInvalidosSinInvocarDependencias() {
        LocalDate fecha = LocalDate.of(2026, 12, 1);
        LocalTime hora = LocalTime.of(9, 30);
        assertThrows(IllegalArgumentException.class, () -> new SolicitudHold(0L, fecha, hora));
        assertThrows(IllegalArgumentException.class, () -> new SolicitudHold(15L, null, hora));
        assertThrows(IllegalArgumentException.class, () -> new SolicitudHold(15L, fecha, null));
    }
    @Test
    void soloUnIntentoInciertoOUnHoldVigenteImpidenUnaNuevaSolicitud() {
        Instant ahora = Instant.parse("2026-11-18T14:55:00Z");
        var creado = nuevo();
        var solicitado = creado.solicitarHold();
        assertFalse(creado.impideNuevoHold(ahora));
        assertTrue(solicitado.impideNuevoHold(ahora.plusSeconds(3600)));
        assertFalse(solicitado.rechazarHold().impideNuevoHold(ahora));
        assertTrue(solicitado.registrarHold("h", "p", ahora.plusSeconds(1)).impideNuevoHold(ahora));
        assertFalse(solicitado.registrarHold("h", "p", ahora).impideNuevoHold(ahora));
        assertFalse(solicitado.registrarHold("h", "p", ahora.minusSeconds(1)).impideNuevoHold(ahora));
    }
}
