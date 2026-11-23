package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class RecuperarConfirmacionTest {
    ProcesoReserva procesoEn(EstadoProcesoReserva estado) {
        var base = proceso();
        boolean definitivo = estado == EstadoProcesoReserva.CONFIRMADA || estado == EstadoProcesoReserva.CANCELADA;
        return new ProcesoReserva(base.id(), base.usuarioId(), base.profesionalId(), base.fecha(), base.hora(), estado,
                base.holdId(), base.procesoExternoId(), base.vencimientoHold(), base.creadoEn(), base.actualizadoEn(), base.version(),
                definitivo ? 99L : null, estado.resultadoDefinitivo() ? AHORA : null);
    }

    EventoInbox evidencia(String externo, String hold, Instant vencimiento, EstadoEventoReserva estado, Long procesoLocal, Long original) {
        var evento = new EventoReserva("request-original", EventoReserva.TIPO_SOLICITUD_INFORMACION, externo, hold, AHORA, 1,
                new SolicitudInformacionAdicional("PHONE_NUMBER", "Ingrese telefono", vencimiento));
        return new EventoInbox(10L, evento, estado, procesoLocal, original);
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CONFIRMACION_SOLICITADA", "CONFIRMACION_INCIERTA"})
    void evidenciaCorrelacionadaRecuperaSoloAceptacionInicial(EstadoProcesoReserva estado) {
        var anterior = procesoEn(estado);
        var recuperado = anterior.recuperarConfirmacion(solicitud(), AHORA);
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, recuperado.estado());
        assertEquals(anterior.usuarioId(), recuperado.usuarioId());
        assertEquals(anterior.procesoExternoId(), recuperado.procesoExternoId());
        assertEquals(anterior.holdId(), recuperado.holdId());
        assertEquals(solicitud().evento().solicitud().expiresAt(), recuperado.vencimientoHold());
        assertNull(recuperado.reservaId());
        assertTrue(anterior.admiteProgreso(recuperado));
        assertTrue(recuperado.esperaTelefonoVigente(AHORA));
        assertTrue(recuperado.impideNuevoHold(AHORA));
        assertSame(recuperado, recuperado.recuperarConfirmacion(solicitud(), AHORA));
    }

    @Test void evidenciaIncompletaAjenaODuplicadaNoRecupera() {
        var anterior = procesoEn(EstadoProcesoReserva.CONFIRMACION_INCIERTA);
        var vence = AHORA.plusSeconds(100);
        var casos = List.of(
                evidencia("otro", "hold", vence, EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null),
                evidencia("externo", "otro", vence, EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null),
                evidencia("externo", "hold", vence, EstadoEventoReserva.SOLICITUD_REGISTRADA, 43L, null),
                evidencia("externo", "hold", vence, EstadoEventoReserva.SOLICITUD_REGISTRADA, null, null),
                evidencia("externo", "hold", vence, EstadoEventoReserva.PENDIENTE_CORRELACION, 42L, null),
                evidencia("externo", "hold", vence, EstadoEventoReserva.CORRELACION_INCONSISTENTE, 42L, null),
                evidencia("externo", "hold", vence, EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, 9L),
                new EventoInbox(10L, null, EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null),
                new EventoInbox(null, solicitud().evento(), EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null),
                new EventoInbox(10L, new EventoReserva("otro", "EventoFuturo", "externo", "hold", AHORA, 1, null),
                        EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null));
        assertSame(anterior, anterior.recuperarConfirmacion(null, AHORA));
        for (var caso : casos) { assertSame(anterior, anterior.recuperarConfirmacion(caso, AHORA)); }
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 500})
    void respetaVencimientoSinExtenderHold(long segundos) {
        var anterior = procesoEn(EstadoProcesoReserva.CONFIRMACION_INCIERTA);
        var evento = evidencia("externo", "hold", AHORA.plusSeconds(segundos), EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null);
        var resultado = anterior.recuperarConfirmacion(evento, AHORA);
        if (segundos <= 0) { assertSame(anterior, resultado); }
        else { assertEquals(anterior.vencimientoHold(), resultado.vencimientoHold()); }
    }

    @Test void vencimientoDelHoldImpideRecuperacionAunqueSolicitudSigaVigente() {
        var anterior = procesoEn(EstadoProcesoReserva.CONFIRMACION_INCIERTA);
        var ahora = anterior.vencimientoHold();
        var evento = evidencia("externo", "hold", ahora.plusSeconds(100), EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null);
        assertSame(anterior, anterior.recuperarConfirmacion(evento, ahora));
    }

    @Test void noRecuperaUnPlazoQueVenceAlNormalizarLaPrecision() {
        var anterior = procesoEn(EstadoProcesoReserva.CONFIRMACION_INCIERTA);
        var evento = evidencia("externo", "hold", AHORA.plusNanos(1), EstadoEventoReserva.SOLICITUD_REGISTRADA, 42L, null);
        assertSame(anterior, anterior.recuperarConfirmacion(evento, AHORA));
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class,
            names = {"HOLD_REGISTRADO", "CONFIRMACION_RECHAZADA", "HOLD_VENCIDO", "HOLD_NO_RECUPERABLE",
                    "ESPERANDO_TELEFONO", "CONFIRMADA", "CANCELADA", "PROCESO_EXPIRADO", "PROCESO_INVALIDO"})
    void eventoTardioNoReabreEstadosIncompatiblesODefinitivos(EstadoProcesoReserva estado) {
        var anterior = procesoEn(estado);
        assertSame(anterior, anterior.recuperarConfirmacion(solicitud(), AHORA));
    }
}
