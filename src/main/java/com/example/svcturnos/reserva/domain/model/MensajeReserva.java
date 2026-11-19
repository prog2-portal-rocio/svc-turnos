package com.example.svcturnos.reserva.domain.model;

/** Datos de transporte originales, sin dependencias del cliente Kafka ni interpretación del payload. */
public final class MensajeReserva {
    private final String topic;
    private final int particion;
    private final long offset;
    private final byte[] clave;
    private final byte[] contenido;

    public MensajeReserva(String topic, int particion, long offset, byte[] clave, byte[] contenido) {
        if (topic == null || topic.isBlank() || particion < 0 || offset < 0) {
            throw new IllegalArgumentException("Coordenadas del mensaje inválidas");
        }
        this.topic = topic;
        this.particion = particion;
        this.offset = offset;
        this.clave = copiar(clave);
        this.contenido = copiar(contenido);
    }

    private static byte[] copiar(byte[] dato) { return dato == null ? null : dato.clone(); }
    public String topic() { return topic; }
    public int particion() { return particion; }
    public long offset() { return offset; }
    public byte[] clave() { return copiar(clave); }
    public byte[] contenido() { return copiar(contenido); }
}
