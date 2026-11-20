package com.example.svcturnos.reserva.infrastructure.web.handler;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import com.example.svcturnos.reserva.infrastructure.web.controller.InformacionAdicionalController;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = InformacionAdicionalController.class)
public class InformacionAdicionalExceptionHandler {
    @ExceptionHandler(EnviarInformacionException.class)
    public ProblemDetail informacion(EnviarInformacionException error) {
        var status = switch (error.getMotivo()) {
            case PROCESO_INEXISTENTE -> HttpStatus.NOT_FOUND;
            case PROCESO_AJENO -> HttpStatus.FORBIDDEN;
            case ESTADO_INCOMPATIBLE, SOLICITUD_NO_DISPONIBLE, CORRELACION_INVALIDA, RESPUESTA_EXISTENTE -> HttpStatus.CONFLICT;
            case PLAZO_VENCIDO -> HttpStatus.GONE;
            case TELEFONO_INVALIDO -> HttpStatus.BAD_REQUEST;
            case CONFIGURACION_INCOMPLETA, PERSISTENCIA_FALLIDA -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problema = ProblemDetail.forStatusAndDetail(status, "No se pudo aceptar la información adicional en las condiciones actuales.");
        problema.setProperty("code", "INFORMACION_" + error.getMotivo().name());
        return problema;
    }
}
