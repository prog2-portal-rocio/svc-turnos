package com.example.svcturnos.usuario.infrastructure.web.controller;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase.DatosRegistro;
import com.example.svcturnos.usuario.infrastructure.web.dto.RegistroRequest;
import com.example.svcturnos.usuario.infrastructure.web.dto.RegistroResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UsuarioController {

    private final RegistrarUsuarioUseCase registrarUsuarioUseCase;

    public UsuarioController(RegistrarUsuarioUseCase registrarUsuarioUseCase) {
        this.registrarUsuarioUseCase = registrarUsuarioUseCase;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistroResponse> registrar(
            @Valid @RequestBody RegistroRequest request
    ) {
        DatosRegistro datos = new DatosRegistro(
                request.login(),
                request.password(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.imageUrl(),
                request.langKey()
        );

        Usuario usuario = registrarUsuarioUseCase.registrar(datos);

        RegistroResponse response = new RegistroResponse(
                usuario.id(),
                usuario.login(),
                usuario.firstName(),
                usuario.lastName(),
                usuario.email(),
                usuario.imageUrl(),
                usuario.langKey(),
                usuario.activated()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
