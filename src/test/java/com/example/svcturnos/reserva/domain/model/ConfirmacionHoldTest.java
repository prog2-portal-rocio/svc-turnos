package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.*;

class ConfirmacionHoldTest {
    final Instant ahora = Instant.parse("2026-11-19T12:00:00Z");
    ProcesoReserva registrado() {
        return new ProcesoReserva(1L, 7L, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 0), HOLD_REGISTRADO,
                "hold", "externo", ahora.plusSeconds(300), ahora.minusSeconds(60), ahora, 1L);
    }

    @Test void progresoNoConfirmaReservaNiExtiendeVigencia() {
        var solicitado = registrado().solicitarConfirmacion(ahora);
        var aceptado = solicitado.registrarConfirmacion(ahora.plusSeconds(200), ahora);
        assertEquals(CONFIRMACION_SOLICITADA, solicitado.estado());
        assertEquals(ESPERANDO_TELEFONO, aceptado.estado());
        assertEquals("hold", aceptado.holdId());
        assertTrue(aceptado.impideNuevoHold(ahora.plusSeconds(1000)));
        var extendido = ahora.plusSeconds(301);
        assertEquals(registrado().vencimientoHold(), solicitado.registrarConfirmacion(extendido, ahora).vencimientoHold());
        assertThrows(IllegalStateException.class, () -> aceptado.solicitarConfirmacion(ahora));
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CONFIRMACION_SOLICITADA", "CONFIRMACION_INCIERTA", "ESPERANDO_TELEFONO"})
    void incertidumbreOProgresoRemotoNoLiberanDuplicadosPorTiempo(EstadoProcesoReserva estado) {
        var p = registrado();
        var proceso = new ProcesoReserva(p.id(), p.usuarioId(), p.profesionalId(), p.fecha(), p.hora(), estado,
                p.holdId(), p.procesoExternoId(), p.vencimientoHold(), p.creadoEn(), p.actualizadoEn(), p.version());
        assertTrue(proceso.impideNuevoHold(ahora.plusSeconds(1000)));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "A", " A ", "\tA\t", "\u2003"})
    void nombreOApellidoInvalidosNoSeEnvian(String nombre) {
        assertThrows(IllegalArgumentException.class, () -> new PacienteReserva(7L, nombre, "Perez"));
        assertThrows(IllegalArgumentException.class, () -> new PacienteReserva(7L, "Juan", nombre));
    }

    @Test void pacienteNormalizaEspaciosSinCambiarLosDatosDeIdentidad() {
        var paciente = new PacienteReserva(7L, "  María  José  ", "\tO'Neil-李\t");
        assertEquals("María  José", paciente.patientFirstName());
        assertEquals("O'Neil-李", paciente.patientLastName());
        assertEquals("user-7", paciente.externalPatientId());
    }

    @Test void limitesContractualesDeNombresEIdentificador() {
        var nombreLargo = "a".repeat(101);
        assertThrows(IllegalArgumentException.class, () -> new PacienteReserva(7L, nombreLargo, "Perez"));
        assertThrows(IllegalArgumentException.class, () -> new PacienteReserva(0L, "Juan", "Perez"));
        assertEquals("user-7", new PacienteReserva(7L, "Ju", "Pe").externalPatientId());
    }

    @Test void vencidoNoPuedeSolicitarseYResultadoRequiereOperacionEspecifica() {
        var hold = registrado();
        var cierre = ahora.plusSeconds(300);
        assertThrows(IllegalStateException.class, () -> hold.solicitarConfirmacion(cierre));
        assertThrows(IllegalArgumentException.class, () -> hold.registrarResultadoConfirmacion(ESPERANDO_TELEFONO, ahora));
        var solicitado = hold.solicitarConfirmacion(ahora);
        assertThrows(IllegalArgumentException.class, () -> solicitado.registrarConfirmacion(null, ahora));
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0, 200, 301})
    void aceptacionYVigenciaSonIndependientes(long segundos) {
        var aceptado = registrado().solicitarConfirmacion(ahora).registrarConfirmacion(ahora.plusSeconds(segundos), ahora);
        assertEquals(ESPERANDO_TELEFONO, aceptado.estado());
        assertEquals(segundos > 0, aceptado.esperaTelefonoVigente(ahora));
        assertEquals(ahora.plusSeconds(Math.min(segundos, 300)), aceptado.vencimientoHold());
        assertTrue(aceptado.impideNuevoHold(ahora.plusSeconds(1000)));
    }

}
