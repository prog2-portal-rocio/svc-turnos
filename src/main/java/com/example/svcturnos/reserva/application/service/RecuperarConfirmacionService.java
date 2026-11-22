package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException;
import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import com.example.svcturnos.reserva.domain.ports.out.ProcesoReservaPersistencePort;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Recuperación local con evidencia durable; no realiza llamadas externas ni reintenta POST. */
@Service
public class RecuperarConfirmacionService {
    private final InboxReservasPort inbox;
    private final ProcesoReservaPersistencePort procesos;
    private final Clock clock;

    public RecuperarConfirmacionService(InboxReservasPort inbox, ProcesoReservaPersistencePort procesos, Clock clock) {
        this.inbox = inbox;
        this.procesos = procesos;
        this.clock = clock;
    }

    public ProcesoReserva recuperar(ProcesoReserva proceso) {
        if (!proceso.confirmacionPendienteDeEvidencia()) { return proceso; }
        var solicitudes = inbox.buscarSolicitudesPorProceso(proceso.id());
        if (solicitudes.size() != 1) { return proceso; }
        var recuperado = proceso.recuperarConfirmacion(solicitudes.getFirst(), clock.instant());
        if (recuperado == proceso) { return proceso; }
        try {
            return procesos.guardar(recuperado);
        } catch (ProcesoReservaConcurrenteException _) {
            // Otro actor pudo aplicar un resultado definitivo. Nunca sobrescribirlo ni repetir la operación.
            return procesos.buscarPorId(proceso.id()).orElseThrow(ProcesoReservaConcurrenteException::new);
        }
    }
}
