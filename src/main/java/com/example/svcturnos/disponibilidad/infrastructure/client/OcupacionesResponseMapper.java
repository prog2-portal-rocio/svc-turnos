package com.example.svcturnos.disponibilidad.infrastructure.client;

import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.model.EstadoOcupacion;
import com.example.svcturnos.disponibilidad.domain.model.Ocupacion;
import com.example.svcturnos.disponibilidad.infrastructure.client.dto.OcupacionesResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import static com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Motivo.RESPUESTA_INVALIDA;

final class OcupacionesResponseMapper {
    List<Ocupacion> toDomain(OcupacionesResponse dto, Long profesionalId, LocalDate fecha) {
        if (dto == null || !Objects.equals(dto.professionalId(), profesionalId)
                || !fecha.equals(dto.from()) || !fecha.equals(dto.to()) || dto.occupancies() == null) {
            throw new ConsultaOcupacionesException(RESPUESTA_INVALIDA);
        }
        return dto.occupancies().stream().map(posicion -> toOcupacion(posicion, fecha)).toList();
    }

    private Ocupacion toOcupacion(OcupacionesResponse.Posicion dto, LocalDate fecha) {
        if (dto == null || !fecha.equals(dto.date()) || dto.status() == null) {
            throw new ConsultaOcupacionesException(RESPUESTA_INVALIDA);
        }
        try {
            return new Ocupacion(dto.date(), dto.startTime(), EstadoOcupacion.valueOf(dto.status()), dto.expiresAt());
        } catch (IllegalArgumentException _) {
            throw new ConsultaOcupacionesException(RESPUESTA_INVALIDA);
        }
    }
}
