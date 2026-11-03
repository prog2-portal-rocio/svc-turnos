package com.example.svcturnos.usuario.infrastructure.web.handler;

import com.example.svcturnos.usuario.application.exception.UsuarioDuplicadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.svcturnos.usuario.application.exception.CredencialesInvalidasException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UsuarioExceptionHandler {

    @ExceptionHandler(UsuarioDuplicadoException.class)
    public ProblemDetail usuarioDuplicado(
            UsuarioDuplicadoException exception
    ) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );

        problema.setTitle("Usuario duplicado");
        problema.setProperty("code", "USUARIO_DUPLICADO");

        return problema;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail datosInvalidos(
            IllegalArgumentException exception
    ) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );

        problema.setTitle("Datos inválidos");
        problema.setProperty("code", "DATOS_INVALIDOS");

        return problema;
    }

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ProblemDetail credencialesInvalidas(
            CredencialesInvalidasException exception
    ) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Credenciales incorrectas"
        );

        problema.setTitle("No autorizado");
        problema.setProperty("code", "CREDENCIALES_INVALIDAS");

        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail manejarValidacion(
            MethodArgumentNotValidException exception
    ) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Uno o más campos contienen datos inválidos"
        );

        problema.setTitle("Datos inválidos");
        problema.setProperty("code", "DATOS_INVALIDOS");

        return problema;
    }
}
