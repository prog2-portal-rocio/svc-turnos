
package com.example.svcturnos.usuario.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class Argon2idPasswordHasherAdapterTest {

    private final Argon2idPasswordHasherAdapter hasher =
            new Argon2idPasswordHasherAdapter();

    @Test
    void generaArgon2idConLosParametrosExplicitosYSinPreHash() {
        String password = "Clave de prueba ñ🔑";
        String hash = hasher.hash(password);

        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        String[] partes = hash.split("\\$");
        assertEquals(16, Base64.getDecoder().decode(partes[4]).length);
        assertEquals(32, Base64.getDecoder().decode(partes[5]).length);
        assertTrue(hash.length() <= 255);
        assertTrue(new Argon2PasswordEncoder(16, 32, 1, 19456, 2).matches(password, hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Contraseña con ñ y 漢字 🔑", "  contraseña sin recortar  "})
    void verificaLaContrasenaUnicodeOriginalSinTransformaciones(String password) {
        assertTrue(hasher.matches(password, hasher.hash(password)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ñ"})
    void noTruncaContrasenasDeCienCaracteresNiElContenidoPosteriorAlByte72(String caracter) {
        String password = caracter.repeat(100);
        String hash = hasher.hash(password);

        assertTrue(hasher.matches(password, hash));
        assertFalse(hasher.matches(caracter.repeat(99) + "b", hash));
        assertFalse(hasher.matches(caracter.repeat(72), hash));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"$2a$12$hash-anterior", "$2b$12$hash-anterior", "$argon2i$hash", "texto-plano"})
    void rechazaFormatosDistintosDeArgon2id(String hash) {
        assertFalse(hasher.matches("claveDePrueba", hash));
    }

    @Test
    void debeGenerarUnHashDistintoDeLaContrasenaOriginal() {
        String password = "claveSegura123";

        String hash = hasher.hash(password);

        assertNotNull(hash);
        assertNotEquals(password, hash);
        assertTrue(hasher.matches(password, hash));
    }

    @Test
    void debeRechazarUnaContrasenaIncorrecta() {
        String hash = hasher.hash("claveCorrecta123");

        assertFalse(hasher.matches("claveIncorrecta", hash));
    }

    @Test
    void debeGenerarHashesDiferentesParaLaMismaContrasena() {
        String password = "claveSegura123";

        String primerHash = hasher.hash(password);
        String segundoHash = hasher.hash(password);

        assertNotEquals(primerHash, segundoHash);
        assertNotEquals(primerHash.split("\\$")[4], segundoHash.split("\\$")[4]);
        assertTrue(hasher.matches(password, primerHash));
        assertTrue(hasher.matches(password, segundoHash));
    }
}
