package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.EnviarInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.*;

@Service
public class EnviarInformacionService implements EnviarInformacionUseCase {
    private final UsuarioAutenticadoPort usuario;
    private final ProcesoReservaPersistencePort procesos;
    private final InboxReservasPort inbox;
    private final OutboxInformacionPort outbox;
    private final boolean habilitada;
    private final String grupo;
    private final String topic;
    public EnviarInformacionService(UsuarioAutenticadoPort usuario, ProcesoReservaPersistencePort procesos,
            InboxReservasPort inbox, OutboxInformacionPort outbox,
            @Value("${turnos.kafka.phone-publication-enabled:false}") boolean habilitada,
            @Value("${turnos.kafka.integration-group-id:}") String grupo, @Value("${turnos.kafka.phone-topic:}") String topic) {
        this.usuario = usuario; this.procesos = procesos; this.inbox = inbox; this.outbox = outbox; this.grupo = grupo; this.topic = topic; this.habilitada = habilitada;
    }
    @Override public EnvioInformacion enviar(Long procesoId, String numero) {
        Long propietario = usuario.obtenerUsuarioId();
        if (procesoId == null || procesoId <= 0) { throw new IllegalArgumentException("Identificador inválido"); }
        var telefono = new Telefono(numero);
        if (!habilitada) { throw new EnviarInformacionException(CONFIGURACION_INCOMPLETA); }
        var destino = new DestinoInformacion(grupo, topic);
        try {
            var proceso = procesos.buscarPorId(procesoId).orElseThrow(() -> new EnviarInformacionException(PROCESO_INEXISTENTE));
            if (!proceso.usuarioId().equals(propietario)) { throw new EnviarInformacionException(PROCESO_AJENO); }
            var solicitudes = inbox.buscarSolicitudesPorProceso(procesoId);
            if (solicitudes.size() != 1) { throw new EnviarInformacionException(SOLICITUD_NO_DISPONIBLE); }
            return outbox.registrar(procesoId, propietario, solicitudes.getFirst().id(), telefono, destino);
        } catch (EnviarInformacionException error) { throw error; }
        catch (RuntimeException _) { throw new EnviarInformacionException(PERSISTENCIA_FALLIDA); }
    }
}
