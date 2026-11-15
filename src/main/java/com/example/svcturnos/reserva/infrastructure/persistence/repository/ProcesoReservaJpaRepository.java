package com.example.svcturnos.reserva.infrastructure.persistence.repository;

import com.example.svcturnos.reserva.infrastructure.persistence.entity.ProcesoReservaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProcesoReservaJpaRepository extends JpaRepository<ProcesoReservaEntity, Long> {
    List<ProcesoReservaEntity> findByUsuarioIdOrderByIdAsc(Long usuarioId);
}
