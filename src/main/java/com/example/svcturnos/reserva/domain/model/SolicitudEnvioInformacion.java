package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import java.time.Instant;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.*;

public final class SolicitudEnvioInformacion {
    private SolicitudEnvioInformacion() { }
    public static Instant validar(ProcesoReserva proceso, Long propietario, EventoInbox solicitud, Instant ahora) {
        if (!proceso.usuarioId().equals(propietario)) { throw new EnviarInformacionException(PROCESO_AJENO); }
        if (proceso.estado() != EstadoProcesoReserva.ESPERANDO_TELEFONO) { throw new EnviarInformacionException(ESTADO_INCOMPATIBLE); }
        if (solicitud == null || solicitud.estado() != EstadoEventoReserva.SOLICITUD_REGISTRADA
                || !proceso.id().equals(solicitud.procesoLocalId()) || solicitud.originalId() != null) {
            throw new EnviarInformacionException(SOLICITUD_NO_DISPONIBLE);
        }
        var evento = solicitud.evento();
        if (evento == null || !evento.esSolicitudInformacion() || !"PHONE_NUMBER".equals(evento.solicitud().requiredField())
                || !proceso.procesoExternoId().equals(evento.reservationProcessId()) || !proceso.holdId().equals(evento.holdId())) {
            throw new EnviarInformacionException(CORRELACION_INVALIDA);
        }
        Instant limite = evento.solicitud().expiresAt().isBefore(proceso.vencimientoHold())
                ? evento.solicitud().expiresAt() : proceso.vencimientoHold();
        if (!limite.isAfter(ahora)) { throw new EnviarInformacionException(PLAZO_VENCIDO); }
        return limite;
    }
}
