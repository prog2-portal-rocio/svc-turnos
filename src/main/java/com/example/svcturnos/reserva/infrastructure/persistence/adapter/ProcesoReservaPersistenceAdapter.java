package com.example.svcturnos.reserva.infrastructure.persistence.adapter;

import com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException;
import com.example.svcturnos.reserva.domain.exception.ProcesoReservaNoActualizableException;
import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import com.example.svcturnos.reserva.domain.ports.out.ProcesoReservaPersistencePort;
import com.example.svcturnos.reserva.infrastructure.persistence.mapper.ProcesoReservaMapper;
import com.example.svcturnos.reserva.infrastructure.persistence.repository.ProcesoReservaJpaRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import java.time.Clock;
import com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProcesoReservaPersistenceAdapter implements ProcesoReservaPersistencePort {
    private final ProcesoReservaJpaRepository repository;
    private final ProcesoReservaMapper mapper = new ProcesoReservaMapper();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcesoReservaPersistenceAdapter(ProcesoReservaJpaRepository repository, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcesoReserva guardar(ProcesoReserva proceso) {
        try {
            if (proceso.id() == null) {
                if (proceso.estado() == EstadoProcesoReserva.HOLD_SOLICITADO) {
                    verificarSolicitudEquivalente(proceso);
                }
                return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(proceso)));
            }
            var actual = repository.findById(proceso.id())
                    .orElseThrow(() -> new ProcesoReservaNoActualizableException("El proceso no existe"));
            var anterior = mapper.toDomain(actual);
            validarSolicitudOriginal(anterior, proceso);
            validarProgreso(anterior, proceso);
            if (anterior.estado() != EstadoProcesoReserva.HOLD_SOLICITADO
                    && proceso.estado() == EstadoProcesoReserva.HOLD_SOLICITADO) {
                verificarSolicitudEquivalente(proceso);
            }
            mapper.actualizar(actual, proceso);
            repository.flush();
            return mapper.toDomain(actual);
        } catch (ObjectOptimisticLockingFailureException _) {
            throw new ProcesoReservaConcurrenteException();
        }
    }

    private void verificarSolicitudEquivalente(ProcesoReserva proceso) {
        // JDBC y JPA comparten esta transacción. El lock se libera al commit/rollback, antes del HTTP.
        String clave = "hold:" + proceso.usuarioId() + ":" + proceso.profesionalId() + ":" + proceso.fecha() + ":" + proceso.hora();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, clave);
                statement.execute();
            }
            return null;
        });
        var ahora = clock.instant();
        repository.findByUsuarioIdAndProfesionalIdAndFechaAndHoraOrderByIdDesc(
                        proceso.usuarioId(), proceso.profesionalId(), proceso.fecha(), proceso.hora()).stream()
                .map(mapper::toDomain).filter(anterior -> anterior.impideNuevoHold(ahora)).findFirst()
                .ifPresent(anterior -> {
                    throw new CrearHoldException(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, null, anterior.id());
                });
    }

    private void validarSolicitudOriginal(ProcesoReserva anterior, ProcesoReserva proceso) {
        if (!Objects.equals(anterior.usuarioId(), proceso.usuarioId())
                || !Objects.equals(anterior.profesionalId(), proceso.profesionalId())
                || !anterior.fecha().equals(proceso.fecha()) || !anterior.hora().equals(proceso.hora())
                || !anterior.creadoEn().equals(proceso.creadoEn())) {
            throw new ProcesoReservaNoActualizableException("No se permite modificar propietario ni solicitud original");
        }
        if (!Objects.equals(anterior.version(), proceso.version())) {
            throw new ProcesoReservaConcurrenteException();
        }
    }

    private void validarProgreso(ProcesoReserva anterior, ProcesoReserva proceso) {
        if (proceso.actualizadoEn().isBefore(anterior.actualizadoEn())) {
            throw new ProcesoReservaNoActualizableException("La auditoría no puede retroceder");
        }
        if (anterior.estado() != proceso.estado() || !Objects.equals(anterior.resultadoOcurridoEn(), proceso.resultadoOcurridoEn())) {
            if (!anterior.admiteProgreso(proceso)) {
                throw new ProcesoReservaNoActualizableException("Transición de hold no permitida");
            }
        } else if (!anterior.equals(proceso)) {
            throw new ProcesoReservaNoActualizableException("El progreso registrado no puede reemplazarse");
        }
    }

    @Override
    public Optional<ProcesoReserva> buscarPorId(Long id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<ProcesoReserva> buscarPorUsuario(Long usuarioId) {
        return repository.findByUsuarioIdOrderByIdAsc(usuarioId).stream().map(mapper::toDomain).toList();
    }
    @Override
    public List<ProcesoReserva> buscarPorProcesoExternoId(String procesoExternoId) {
        return repository.findByProcesoExternoIdOrderByIdAsc(procesoExternoId).stream().map(mapper::toDomain).toList();
    }
}
