package com.example.svcturnos.shared.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NombrePersonaTest {
    @ParameterizedTest @ValueSource(ints = {2, 50, 51, 100})
    void admiteTodoElIntervaloContractual(int longitud) {
        String valor = "a".repeat(longitud);
        assertEquals(valor, new NombrePersona(valor).valor());
        assertEquals(valor, new NombrePersona("  " + valor + "  ").valor());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"A", " A ", "\tA\t", "   ", "\t", "\u2003"})
    void rechazaAusenciaBlancosYUnSoloCaracterTrasNormalizar(String valor) {
        assertThrows(IllegalArgumentException.class, () -> new NombrePersona(valor));
    }

    @Test void rechazaMasDeCienCaracteresSinTruncar() {
        String valor = "a".repeat(101);
        assertThrows(IllegalArgumentException.class, () -> new NombrePersona(valor));
    }

    @Test void conservaUnicodeEspaciosInternosYSignosSinRestriccionesAdicionales() {
        String nombre = "María  José-李 O'Neil";
        assertEquals(nombre, new NombrePersona(" \t" + nombre + "\t ").valor());
    }
}
