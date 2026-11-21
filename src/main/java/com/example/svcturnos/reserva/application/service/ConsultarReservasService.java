package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ConsultarReservasUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.stereotype.Service;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Objects;
import static com.example.svcturnos.reserva.domain.exception.ConsultaReservasException.Motivo.*;

@Service
public class ConsultarReservasService implements ConsultarReservasUseCase {
    private final UsuarioAutenticadoPort usuario;
    private final ProcesoReservaPersistencePort procesos;
    private final ConsultarReservasRemotasPort remoto;

    public ConsultarReservasService(UsuarioAutenticadoPort usuario, ProcesoReservaPersistencePort procesos,
                                    ConsultarReservasRemotasPort remoto) {
        this.usuario = usuario;
        this.procesos = procesos;
        this.remoto = remoto;
    }

    @Override public List<ProcesoReserva> consultarProcesos() {
        return procesos.buscarPorUsuario(usuario.obtenerUsuarioId());
    }

    @Override public ProcesoReserva consultarProceso(Long procesoId) {
        Long propietario = usuario.obtenerUsuarioId();
        if (procesoId == null || procesoId <= 0) { throw new IllegalArgumentException("Identificador invalido"); }
        return procesos.buscarPorId(procesoId).filter(proceso -> propietario.equals(proceso.usuarioId()))
                .orElseThrow(() -> new ConsultaReservasException(PROCESO_NO_ENCONTRADO));
    }

    @Override public PaginaReservas consultarReservas(CriteriosConsultaReservas criterios) {
        Objects.requireNonNull(criterios, "Criterios obligatorios");
        Long propietario = usuario.obtenerUsuarioId();
        String paciente = PacienteReserva.identificadorExterno(propietario);
        // La identidad central es suficiente aun cuando no exista un proceso local.
        var pagina = remoto.consultar(paciente, criterios);
        if (pagina.content().stream().anyMatch(reserva -> !paciente.equals(reserva.externalPatientId()))) {
            throw new ConsultaReservasException(RESPUESTA_INVALIDA);
        }
        if (pagina.content().isEmpty()) { return pagina; }
        // Una sola consulta de lectura, despues del HTTP y sin transaccion distribuida.
        var ids = pagina.content().stream().map(ReservaCentral::reservationProcessId).distinct().toList();
        var asociados = procesos.buscarPorProcesosExternos(ids).stream()
                .collect(Collectors.groupingBy(ProcesoReserva::procesoExternoId));
        for (var reserva : pagina.content()) {
            var candidatos = asociados.getOrDefault(reserva.reservationProcessId(), List.of());
            if (candidatos.size() > 1) { throw new ConsultaReservasException(RESPUESTA_INVALIDA); }
            if (!candidatos.isEmpty()) { validarCorrespondencia(paciente, candidatos.getFirst(), reserva); }
        }
        return pagina;
    }

    private void validarCorrespondencia(String paciente, ProcesoReserva proceso, ReservaCentral reserva) {
        if (!paciente.equals(PacienteReserva.identificadorExterno(proceso.usuarioId()))
                || !proceso.profesionalId().equals(reserva.professionalId())
                || !proceso.fecha().equals(reserva.appointmentDate()) || !proceso.hora().equals(reserva.startTime())
                || (proceso.reservaId() != null && !proceso.reservaId().equals(reserva.reservationId()))) {
            // No filtrar silenciosamente: alteraria el contenido y total de la pagina.
            throw new ConsultaReservasException(RESPUESTA_INVALIDA);
        }
    }
}
