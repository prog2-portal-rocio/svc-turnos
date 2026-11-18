package com.example.svcturnos.reserva.infrastructure.persistence.repository;

import com.example.svcturnos.reserva.infrastructure.persistence.entity.ProcesoReservaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;

public interface ProcesoReservaJpaRepository extends JpaRepository<ProcesoReservaEntity, Long> {
    List<ProcesoReservaEntity> findByUsuarioIdAndProfesionalIdAndFechaAndHoraOrderByIdDesc(
            Long usuarioId, Long profesionalId, LocalDate fecha, LocalTime hora);
    List<ProcesoReservaEntity> findByUsuarioIdOrderByIdAsc(Long usuarioId);
}
