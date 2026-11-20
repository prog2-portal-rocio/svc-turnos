package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class InformacionAdicionalTest {
    @ParameterizedTest @CsvSource({"'+54 (9261) 555-12.34',+5492615551234", "1234567,1234567", "123456789012345,123456789012345"})
    void normalizaSegunContrato(String entrada, String esperado) { assertEquals(esperado, new Telefono(entrada).numero()); }
    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "123456", "1234567890123456", "++1234567", "abc1234567", "12+34567", "１２３４５６７", "١٢٣٤٥٦٧", "१२३४५६७", "+123456٧", "𝟙𝟚𝟛𝟜𝟝𝟞𝟟"})
    void formatoInvalidoNoSeAcepta(String entrada) {
        var error = assertThrows(EnviarInformacionException.class, () -> new Telefono(entrada));
        assertEquals(EnviarInformacionException.Motivo.TELEFONO_INVALIDO, error.getMotivo());
        assertFalse(error.getMessage().contains("abc"));
    }
    @Test void solicitudCorrelacionadaUsaElMenorPlazo() {
        assertEquals(AHORA.plusSeconds(200), SolicitudEnvioInformacion.validar(proceso(), 7L, solicitud(), AHORA));
        assertFalse(new Telefono(NUMERO).toString().contains(NUMERO));
        assertFalse(envio(EstadoEnvioInformacion.PENDIENTE).toString().contains(NUMERO));
    }
    @ParameterizedTest @EnumSource(value=EstadoProcesoReserva.class, names="ESPERANDO_TELEFONO", mode=EnumSource.Mode.EXCLUDE)
    void soloAceptacionInicialPermiteEnviar(EstadoProcesoReserva estado) {
        var p = proceso();
        boolean datos = estado.requiereDatosHold();
        var otro = new ProcesoReserva(p.id(),p.usuarioId(),p.profesionalId(),p.fecha(),p.hora(),estado,
                datos ? p.holdId() : null, datos ? p.procesoExternoId() : null, datos ? p.vencimientoHold() : null,
                p.creadoEn(),p.actualizadoEn(),p.version(),
                estado == EstadoProcesoReserva.CONFIRMADA || estado == EstadoProcesoReserva.CANCELADA ? 8251L : null,
                estado.resultadoDefinitivo() ? AHORA : null);
        var s = solicitud();
        assertThrows(EnviarInformacionException.class, () -> SolicitudEnvioInformacion.validar(otro,7L,s,AHORA));
    }
    @ParameterizedTest @ValueSource(longs={200,201,300})
    void limiteEsExclusivo(long segundos) {
        var p=proceso(); var s=solicitud(); var instante=AHORA.plusSeconds(segundos);
        var error=assertThrows(EnviarInformacionException.class, () -> SolicitudEnvioInformacion.validar(p,7L,s,instante));
        assertEquals(EnviarInformacionException.Motivo.PLAZO_VENCIDO,error.getMotivo());
    }
    @Test void propietarioYCorrelacionNoPuedenElegirse() {
        var p=proceso(); var s=solicitud();
        assertThrows(EnviarInformacionException.class, () -> SolicitudEnvioInformacion.validar(p,8L,s,AHORA));
        var incorrecta=new EventoInbox(s.id(),s.evento(),EstadoEventoReserva.PENDIENTE_CORRELACION,null,null);
        assertThrows(EnviarInformacionException.class, () -> SolicitudEnvioInformacion.validar(p,7L,incorrecta,AHORA));
        var evento=new EventoReserva("request",EventoReserva.TIPO_SOLICITUD_INFORMACION,"otro","hold",AHORA,1,s.evento().solicitud());
        var ajena=new EventoInbox(s.id(),evento,s.estado(),s.procesoLocalId(),null);
        assertThrows(EnviarInformacionException.class, () -> SolicitudEnvioInformacion.validar(p,7L,ajena,AHORA));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", "otro", "grupo con espacios"})
    void destinoDebeCorresponderAIntegracion(String grupo) {
        assertThrows(EnviarInformacionException.class, () -> new DestinoInformacion(grupo,TOPIC));
    }
    @ParameterizedTest @ValueSource(strings={"abc", "a-b", "abc-123", "123", "a-b-c", "a0-z9"})
    void destinoValidoConservaCaracteresYSeparadores(String grupo) {
        String topic = "catedra.turnos.telefono." + grupo;
        assertEquals(grupo, new DestinoInformacion(grupo, topic).groupId());
    }

    @ParameterizedTest @ValueSource(strings={"a", "ab", "-abc", "abc-", "a--b", "ABC", "ab_c", "a.b", "ab c", "ab\n", "ábc", "ab١", "ab😀"})
    void grupoInvalidoSeRechazaAunqueElTopicCoincida(String grupo) {
        String topic = "catedra.turnos.telefono." + grupo;
        var error = assertThrows(EnviarInformacionException.class, () -> new DestinoInformacion(grupo, topic));
        assertEquals(EnviarInformacionException.Motivo.CONFIGURACION_INCOMPLETA, error.getMotivo());
    }

    @ParameterizedTest @ValueSource(ints={3, 100})
    void limitesDeLongitudValidos(int longitud) {
        String grupo = "a".repeat(longitud);
        String topic = "catedra.turnos.telefono." + grupo;
        assertEquals(topic, new DestinoInformacion(grupo, topic).topic());
    }

    @ParameterizedTest @ValueSource(ints={101, 100000})
    void entradasLargasSeRechazanSinRecursion(int longitud) {
        String grupo = "a".repeat(longitud);
        String topic = "catedra.turnos.telefono." + grupo;
        assertThrows(EnviarInformacionException.class, () -> new DestinoInformacion(grupo, topic));
    }

    @Test void repeticionesMaliciosasNoAgotanLaPila() {
        String grupo = "a-".repeat(100000) + "a!";
        String topic = "catedra.turnos.telefono." + grupo;
        assertThrows(EnviarInformacionException.class, () -> new DestinoInformacion(grupo, topic));
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"", "catedra.turnos.telefono.otro", "alumnos.turnos.acciones.abc"})
    void topicDebeCoincidirExactamenteConElGrupo(String topic) {
        assertThrows(EnviarInformacionException.class, () -> new DestinoInformacion("abc", topic));
    }

}
