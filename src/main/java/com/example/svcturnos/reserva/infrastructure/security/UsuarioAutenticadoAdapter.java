package com.example.svcturnos.reserva.infrastructure.security;

import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.reserva.domain.ports.out.UsuarioAutenticadoPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import static com.example.svcturnos.reserva.domain.exception.CrearHoldException.Motivo.*;

@Component
public class UsuarioAutenticadoAdapter implements UsuarioAutenticadoPort {
    private final UsuarioPersistencePort usuarios;

    public UsuarioAutenticadoAdapter(UsuarioPersistencePort usuarios) { this.usuarios = usuarios; }

    @Override
    public Long obtenerUsuarioId() { return usuarioActual().id(); }

    @Override
    public PacienteReserva obtenerPaciente() {
        var usuario = usuarioActual();
        return new PacienteReserva(usuario.id(), usuario.firstName(), usuario.lastName());
    }

    private Usuario usuarioActual() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new CrearHoldException(NO_AUTENTICADO);
        }
        Long id = leerId(jwt.getToken().getSubject());
        String login = jwt.getToken().getClaimAsString("login");
        if (login == null || login.isBlank()) { throw new CrearHoldException(NO_AUTORIZADO); }
        var usuario = usuarios.buscarPorLogin(login).orElseThrow(() -> new CrearHoldException(NO_AUTORIZADO));
        if (!usuario.activated() || !id.equals(usuario.id())) { throw new CrearHoldException(NO_AUTORIZADO); }
        return usuario;
    }

    private Long leerId(String subject) {
        try {
            long id = Long.parseLong(subject);
            if (id > 0) { return id; }
        } catch (NumberFormatException _) {
            // El sub firmado debe identificar un usuario local persistido.
        }
        throw new CrearHoldException(NO_AUTORIZADO);
    }
}
