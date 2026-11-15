package com.example.svcturnos.catalogo.infrastructure.client;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.model.Categoria;
import com.example.svcturnos.catalogo.domain.model.HorarioSemanal;
import com.example.svcturnos.catalogo.domain.model.Profesional;
import com.example.svcturnos.catalogo.infrastructure.client.dto.HorarioCatalogoResponse;
import com.example.svcturnos.catalogo.infrastructure.client.dto.ProfesionalCatalogoResponse;

import java.util.List;
import java.util.Objects;

import static com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException.Motivo.RESPUESTA_INVALIDA;

final class CatalogoResponseMapper {

    Profesional toProfesional(ProfesionalCatalogoResponse dto, Long idSolicitado) {
        if (dto == null || !Objects.equals(idSolicitado, dto.id()) || dto.categoryId() == null
                || dto.firstName() == null || dto.lastName() == null || dto.enabled() == null) {
            throw new ConsultaCatalogoException(RESPUESTA_INVALIDA);
        }
        var categoria = dto.category();
        if (categoria == null || !Objects.equals(dto.categoryId(), categoria.id())
                || categoria.name() == null || categoria.enabled() == null) {
            throw new ConsultaCatalogoException(RESPUESTA_INVALIDA);
        }
        return new Profesional(dto.id(), dto.categoryId(), dto.firstName(), dto.lastName(), dto.enabled(),
                dto.createdAt(), dto.updatedAt(), new Categoria(categoria.id(), categoria.name(),
                categoria.description(), categoria.enabled(), categoria.createdAt(), categoria.updatedAt()));
    }

    List<HorarioSemanal> toHorarios(List<HorarioCatalogoResponse> dtos, Long profesionalId) {
        if (dtos == null) {
            throw new ConsultaCatalogoException(RESPUESTA_INVALIDA);
        }
        return dtos.stream().map(dto -> toHorario(dto, profesionalId)).toList();
    }

    private HorarioSemanal toHorario(HorarioCatalogoResponse dto, Long profesionalId) {
        if (dto == null || dto.id() == null || !Objects.equals(profesionalId, dto.professionalId())
                || dto.dayOfWeek() == null || dto.startTime() == null || dto.endTime() == null
                || dto.slotDurationMinutes() == null || dto.enabled() == null) {
            throw new ConsultaCatalogoException(RESPUESTA_INVALIDA);
        }
        return new HorarioSemanal(dto.id(), dto.professionalId(), dto.dayOfWeek(), dto.startTime(), dto.endTime(),
                dto.slotDurationMinutes(), dto.enabled(), dto.createdAt(), dto.updatedAt());
    }
}
