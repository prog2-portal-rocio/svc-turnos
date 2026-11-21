package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.example.svcturnos.reserva.domain.exception.CancelarReservaException.Motivo.*;

@Service
public class CancelarReservaService implements CancelarReservaUseCase {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CancelarReservaService.class);
    private static final int TAMANO_PAGINA = 100;
    private static final int MAX_PAGINAS_POR_ESTADO = 10;
    private final UsuarioAutenticadoPort usuario;
    private final ConsultarReservasUseCase consultas;
    private final CancelacionReservaPersistencePort persistencia;
    private final CancelarReservaRemotaPort remoto;

    public CancelarReservaService(UsuarioAutenticadoPort usuario, ConsultarReservasUseCase consultas,
            CancelacionReservaPersistencePort persistencia, CancelarReservaRemotaPort remoto) {
        this.usuario = usuario; this.consultas = consultas; this.persistencia = persistencia; this.remoto = remoto;
    }

    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CancelacionReserva cancelar(SolicitudCancelacion solicitud) {
        Long propietario = usuario.obtenerUsuarioId();
        var anterior = persistencia.buscar(solicitud.reservationProcessId());
        if (anterior.isPresent()) {
            var intento = anterior.orElseThrow();
            if (!propietario.equals(intento.usuarioId())) { throw new CancelarReservaException(RESERVA_NO_ENCONTRADA); }
            if (intento.estado() == EstadoCancelacion.CANCELADA) { return intento; }
        }
        var reserva = buscarPropia(solicitud.reservationProcessId(), propietario);
        if (reserva.status() != EstadoReservaCentral.CONFIRMED && reserva.status() != EstadoReservaCentral.CANCELLED) {
            throw new CancelarReservaException(ESTADO_NO_PERMITIDO);
        }
        var preparacion = persistencia.preparar(propietario, reserva);
        if (!preparacion.enviar()) { return preparacion.cancelacion(); }
        CancelacionConfirmada resultado;
        try { resultado = remoto.cancelar(solicitud, reserva.reservationId()); }
        catch (CancelarReservaException error) { return registrarFallo(propietario, solicitud, error); }
        return persistencia.confirmar(propietario, resultado);
    }

    private CancelacionReserva registrarFallo(Long propietario, SolicitudCancelacion solicitud, CancelarReservaException error) {
        var estado = error.resultadoIncierto() ? EstadoCancelacion.INCIERTA : EstadoCancelacion.RECHAZADA;
        try {
            var registrado = persistencia.registrarFallo(propietario, solicitud.reservationProcessId(), estado);
            // Kafka puede haber acreditado la cancelacion mientras se esperaba el POST.
            if (registrado.estado() == EstadoCancelacion.CANCELADA) { return registrado; }
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException _) {
            // La intención SOLICITADA ya es duradera y bloquea otro POST; no divulgar causas externas.
            LOG.warn("No se pudo actualizar el diagnóstico del intento de cancelación");
        }
        throw error;
    }

    private ReservaCentral buscarPropia(String id, Long propietario) {
        try {
            for (var estado : List.of(EstadoReservaCentral.CONFIRMED, EstadoReservaCentral.CANCELLED,
                    EstadoReservaCentral.PHONE_PENDING, EstadoReservaCentral.FAILED)) {
                var encontrada = buscarEnEstado(id, propietario, estado);
                if (encontrada != null) { return encontrada; }
            }
        } catch (ConsultaReservasException error) {
            throw new CancelarReservaException(switch (error.getMotivo()) {
                case CREDENCIAL_TECNICA_RECHAZADA, CONFIGURACION_INCOMPLETA -> CREDENCIAL_TECNICA_RECHAZADA;
                case TIMEOUT -> TIMEOUT;
                default -> VERIFICACION_INCOMPLETA;
            });
        }
        throw new CancelarReservaException(RESERVA_NO_ENCONTRADA);
    }

    private ReservaCentral buscarEnEstado(String id, Long propietario, EstadoReservaCentral estado) {
        long totalAnterior = -1;
        for (int pagina = 0; pagina < MAX_PAGINAS_POR_ESTADO; pagina++) {
            var respuesta = consultas.consultarReservas(new CriteriosConsultaReservas(estado, null, null,
                    pagina, TAMANO_PAGINA, List.of()));
            validarPagina(respuesta, pagina, totalAnterior);
            totalAnterior = respuesta.totalElements();
            for (var reserva : respuesta.content()) {
                if (!PacienteReserva.identificadorExterno(propietario).equals(reserva.externalPatientId())) {
                    throw new CancelarReservaException(CORRELACION_INCONSISTENTE);
                }
                if (id.equals(reserva.reservationProcessId())) { return reserva; }
            }
            if (respuesta.content().isEmpty() || (long) (pagina + 1) * TAMANO_PAGINA >= respuesta.totalElements()) { return null; }
        }
        // El contrato solo permite listar: nunca asumir ausencia tras un recorrido incompleto.
        throw new CancelarReservaException(VERIFICACION_INCOMPLETA);
    }
    private void validarPagina(PaginaReservas respuesta, int pagina, long totalAnterior) {
        long inicio = (long) pagina * TAMANO_PAGINA;
        long esperados = Math.min(TAMANO_PAGINA, Math.max(0L, respuesta.totalElements() - inicio));
        if (respuesta.page() != pagina || respuesta.size() != TAMANO_PAGINA || respuesta.content().size() != esperados
                || (totalAnterior >= 0 && totalAnterior != respuesta.totalElements())) {
            // No inferir ausencia con páginas truncadas, vacías prematuras o totales cambiantes.
            throw new CancelarReservaException(VERIFICACION_INCOMPLETA);
        }
    }

}
