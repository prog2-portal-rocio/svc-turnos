package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.TELEFONO_INVALIDO;

public record Telefono(String numero) {
    public Telefono {
        if (numero == null) { throw new EnviarInformacionException(TELEFONO_INVALIDO); }
        numero = numero.replaceAll("[ \t\r\n().-]", "");
        if (!numero.matches("\\+?[0-9]{7,15}")) { throw new EnviarInformacionException(TELEFONO_INVALIDO); }
    }
    @Override public String toString() { return "Telefono[protegido]"; }
}
