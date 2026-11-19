package com.example.svcturnos.reserva.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MensajeReservaTest {
    @Test
    void conservaCoordenadasYCopiaAmbosArreglosEnConstructorYAccesores() {
        byte[] clave = {1, 2};
        byte[] contenido = {3, 4};
        var mensaje = new MensajeReserva("acciones", 1, 5L, clave, contenido);
        int hashOriginal = mensaje.hashCode();
        clave[0] = 9;
        contenido[0] = 9;
        byte[] claveDevuelta = mensaje.clave();
        byte[] contenidoDevuelto = mensaje.contenido();
        claveDevuelta[1] = 9;
        contenidoDevuelto[1] = 9;
        assertEquals("acciones", mensaje.topic());
        assertEquals(1, mensaje.particion());
        assertEquals(5L, mensaje.offset());
        assertArrayEquals(new byte[]{1, 2}, mensaje.clave());
        assertArrayEquals(new byte[]{3, 4}, mensaje.contenido());
        assertEquals(hashOriginal, mensaje.hashCode());
    }

    @Test
    void igualdadYHashComparanContenidoAunqueSeanArreglosDistintos() {
        var primero = new MensajeReserva("acciones", 0, 5L, new byte[]{1}, new byte[]{2});
        var segundo = new MensajeReserva("acciones", 0, 5L, new byte[]{1}, new byte[]{2});
        assertEquals(primero, segundo);
        assertEquals(segundo, primero);
        assertEquals(primero, primero);
        assertEquals(primero.hashCode(), segundo.hashCode());
        var conjunto = new HashSet<>(List.of(primero));
        primero.clave()[0] = 9;
        primero.contenido()[0] = 9;
        assertTrue(conjunto.contains(segundo));
    }

    @Test
    void cadaComponenteIntervieneEnLaIgualdad() {
        var original = new MensajeReserva("acciones", 0, 5L, new byte[]{1}, new byte[]{2});
        assertNotEquals(original, new MensajeReserva("otro", 0, 5L, new byte[]{1}, new byte[]{2}));
        assertNotEquals(original, new MensajeReserva("acciones", 1, 5L, new byte[]{1}, new byte[]{2}));
        assertNotEquals(original, new MensajeReserva("acciones", 0, 6L, new byte[]{1}, new byte[]{2}));
        assertNotEquals(original, new MensajeReserva("acciones", 0, 5L, new byte[]{9}, new byte[]{2}));
        assertNotEquals(original, new MensajeReserva("acciones", 0, 5L, new byte[]{1}, new byte[]{9}));
        assertNotEquals(original, null);
        assertNotEquals(original, new Object());
    }

    @Test
    void conservaClaveNulaYTombstoneSinConfundirNuloConVacio() {
        var tombstone = new MensajeReserva("acciones", 0, 0, null, null);
        var igual = new MensajeReserva("acciones", 0, 0, null, null);
        assertNull(tombstone.clave());
        assertNull(tombstone.contenido());
        assertEquals(tombstone, igual);
        assertEquals(tombstone.hashCode(), igual.hashCode());
        assertNotEquals(tombstone, new MensajeReserva("acciones", 0, 0, new byte[0], null));
        assertNotEquals(tombstone, new MensajeReserva("acciones", 0, 0, null, new byte[0]));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void topicSigueSiendoObligatorio(String topic) {
        assertThrows(IllegalArgumentException.class, () -> new MensajeReserva(topic, 0, 0, null, null));
    }

    @Test
    void coordenadasNegativasSiguenSiendoInvalidas() {
        assertThrows(IllegalArgumentException.class, () -> new MensajeReserva("acciones", -1, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MensajeReserva("acciones", 0, -1, null, null));
    }

    @Test
    void representacionNoExponeClaveNiPayload() {
        var mensaje = new MensajeReserva("acciones", 0, 0,
                "clave-de-prueba".getBytes(StandardCharsets.UTF_8), "payload-de-prueba".getBytes(StandardCharsets.UTF_8));
        assertEquals("MensajeReserva[topic=acciones, particion=0, offset=0]", mensaje.toString());
    }
}
