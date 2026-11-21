package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.ConsultarReservasRemotasPort;
import com.example.svcturnos.reserva.infrastructure.client.dto.ReservaCentralDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import static com.example.svcturnos.reserva.domain.exception.ConsultaReservasException.Motivo.*;

@Component
public class ConsultarReservasRestAdapter implements ConsultarReservasRemotasPort {
    private final RestClient client;
    private final String token;
    private final String baseUrl;

    public ConsultarReservasRestAdapter(@Qualifier("ocupacionesRestClient") RestClient client,
            @Value("${turnos.catedra.technical-token:}") String token,
            @Value("${turnos.catedra.base-url:}") String baseUrl) {
        this.client = client;
        this.token = token;
        this.baseUrl = baseUrl;
    }

    @Override public PaginaReservas consultar(String paciente, CriteriosConsultaReservas criterios) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ConsultaReservasException(CONFIGURACION_INCOMPLETA);
        }
        try {
            var respuesta = client.get().uri(builder -> consultaUri(builder, paciente, criterios))
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve().onStatus(status -> status.value() != 200, (request, response) -> {
                        throw errorHttp(response.getStatusCode().value());
                    }).toEntity(ReservaCentralDto[].class);
            var body = respuesta.getBody();
            if (body == null || Arrays.stream(body).anyMatch(java.util.Objects::isNull)) {
                throw new ConsultaReservasException(RESPUESTA_INVALIDA);
            }
            long total = Long.parseLong(respuesta.getHeaders().getFirst("X-Total-Count"));
            var reservas = Arrays.stream(body).map(ReservaCentralDto::toDomain).toList();
            for (var reserva : reservas) { validarFiltros(reserva, paciente, criterios); }
            return new PaginaReservas(reservas, total, criterios.page(), criterios.size());
        } catch (ResourceAccessException error) {
            var motivo = error.contains(SocketTimeoutException.class) || error.contains(HttpTimeoutException.class)
                    ? TIMEOUT : CONEXION_FALLIDA;
            throw new ConsultaReservasException(motivo);
        } catch (RestClientException | IllegalArgumentException _) {
            // No propagar bodies, URLs, headers ni causas que puedan contener informacion privada.
            throw new ConsultaReservasException(RESPUESTA_INVALIDA);
        }
    }

    private URI consultaUri(UriBuilder builder, String paciente, CriteriosConsultaReservas criterios) {
        builder.path("/api/appointments").queryParam("externalPatientId", paciente)
                .queryParam("status", criterios.status()).queryParam("page", criterios.page())
                .queryParam("size", criterios.size());
        if (criterios.from() != null) { builder.queryParam("from", criterios.from()); }
        if (criterios.to() != null) { builder.queryParam("to", criterios.to()); }
        for (String orden : criterios.sort()) { builder.queryParam("sort", orden); }
        return builder.build();
    }

    private void validarFiltros(ReservaCentral reserva, String paciente, CriteriosConsultaReservas criterios) {
        if (!paciente.equals(reserva.externalPatientId()) || reserva.status() != criterios.status()
                || (criterios.from() != null && reserva.appointmentDate().isBefore(criterios.from()))
                || (criterios.to() != null && reserva.appointmentDate().isAfter(criterios.to()))) {
            throw new ConsultaReservasException(RESPUESTA_INVALIDA);
        }
    }

    private ConsultaReservasException errorHttp(int status) {
        var motivo = switch (status) {
            case 400 -> SOLICITUD_RECHAZADA;
            case 401, 403 -> CREDENCIAL_TECNICA_RECHAZADA;
            default -> status >= 500 ? SERVICIO_NO_DISPONIBLE : RESPUESTA_INVALIDA;
        };
        return new ConsultaReservasException(motivo);
    }
}
