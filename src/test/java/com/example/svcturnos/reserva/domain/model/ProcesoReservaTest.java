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
}
