package com.example.svcturnos.reserva.infrastructure.persistence.mapper;

import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import com.example.svcturnos.reserva.infrastructure.persistence.entity.ProcesoReservaEntity;

public class ProcesoReservaMapper {
    public ProcesoReserva toDomain(ProcesoReservaEntity e) {
        return new ProcesoReserva(e.getId(), e.getUsuarioId(), e.getProfesionalId(), e.getFecha(), e.getHora(),
                e.getEstado(), e.getHoldId(), e.getProcesoExternoId(), e.getVencimientoHold(), e.getCreadoEn(), e.getActualizadoEn(), e.getVersion(), e.getReservaId(), e.getResultadoOcurridoEn());
    }

    public ProcesoReservaEntity toEntity(ProcesoReserva p) {
        var e = new ProcesoReservaEntity();
        e.setUsuarioId(p.usuarioId());
        e.setProfesionalId(p.profesionalId());
        e.setFecha(p.fecha());
        e.setHora(p.hora());
        e.setCreadoEn(p.creadoEn());
        actualizar(e, p);
        return e;
    }

    public void actualizar(ProcesoReservaEntity e, ProcesoReserva p) {
        e.setReservaId(p.reservaId());
        e.setResultadoOcurridoEn(p.resultadoOcurridoEn());
        e.setEstado(p.estado());
        e.setHoldId(p.holdId());
        e.setProcesoExternoId(p.procesoExternoId());
        e.setVencimientoHold(p.vencimientoHold());
        e.setActualizadoEn(p.actualizadoEn());
    }
}
