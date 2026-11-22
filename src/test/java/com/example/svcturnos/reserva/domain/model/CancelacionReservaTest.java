package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CancelacionReservaTest {
    static final Instant AHORA=Instant.parse("2026-11-21T10:00:00Z");
    @Test void motivoOpcionalRespetaLimiteYNoSeExponeEnToString() {
        assertNull(new SolicitudCancelacion("externo",null).reason());
        String limite="a".repeat(500);
        assertEquals(limite,new SolicitudCancelacion("externo",limite).reason());
        assertFalse(new SolicitudCancelacion("externo","dato privado").toString().contains("dato privado"));
        String excesivo="a".repeat(501);
        assertThrows(IllegalArgumentException.class,()->new SolicitudCancelacion("externo",excesivo));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t"})
    void identificadorExternoEsObligatorio(String id) {
        assertThrows(IllegalArgumentException.class,()->new SolicitudCancelacion(id,null));
        assertThrows(IllegalArgumentException.class,()->new CancelacionConfirmada(8251L,id,AHORA));
    }
    @Test void identidadDeReservaYPropietarioDebeSerValida() {
        assertThrows(IllegalArgumentException.class,()->new CancelacionConfirmada(0L,"externo",AHORA));
        assertThrows(IllegalArgumentException.class,()->new CancelacionReserva("externo",0L,8251L,
                EstadoCancelacion.SOLICITADA,AHORA,AHORA,null,false));
    }
    @Test void intentoInciertoNoPuedeContenerEvidenciaDefinitiva() {
        assertThrows(IllegalArgumentException.class,()->new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.INCIERTA,AHORA,AHORA,AHORA,false));
        assertThrows(IllegalArgumentException.class,()->new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.SOLICITADA,AHORA,AHORA,null,true));
    }
    @Test void fechaDeActualizacionNoPuedeRetroceder() {
        Instant anterior=AHORA.minusSeconds(1);
        assertThrows(IllegalArgumentException.class,()->new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.SOLICITADA,AHORA,anterior,null,false));
    }
}
