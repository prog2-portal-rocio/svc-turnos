package com.example.svcturnos.usuario.infrastructure.security;

import java.util.List;

final class JwtContract {

    static final String ISSUER = "svc-turnos";
    static final String TURNOS_AUDIENCE = "svc-turnos-api";
    static final String CATALOGO_AUDIENCE = "svc-catalogo-api";
    static final List<String> USER_AUDIENCES = List.of(TURNOS_AUDIENCE, CATALOGO_AUDIENCE);
    static final String USER_SCOPE = "catalogo:read";

    private JwtContract() {
    }
}
