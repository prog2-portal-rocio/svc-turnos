package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import com.example.svcturnos.reserva.domain.model.HoldCreado;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;
import com.example.svcturnos.reserva.domain.ports.in.CrearHoldUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.example.svcturnos.reserva.domain.exception.CrearHoldException.Motivo.*;

@Service
public class CrearHoldService implements CrearHoldUseCase {
    private final UsuarioAutenticadoPort usuario;
    private final ConsultarDisponibilidadUseCase disponibilidad;
    private final CrearHoldRemotoPort remoto;
    private final ProcesoReservaPersistencePort procesos;

    public CrearHoldService(UsuarioAutenticadoPort usuario, ConsultarDisponibilidadUseCase disponibilidad,
                            CrearHoldRemotoPort remoto, ProcesoReservaPersistencePort procesos) {
        this.usuario = usuario;
        this.disponibilidad = disponibilidad;
        this.remoto = remoto;
        this.procesos = procesos;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProcesoReserva crear(SolicitudHold solicitud) {
        Long propietario = usuario.obtenerUsuarioId();
        if (solicitud == null) { throw new IllegalArgumentException("La solicitud es obligatoria"); }
        boolean disponible = disponibilidad.consultar(solicitud.professionalId(), solicitud.date()).stream()
                .anyMatch(bloque -> bloque.fecha().equals(solicitud.date()) && bloque.inicio().equals(solicitud.startTime()));
        if (!disponible) { throw new CrearHoldException(SLOT_NO_DISPONIBLE); }
        var nuevo = ProcesoReserva.crear(propietario, solicitud.professionalId(), solicitud.date(), solicitud.startTime());
        // Debe quedar comprometido antes del POST: incluso un reinicio aquí deja un intento incierto recuperable.
        var intento = guardar(nuevo.solicitarHold());
        return enviarYRegistrar(solicitud, propietario, intento);
    }

    private ProcesoReserva enviarYRegistrar(SolicitudHold solicitud, Long propietario, ProcesoReserva intento) {
        HoldCreado hold;
        try {
            hold = remoto.crear(solicitud, "user-" + propietario);
        } catch (CrearHoldException error) {
            if (error.getMotivo() != RESULTADO_INCIERTO) {
                guardar(intento.rechazarHold());
            }
            throw error.conProceso(intento.id());
        } catch (RuntimeException _) {
            throw new CrearHoldException(RESULTADO_INCIERTO, null, intento.id());
        }
        if (hold == null) { throw new CrearHoldException(RESULTADO_INCIERTO, null, intento.id()); }
        // Si esta escritura falla, HOLD_SOLICITADO permanece; nunca reenviar automáticamente el POST.
        return guardar(intento.registrarHold(hold.holdId(), hold.procesoExternoId(), hold.expiresAt()));
    }

    private ProcesoReserva guardar(ProcesoReserva proceso) {
        try {
            return procesos.guardar(proceso);
        } catch (CrearHoldException error) {
            throw error;
        } catch (RuntimeException _) {
            throw new CrearHoldException(PERSISTENCIA_FALLIDA, null, proceso.id());
        }
    }
}
