package com.example.svcturnos.reserva.infrastructure.web.handler;

import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.reserva.infrastructure.web.controller.HoldController;
import com.example.svcturnos.reserva.infrastructure.web.controller.ConfirmacionHoldController;
import com.example.svcturnos.reserva.infrastructure.web.controller.InformacionAdicionalController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {HoldController.class, ConfirmacionHoldController.class, InformacionAdicionalController.class})
public class HoldExceptionHandler {
    @ExceptionHandler(CrearHoldException.class)
    public ProblemDetail hold(CrearHoldException error) {
        HttpStatus status = switch (error.getMotivo()) {
            case NO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case NO_AUTORIZADO -> HttpStatus.FORBIDDEN;
            case SOLICITUD_INVALIDA -> HttpStatus.BAD_REQUEST;
            case PROFESIONAL_INEXISTENTE -> HttpStatus.NOT_FOUND;
            case SLOT_NO_DISPONIBLE, CONFLICTO, SOLICITUD_DUPLICADA -> HttpStatus.CONFLICT;
            case CONFIGURACION_INCOMPLETA, CREDENCIAL_TECNICA_RECHAZADA -> HttpStatus.BAD_GATEWAY;
            case RESULTADO_INCIERTO, PERSISTENCIA_FALLIDA -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problema = problema(status, "HOLD_" + error.getMotivo().name());
        if (error.getMotivo() == CrearHoldException.Motivo.SOLICITUD_DUPLICADA) {
            problema.setDetail("Ya existe una solicitud equivalente pendiente o un hold vigente.");
        }
        if (error.getMotivo() == CrearHoldException.Motivo.RESULTADO_INCIERTO
                || (error.getMotivo() == CrearHoldException.Motivo.PERSISTENCIA_FALLIDA && error.getProcesoId() != null)) {
            problema.setDetail("No se conoce el resultado de la operación. Consulte su estado antes de volver a solicitarla.");
        }
        if (error.getProcesoId() != null) { problema.setProperty("procesoId", error.getProcesoId()); }
        return problema;
    }

    @ExceptionHandler(ConsultaCatalogoException.class)
    public ProblemDetail catalogo(ConsultaCatalogoException error) {
        HttpStatus status = switch (error.getMotivo()) {
            case SOLICITUD_INVALIDA -> HttpStatus.BAD_REQUEST;
            case PROFESIONAL_NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case NO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case ACCESO_DENEGADO -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return problema(status, "CATALOGO_NO_DISPONIBLE");
    }

    @ExceptionHandler(ConsultaOcupacionesException.class)
    public ProblemDetail ocupaciones() {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "OCUPACIONES_NO_DISPONIBLES");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail formatoInvalido() {
        return problema(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS");
    }

    private ProblemDetail problema(HttpStatus status, String code) {
        String detalle = switch (status) {
            case BAD_REQUEST -> "Los datos de la solicitud no son válidos.";
            case UNAUTHORIZED -> "Debe autenticarse para realizar esta operación.";
            case FORBIDDEN -> "No tiene permiso para realizar esta operación.";
            case NOT_FOUND -> "No se encontró el profesional solicitado.";
            case CONFLICT -> "El horario solicitado no está disponible.";
            default -> "No se pudo completar la solicitud. El servicio no está disponible en este momento.";
        };
        var problema = ProblemDetail.forStatusAndDetail(status, detalle);
        problema.setProperty("code", code);
        return problema;
    }
}
