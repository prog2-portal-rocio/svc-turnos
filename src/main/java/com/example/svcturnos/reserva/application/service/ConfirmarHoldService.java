package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ConfirmarHoldUseCase;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException.Motivo.*;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.ESPERANDO_TELEFONO;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.HOLD_REGISTRADO;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.CONFIRMACION_RECHAZADA;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.CONFIRMACION_INCIERTA;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.HOLD_NO_RECUPERABLE;

@Service
public class ConfirmarHoldService implements ConfirmarHoldUseCase {
    private static final int LIMITE_CORRELACION = 1000;
    private final UsuarioAutenticadoPort usuario;
    private final ProcesoReservaPersistencePort procesos;
    private final ConfirmarHoldRemotoPort remoto;
    private final InboxReservasPort inbox;
    private final CorrelacionarSolicitudInformacionUseCase correlacionar;
    private final Clock clock;

    public ConfirmarHoldService(UsuarioAutenticadoPort usuario, ProcesoReservaPersistencePort procesos,
            ConfirmarHoldRemotoPort remoto, InboxReservasPort inbox,
            CorrelacionarSolicitudInformacionUseCase correlacionar, Clock clock) {
        this.usuario = usuario;
        this.procesos = procesos;
        this.remoto = remoto;
        this.inbox = inbox;
        this.correlacionar = correlacionar;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProcesoReserva confirmar(Long procesoId) {
        var paciente = usuario.obtenerPaciente();
        if (procesoId == null || procesoId <= 0) { throw new IllegalArgumentException("Identificador inválido"); }
        var proceso = buscar(procesoId);
        if (!proceso.usuarioId().equals(paciente.usuarioId())) { throw new ConfirmarHoldException(PROCESO_AJENO); }
        if (proceso.estado() == ESPERANDO_TELEFONO) { return recuperarCorrelacion(proceso); }
        if (proceso.estado() != HOLD_REGISTRADO && proceso.estado() != CONFIRMACION_RECHAZADA) {
            throw new ConfirmarHoldException(ESTADO_INCOMPATIBLE, null, procesoId);
        }
        comprobarVigencia(proceso);
        // Cada guardar finaliza su propia transacción antes de efectuar el POST.
        var intento = guardar(proceso.solicitarConfirmacion(clock.instant()));
        comprobarVigencia(intento);
        var aceptado = enviarYRegistrar(intento, paciente);
        return recuperarCorrelacion(aceptado);
    }

    private ProcesoReserva buscar(Long id) {
        try {
            return procesos.buscarPorId(id).orElseThrow(() -> new ConfirmarHoldException(PROCESO_INEXISTENTE));
        } catch (ConfirmarHoldException error) {
            throw error;
        } catch (RuntimeException _) {
            // La lectura falló antes de verificar propiedad: no incluir identificadores del proceso.
            throw new ConfirmarHoldException(PERSISTENCIA_FALLIDA);
        }
    }

    private void comprobarVigencia(ProcesoReserva proceso) {
        if (!proceso.vencimientoHold().isAfter(clock.instant())) {
            guardar(proceso.registrarResultadoConfirmacion(EstadoProcesoReserva.HOLD_VENCIDO, clock.instant()));
            throw new ConfirmarHoldException(HOLD_VENCIDO, null, proceso.id());
        }
    }

    private ProcesoReserva enviarYRegistrar(ProcesoReserva intento, PacienteReserva paciente) {
        ProcesoReserva aceptado;
        try {
            var respuesta = remoto.confirmar(intento.holdId(), paciente);
            if (respuesta == null || !intento.procesoExternoId().equals(respuesta.reservationProcessId())) {
                throw new ConfirmarHoldException(RESULTADO_INCIERTO);
            }
            aceptado = intento.registrarConfirmacion(respuesta.expiresAt(), clock.instant());
        } catch (ConfirmarHoldException error) {
            registrarFallo(intento, error);
            throw error.conProceso(intento.id());
        } catch (RuntimeException _) {
            var error = new ConfirmarHoldException(RESULTADO_INCIERTO);
            registrarFallo(intento, error);
            throw error.conProceso(intento.id());
        }
        // Si falla esta escritura, el marcador CONFIRMACION_SOLICITADA impide otro POST.
        return guardar(aceptado);
    }

    private void registrarFallo(ProcesoReserva intento, ConfirmarHoldException error) {
        var estado = switch (error.getMotivo()) {
            case RESULTADO_INCIERTO, PROCESO_YA_CONFIRMADO -> CONFIRMACION_INCIERTA;
            case HOLD_VENCIDO -> EstadoProcesoReserva.HOLD_VENCIDO;
            case HOLD_NO_ENCONTRADO -> HOLD_NO_RECUPERABLE;
            // El intento no fue aceptado; no implica rechazo definitivo de la reserva.
            case CONFIGURACION_INCOMPLETA, CREDENCIAL_TECNICA_RECHAZADA, SOLICITUD_RECHAZADA -> CONFIRMACION_RECHAZADA;
            default -> CONFIRMACION_INCIERTA;
        };
        guardar(intento.registrarResultadoConfirmacion(estado, clock.instant()));
    }

    private ProcesoReserva recuperarCorrelacion(ProcesoReserva proceso) {
        try {
            for (var id : inbox.buscarPendientes(LIMITE_CORRELACION)) {
                var pendiente = inbox.buscarPorId(id);
                if (pendiente.isPresent() && corresponde(pendiente.get(), proceso)) { correlacionar.correlacionar(id); }
            }
        } catch (RuntimeException _) {
            // El 202 ya quedó persistido. Un nuevo intento sólo recuperará la correlación local.
            throw new ConfirmarHoldException(CORRELACION_PENDIENTE, null, proceso.id());
        }
        // Conservar la aceptación observada, sin presentarla como un plazo todavía utilizable.
        if (!proceso.esperaTelefonoVigente(clock.instant())) {
            throw new ConfirmarHoldException(HOLD_VENCIDO, null, proceso.id());
        }
        return proceso;
    }

    private boolean corresponde(EventoInbox pendiente, ProcesoReserva proceso) {
        var evento = pendiente.evento();
        return evento != null && evento.esSolicitudInformacion()
                && proceso.procesoExternoId().equals(evento.reservationProcessId());
    }

    private ProcesoReserva guardar(ProcesoReserva proceso) {
        try {
            return procesos.guardar(proceso);
        } catch (ProcesoReservaConcurrenteException _) {
            throw new ConfirmarHoldException(ESTADO_INCOMPATIBLE, null, proceso.id());
        } catch (RuntimeException _) {
            throw new ConfirmarHoldException(PERSISTENCIA_FALLIDA, null, proceso.id());
        }
    }
}
