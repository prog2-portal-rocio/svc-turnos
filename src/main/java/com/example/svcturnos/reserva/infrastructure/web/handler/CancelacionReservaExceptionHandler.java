package com.example.svcturnos.reserva.infrastructure.web.handler;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.infrastructure.web.controller.CancelacionReservaController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = CancelacionReservaController.class)
public class CancelacionReservaExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(CancelarReservaException.class)
    public ProblemDetail cancelacion(CancelarReservaException error) {
        var status = switch (error.getMotivo()) {
            case RESERVA_NO_ENCONTRADA -> HttpStatus.NOT_FOUND;
            case ESTADO_NO_PERMITIDO -> HttpStatus.CONFLICT;
            case SOLICITUD_INVALIDA -> HttpStatus.BAD_REQUEST;
            case PROPIEDAD_RECHAZADA -> HttpStatus.FORBIDDEN;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RESULTADO_INCIERTO, CONEXION_FALLIDA, PERSISTENCIA_FALLIDA, VERIFICACION_INCOMPLETA -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return problema(status, "CANCELACION_" + error.getMotivo().name());
    }
    @ExceptionHandler(CrearHoldException.class)
    public ProblemDetail identidad(CrearHoldException error) {
        return problema(error.getMotivo() == CrearHoldException.Motivo.NO_AUTENTICADO
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, "IDENTIDAD_NO_DISPONIBLE");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail parametros() { return problema(HttpStatus.BAD_REQUEST, "PARAMETROS_INVALIDOS"); }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class,
            com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException.class})
    public ProblemDetail persistencia() { return problema(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCIA_NO_DISPONIBLE"); }
    private ProblemDetail problema(HttpStatus status, String codigo) {
        var problema = ProblemDetail.forStatusAndDetail(status, "No se pudo completar la cancelación. Consulte su estado antes de iniciar otra operación.");
        problema.setProperty("code", codigo);
        return problema;
    }
}
