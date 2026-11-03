package com.example.svcturnos.usuario.domain.ports.out;

public interface PasswordHasherPort {

    String hash(String password);

    boolean matches(String password, String passwordHash);
}
