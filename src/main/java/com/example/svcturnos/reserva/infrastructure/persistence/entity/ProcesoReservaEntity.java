package com.example.svcturnos.reserva.infrastructure.persistence.entity;

import com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "procesos_reserva")
public class ProcesoReservaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;
    @Column(name = "profesional_id", nullable = false, updatable = false)
    private Long profesionalId;
    @Column(nullable = false, updatable = false)
    private LocalDate fecha;
    @Column(nullable = false, updatable = false)
    private LocalTime hora;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private EstadoProcesoReserva estado;
    @Column(name = "hold_id")
    private String holdId;
    @Column(name = "proceso_externo_id")
    private String procesoExternoId;
    @Column(name = "vencimiento_hold")
    private Instant vencimientoHold;
    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;
    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;
    @Version
    private Long version;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }

    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(Long value) { this.usuarioId = value; }

    public Long getProfesionalId() { return profesionalId; }
    public void setProfesionalId(Long value) { this.profesionalId = value; }

    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate value) { this.fecha = value; }

    public LocalTime getHora() { return hora; }
    public void setHora(LocalTime value) { this.hora = value; }

    public EstadoProcesoReserva getEstado() { return estado; }
    public void setEstado(EstadoProcesoReserva value) { this.estado = value; }

    public String getHoldId() { return holdId; }
    public void setHoldId(String value) { this.holdId = value; }

    public String getProcesoExternoId() { return procesoExternoId; }
    public void setProcesoExternoId(String value) { this.procesoExternoId = value; }

    public Instant getVencimientoHold() { return vencimientoHold; }
    public void setVencimientoHold(Instant value) { this.vencimientoHold = value; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant value) { this.creadoEn = value; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant value) { this.actualizadoEn = value; }

    public Long getVersion() { return version; }
    public void setVersion(Long value) { this.version = value; }
}
