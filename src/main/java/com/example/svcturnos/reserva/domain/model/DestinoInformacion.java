package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.CONFIGURACION_INCOMPLETA;

public record DestinoInformacion(String groupId, String topic) {
    public DestinoInformacion {
        if (!grupoValido(groupId) || !("catedra.turnos.telefono." + groupId).equals(topic)) {
            throw new EnviarInformacionException(CONFIGURACION_INCOMPLETA);
        }
    }

    private static boolean grupoValido(String grupo) {
        if (grupo == null || grupo.length() < 3 || grupo.length() > 100) { return false; }
        boolean guionAnterior = true;
        for (int i = 0; i < grupo.length(); i++) {
            char caracter = grupo.charAt(i);
            if (caracter == '-') {
                if (guionAnterior) { return false; }
                guionAnterior = true;
            } else {
                if (!alfanumericoAscii(caracter)) { return false; }
                guionAnterior = false;
            }
        }
        return !guionAnterior;
    }

    private static boolean alfanumericoAscii(char caracter) {
        return (caracter >= 'a' && caracter <= 'z') || (caracter >= '0' && caracter <= '9');
    }
}
