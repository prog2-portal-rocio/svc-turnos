package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.CONFIGURACION_INCOMPLETA;

public record DestinoInformacion(String groupId, String topic) {
    public DestinoInformacion {
        if (groupId == null || !groupId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || !groupId.matches(".{3,100}") || !("catedra.turnos.telefono." + groupId).equals(topic)) {
            throw new EnviarInformacionException(CONFIGURACION_INCOMPLETA);
        }
    }
}
