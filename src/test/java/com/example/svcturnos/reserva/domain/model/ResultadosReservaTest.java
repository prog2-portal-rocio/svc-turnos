package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.infrastructure.kafka.EventoReservaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;
import static com.example.svcturnos.reserva.support.ResultadosReservaFixtures.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.mensaje;

class ResultadosReservaTest {
    EventoReserva evento(String tipo,Instant ocurrido) {
        return new EventoReservaDecoder().decodificar(mensaje(resultado(tipo,"e","externo","hold","request",ocurrido).toString(),"externo",0)).evento();
    }
    @ParameterizedTest @CsvSource({"AppointmentConfirmed,CONFIRMADA", "AdditionalInformationRejected,ESPERANDO_TELEFONO",
            "AppointmentProcessExpired,PROCESO_EXPIRADO", "AppointmentProcessInvalid,PROCESO_INVALIDO", "AppointmentCancelled,CANCELADA"})
    void aplicaSoloEvidenciaContractual(String tipo,EstadoProcesoReserva destino) {
        var e=evento(tipo,AHORA.plusSeconds(1));var p=proceso();
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,PoliticaResultadosReserva.evaluar(p,e));
        var cambiado=p.registrarResultadoEvento(e.resultado(),e.occurredAt(),AHORA.plusSeconds(2));
        assertEquals(destino,cambiado.estado());assertEquals(7L,cambiado.usuarioId());
        assertEquals(e.resultado().reservationId(),cambiado.reservaId());
    }
    @Test void confirmacionPermiteCancelacionInclusoEnMismoInstante() {
        var e=evento(CONFIRMACION,AHORA);var confirmado=proceso().registrarResultadoEvento(e.resultado(),AHORA,AHORA);
        var cancelacion=evento(CANCELACION,AHORA);
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,PoliticaResultadosReserva.evaluar(confirmado,cancelacion));
        assertEquals(EstadoProcesoReserva.CANCELADA,confirmado.registrarResultadoEvento(cancelacion.resultado(),AHORA,AHORA).estado());
        assertTrue(confirmado.impideNuevoHold(AHORA.plusSeconds(1000)));
    }
    @ParameterizedTest @ValueSource(strings={EXPIRACION,INVALIDEZ,CANCELACION})
    void cierresNoSeReabrenPorConfirmacionTardia(String tipo) {
        var cierre=evento(tipo,AHORA.plusSeconds(2));
        var cerrado=proceso().registrarResultadoEvento(cierre.resultado(),cierre.occurredAt(),AHORA.plusSeconds(3));
        assertNotEquals(EstadoEventoReserva.RESULTADO_APLICADO,PoliticaResultadosReserva.evaluar(cerrado,evento(CONFIRMACION,AHORA)));
        var confirmacion=evento(CONFIRMACION,AHORA.plusSeconds(4));
        var datos=confirmacion.resultado();var ocurrido=confirmacion.occurredAt();var ahora=AHORA.plusSeconds(5);
        assertThrows(IllegalStateException.class, () -> cerrado.registrarResultadoEvento(datos,ocurrido,ahora));
    }
    @Test void rechazoNoRetrocedeConfirmacionYConfirmacionPrevaleceSobreProgresoParcial() {
        var rechazo=evento(RECHAZO,AHORA.plusSeconds(2));
        var esperando=proceso().registrarResultadoEvento(rechazo.resultado(),rechazo.occurredAt(),AHORA.plusSeconds(3));
        var confirmacion=evento(CONFIRMACION,AHORA);
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,PoliticaResultadosReserva.evaluar(esperando,confirmacion));
        var confirmado=esperando.registrarResultadoEvento(confirmacion.resultado(),confirmacion.occurredAt(),AHORA.plusSeconds(4));
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,PoliticaResultadosReserva.evaluar(confirmado,rechazo));
    }
    @Test void rechazoAnteriorNoHabilitaOtraRespuesta() {
        var rechazo=evento(RECHAZO,AHORA.plusSeconds(2));
        var esperando=proceso().registrarResultadoEvento(rechazo.resultado(),rechazo.occurredAt(),AHORA.plusSeconds(3));
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,PoliticaResultadosReserva.evaluar(esperando,evento(RECHAZO,AHORA)));
    }
    @Test void rechazosDistintosConMismoInstanteRequierenRevisionSinReabrirRespuesta() {
        var rechazo=evento(RECHAZO,AHORA);
        var esperando=proceso().registrarResultadoEvento(rechazo.resultado(),AHORA,AHORA);
        var distinto=new EventoReserva("otro-rechazo",rechazo.eventType(),rechazo.reservationProcessId(),rechazo.holdId(),
                rechazo.occurredAt(),1,null,rechazo.resultado());
        assertEquals(EstadoEventoReserva.REQUIERE_RECONCILIACION,PoliticaResultadosReserva.evaluar(esperando,distinto));
    }

    @ParameterizedTest @CsvSource({"-1000,RESULTADO_OBSOLETO", "-789,REQUIERE_RECONCILIACION",
            "0,REQUIERE_RECONCILIACION", "100,REQUIERE_RECONCILIACION", "210,REQUIERE_RECONCILIACION",
            "211,RESULTADO_APLICADO", "1000,RESULTADO_APLICADO"})
    void rechazosComparanConLaMismaPrecisionQueElProceso(long diferencia,EstadoEventoReserva esperado) {
        Instant original=AHORA.plusNanos(123456789);
        var primero=evento(RECHAZO,original);
        var persistible=proceso().registrarResultadoEvento(primero.resultado(),original,original);
        var segundo=evento(RECHAZO,original.plusNanos(diferencia));
        assertEquals(original.truncatedTo(java.time.temporal.ChronoUnit.MICROS),persistible.resultadoOcurridoEn());
        assertEquals(esperado,PoliticaResultadosReserva.evaluar(persistible,segundo));
        assertEquals(original,primero.occurredAt());
    }

    @ParameterizedTest @CsvSource({"-1000,REQUIERE_RECONCILIACION", "-100,RESULTADO_APLICADO", "0,RESULTADO_APLICADO", "1000,RESULTADO_APLICADO"})
    void cancelacionTrasConfirmacionComparaMicrosegundos(long diferencia,EstadoEventoReserva esperado) {
        Instant original=AHORA.plusNanos(123456789);var confirmacion=evento(CONFIRMACION,original);
        var confirmado=proceso().registrarResultadoEvento(confirmacion.resultado(),original,original);
        assertEquals(esperado,PoliticaResultadosReserva.evaluar(confirmado,evento(CANCELACION,original.plusNanos(diferencia))));
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,
                PoliticaResultadosReserva.evaluar(confirmado,evento(RECHAZO,original.plusSeconds(1))));
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7})
    void camposOpcionalesParcialesSeComparanIndependientemente(int campos) {
        var parcial=new ResultadoReserva(TipoResultadoReserva.EXPIRACION,GRUPO,"request",null,
                (campos & 1) == 0 ? null : 15L,(campos & 2) == 0 ? null : LocalDate.of(2026,12,1),
                (campos & 4) == 0 ? null : LocalTime.of(9,30),null,"HOLD_EXPIRED",null,null);
        assertTrue(parcial.coincideCon(proceso()));
        var inconsistente=new ResultadoReserva(TipoResultadoReserva.EXPIRACION,GRUPO,"request",null,
                (campos & 1) == 0 ? null : 99L,(campos & 2) == 0 ? null : LocalDate.of(2026,12,2),
                (campos & 4) == 0 ? null : LocalTime.of(10,30),null,"HOLD_EXPIRED",null,null);
        assertFalse(inconsistente.coincideCon(proceso()));
    }

    @ParameterizedTest @CsvSource({"CONFIRMACION,0", "CONFIRMACION,1", "CONFIRMACION,2", "CANCELACION,0", "CANCELACION,1", "CANCELACION,2"})
    void confirmacionYCancelacionSiguenExigiendoLosTresCampos(TipoResultadoReserva tipo,int ausente) {
        Long profesional=ausente==0 ? null : 15L;
        LocalDate fecha=ausente==1 ? null : LocalDate.of(2026,12,1);
        LocalTime hora=ausente==2 ? null : LocalTime.of(9,30);
        String estado=tipo==TipoResultadoReserva.CONFIRMACION ? "CONFIRMED" : "CANCELLED";
        assertThrows(IllegalArgumentException.class, () -> new ResultadoReserva(tipo,GRUPO,"request",8251L,
                profesional,fecha,hora,estado,null,null,null));
    }

}
