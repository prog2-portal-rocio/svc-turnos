package com.example.svcturnos.reserva.infrastructure.web.handler;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.infrastructure.web.controller.ConsultaReservasController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = ConsultaReservasController.class)
public class ConsultaReservasExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ConsultaReservasException.class)
    public ProblemDetail consulta(ConsultaReservasException error) {
        var status = switch (error.getMotivo()) {
            case PROCESO_NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case SOLICITUD_RECHAZADA -> HttpStatus.BAD_REQUEST;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CONEXION_FALLIDA, SERVICIO_NO_DISPONIBLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return problema(status, "CONSULTA_" + error.getMotivo().name());
    }

    @ExceptionHandler(CrearHoldException.class)
    public ProblemDetail identidad(CrearHoldException error) {
        var status = switch (error.getMotivo()) {
            case NO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case NO_AUTORIZADO -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return problema(status, "IDENTIDAD_NO_DISPONIBLE");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail parametros() { return problema(HttpStatus.BAD_REQUEST, "PARAMETROS_INVALIDOS"); }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ProblemDetail persistencia() {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCIA_NO_DISPONIBLE");
    }

    private ProblemDetail problema(HttpStatus status, String code) {
        var problema = ProblemDetail.forStatusAndDetail(status,
                "No se pudo completar la consulta en las condiciones actuales.");
        problema.setProperty("code", code);
        return problema;
    }
}
