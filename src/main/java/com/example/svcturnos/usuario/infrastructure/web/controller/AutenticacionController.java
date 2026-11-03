package com.example.svcturnos.usuario.infrastructure.web.controller;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.AutenticarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.out.TokenPort;
import com.example.svcturnos.usuario.infrastructure.web.dto.AutenticacionRequest;
import com.example.svcturnos.usuario.infrastructure.web.dto.AutenticacionResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AutenticacionController {

    private final AutenticarUsuarioUseCase autenticarUsuario;
    private final TokenPort tokenPort;

    public AutenticacionController(
            AutenticarUsuarioUseCase autenticarUsuario,
            TokenPort tokenPort
    ) {
        this.autenticarUsuario = autenticarUsuario;
        this.tokenPort = tokenPort;
    }

    @PostMapping("/authenticate")
    public ResponseEntity<AutenticacionResponse> autenticar(
            @Valid @RequestBody AutenticacionRequest request
    ) {
        Usuario usuario = autenticarUsuario.autenticar(
                request.username(),
                request.password()
        );

        String token = tokenPort.generarToken(usuario);

        return ResponseEntity.ok(
                new AutenticacionResponse(token)
        );
    }
}
