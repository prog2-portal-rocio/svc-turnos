package com.example.svcturnos.reserva.domain.model;

import java.util.Arrays;
import java.util.Objects;

/** Datos originales con copias defensivas e igualdad por contenido, sin dependencias Kafka. */
public record MensajeReserva(String topic, int particion, long offset, byte[] clave, byte[] contenido) {
    public MensajeReserva {
        if (topic == null || topic.isBlank() || particion < 0 || offset < 0) {
            throw new IllegalArgumentException("Coordenadas del mensaje inválidas");
        }
        clave = copiar(clave);
        contenido = copiar(contenido);
    }

    private static byte[] copiar(byte[] dato) { return dato == null ? null : dato.clone(); }

    @Override
    public byte[] clave() { return copiar(clave); }

    @Override
    public byte[] contenido() { return copiar(contenido); }

    @Override
    public boolean equals(Object objeto) {
        if (this == objeto) { return true; }
        if (!(objeto instanceof MensajeReserva(var otroTopic, var otraParticion, var otroOffset,
                                               var otraClave, var otroContenido))) { return false; }
        return particion == otraParticion && offset == otroOffset && topic.equals(otroTopic)
                && Arrays.equals(clave, otraClave) && Arrays.equals(contenido, otroContenido);
    }

    @Override
    public int hashCode() {
        int resultado = Objects.hash(topic, particion, offset);
        resultado = 31 * resultado + Arrays.hashCode(clave);
        return 31 * resultado + Arrays.hashCode(contenido);
    }

    @Override
    public String toString() {
        return "MensajeReserva[topic=" + topic + ", particion=" + particion + ", offset=" + offset + "]";
    }
}
