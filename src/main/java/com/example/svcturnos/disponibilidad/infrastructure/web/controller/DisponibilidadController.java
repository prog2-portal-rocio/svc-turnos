package com.example.svcturnos.disponibilidad.infrastructure.web.controller;

import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.infrastructure.web.dto.BloqueDisponibleResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/disponibilidad")
public class DisponibilidadController {
    private final ConsultarDisponibilidadUseCase disponibilidad;

    public DisponibilidadController(ConsultarDisponibilidadUseCase disponibilidad) {
        this.disponibilidad = disponibilidad;
    }

    @GetMapping
    public List<BloqueDisponibleResponse> consultar(@RequestParam Long profesionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        if (profesionalId <= 0) { throw new IllegalArgumentException("El profesional debe ser positivo"); }
        return disponibilidad.consultar(profesionalId, fecha).stream().map(BloqueDisponibleResponse::from).toList();
    }
    // La resolución local evita que el advice global de usuarios interprete causas de conversión.
    @ExceptionHandler({IllegalArgumentException.class, TypeMismatchException.class, MissingServletRequestParameterException.class})
    public ProblemDetail parametrosInvalidos() {
        var problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Los parámetros de disponibilidad no son válidos.");
        problema.setProperty("code", "PARAMETROS_INVALIDOS");
        return problema;
    }

    @ExceptionHandler(ConsultaCatalogoException.class)
    public ProblemDetail catalogo(ConsultaCatalogoException error) {
        var status = switch (error.getMotivo()) {
            case SOLICITUD_INVALIDA -> HttpStatus.BAD_REQUEST;
            case PROFESIONAL_NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case NO_AUTENTICADO -> HttpStatus.UNAUTHORIZED;
            case ACCESO_DENEGADO -> HttpStatus.FORBIDDEN;
            case TIEMPO_AGOTADO -> HttpStatus.GATEWAY_TIMEOUT;
            case ERROR_SERVICIO, NO_DISPONIBLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RESPUESTA_INVALIDA -> HttpStatus.BAD_GATEWAY;
        };
        return problema(status, "CATALOGO_" + error.getMotivo().name());
    }

    @ExceptionHandler(ConsultaOcupacionesException.class)
    public ProblemDetail ocupaciones(ConsultaOcupacionesException error) {
        var status = switch (error.getMotivo()) {
            case TIEMPO_AGOTADO -> HttpStatus.GATEWAY_TIMEOUT;
            case ERROR_SERVICIO, NO_DISPONIBLE -> HttpStatus.SERVICE_UNAVAILABLE;
            // Incluye autenticación técnica: no atribuir el error al JWT del paciente.
            default -> HttpStatus.BAD_GATEWAY;
        };
        return problema(status, "OCUPACIONES_" + error.getMotivo().name());
    }

    private ProblemDetail problema(HttpStatus status, String codigo) {
        var problema = ProblemDetail.forStatusAndDetail(status, "No se pudo completar la consulta de disponibilidad.");
        problema.setProperty("code", codigo);
        return problema;
    }
}
