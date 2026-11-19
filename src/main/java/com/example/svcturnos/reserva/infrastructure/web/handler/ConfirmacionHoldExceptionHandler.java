package com.example.svcturnos.reserva.infrastructure.web.handler;

import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.infrastructure.web.controller.ConfirmacionHoldController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConfirmacionHoldController.class)
public class ConfirmacionHoldExceptionHandler {
    @ExceptionHandler(ConfirmarHoldException.class)
    public ProblemDetail confirmar(ConfirmarHoldException error) {
        var status = switch (error.getMotivo()) {
            case PROCESO_INEXISTENTE, HOLD_NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case PROCESO_AJENO -> HttpStatus.FORBIDDEN;
            case ESTADO_INCOMPATIBLE, PROCESO_YA_CONFIRMADO -> HttpStatus.CONFLICT;
            case HOLD_VENCIDO -> HttpStatus.GONE;
            case SOLICITUD_RECHAZADA -> HttpStatus.BAD_REQUEST;
            case CONFIGURACION_INCOMPLETA, CREDENCIAL_TECNICA_RECHAZADA -> HttpStatus.BAD_GATEWAY;
            case RESULTADO_INCIERTO, PERSISTENCIA_FALLIDA, CORRELACION_PENDIENTE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String detalle = switch (error.getMotivo()) {
            case RESULTADO_INCIERTO, PROCESO_YA_CONFIRMADO, PERSISTENCIA_FALLIDA ->
                    "No se pudo establecer el resultado de la confirmación. No repita la operación remota.";
            case CORRELACION_PENDIENTE -> "La confirmación inicial fue aceptada; queda pendiente recuperar la solicitud de información.";
            case HOLD_VENCIDO -> "El hold está vencido.";
            case PROCESO_AJENO -> "No tiene permiso para confirmar este proceso.";
            default -> "No se pudo realizar la confirmación inicial en el estado actual.";
        };
        var problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setProperty("code", "CONFIRMACION_" + error.getMotivo().name());
        if (error.getProcesoId() != null) { problema.setProperty("procesoId", error.getProcesoId()); }
        return problema;
    }
}
