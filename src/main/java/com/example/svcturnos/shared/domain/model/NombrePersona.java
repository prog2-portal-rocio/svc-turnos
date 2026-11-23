package com.example.svcturnos.shared.domain.model;

/** Nombre o apellido normalizado, compartido por el registro y los datos del paciente. */
public record NombrePersona(String valor) {
    public static final int LONGITUD_MINIMA = 2;
    public static final int LONGITUD_MAXIMA = 100;

    public NombrePersona {
        valor = normalizar(valor);
        if (valor == null || valor.isBlank() || valor.length() < LONGITUD_MINIMA || valor.length() > LONGITUD_MAXIMA) {
            throw new IllegalArgumentException("Nombre y apellido deben tener entre 2 y 100 caracteres después de quitar espacios exteriores");
        }
    }

    /** Mantiene el trim existente y permite que Bean Validation trate los valores nulos. */
    public static String normalizar(String valor) {
        return valor == null ? null : valor.trim();
    }
}
