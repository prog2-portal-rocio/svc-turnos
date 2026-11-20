package com.example.svcturnos.reserva.domain.model;

import java.util.Objects;
import java.time.temporal.ChronoUnit;
import static com.example.svcturnos.reserva.domain.model.EstadoEventoReserva.*;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.*;

/** Las evidencias terminales prevalecen sobre progreso parcial, nunca reabren un cierre previo. */
public final class PoliticaResultadosReserva {
    private PoliticaResultadosReserva() { }
    public static EstadoEventoReserva evaluar(ProcesoReserva proceso, EventoReserva evento) {
        var resultado = evento.resultado();
        if (resultado == null || !evento.holdId().equals(proceso.holdId())
                || !evento.reservationProcessId().equals(proceso.procesoExternoId()) || !resultado.coincideCon(proceso)) {
            return CORRELACION_INCONSISTENTE;
        }
        if (proceso.estado().resultadoDefinitivo()) { return evaluarFinal(proceso, evento); }
        if (!proceso.estado().requiereDatosHold()) { return PENDIENTE_CORRELACION; }
        if (resultado.tipo() == TipoResultadoReserva.RECHAZO_TELEFONO && proceso.resultadoOcurridoEn() != null) {
            int orden = compararOcurrencia(proceso, evento);
            if (orden < 0) { return RESULTADO_OBSOLETO; }
            // El contrato no identifica el envío rechazado: un empate entre eventos distintos es ambiguo.
            if (orden == 0) { return REQUIERE_RECONCILIACION; }
        }
        return RESULTADO_APLICADO;
    }
    private static EstadoEventoReserva evaluarFinal(ProcesoReserva proceso, EventoReserva evento) {
        var resultado = evento.resultado();
        if (resultado.tipo() == TipoResultadoReserva.RECHAZO_TELEFONO) { return RESULTADO_OBSOLETO; }
        if ((proceso.estado() == resultado.estadoDestino() || proceso.estado() == CANCELADA)
                && Objects.equals(proceso.reservaId(), resultado.reservationId())) { return RESULTADO_OBSOLETO; }
        if (proceso.estado() == CONFIRMADA && resultado.tipo() == TipoResultadoReserva.CANCELACION
                && proceso.reservaId().equals(resultado.reservationId())
                && compararOcurrencia(proceso, evento) >= 0) { return RESULTADO_APLICADO; }
        return REQUIERE_RECONCILIACION;
    }
    private static int compararOcurrencia(ProcesoReserva proceso, EventoReserva evento) {
        return evento.occurredAt().truncatedTo(ChronoUnit.MICROS)
                .compareTo(proceso.resultadoOcurridoEn().truncatedTo(ChronoUnit.MICROS));
    }

}
