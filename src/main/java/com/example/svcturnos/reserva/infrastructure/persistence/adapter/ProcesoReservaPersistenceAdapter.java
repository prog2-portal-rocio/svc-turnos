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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class ProcesoReservaPersistenceAdapter implements ProcesoReservaPersistencePort {
    private final ProcesoReservaJpaRepository repository;
    private final ProcesoReservaMapper mapper = new ProcesoReservaMapper();

    public ProcesoReservaPersistenceAdapter(ProcesoReservaJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ProcesoReserva guardar(ProcesoReserva proceso) {
        try {
            if (proceso.id() == null) {
                return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(proceso)));
            }
            var actual = repository.findById(proceso.id())
                    .orElseThrow(() -> new ProcesoReservaNoActualizableException("El proceso no existe"));
            var anterior = mapper.toDomain(actual);
            validarSolicitudOriginal(anterior, proceso);
            validarProgreso(anterior, proceso);
            mapper.actualizar(actual, proceso);
            repository.flush();
            return mapper.toDomain(actual);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ProcesoReservaConcurrenteException();
        }
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
        if (anterior.estado() != proceso.estado()) {
            try {
                anterior.registrarHold(proceso.holdId(), proceso.procesoExternoId(), proceso.vencimientoHold());
            } catch (IllegalStateException ex) {
                throw new ProcesoReservaNoActualizableException("No se permite retroceder el estado del proceso");
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
}
