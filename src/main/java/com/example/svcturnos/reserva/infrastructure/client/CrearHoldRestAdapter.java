package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException.Codigo;
import com.example.svcturnos.reserva.domain.model.HoldCreado;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;
import com.example.svcturnos.reserva.domain.ports.out.CrearHoldRemotoPort;
import com.example.svcturnos.reserva.infrastructure.client.dto.CrearHoldRequest;
import com.example.svcturnos.reserva.infrastructure.client.dto.CrearHoldResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import static com.example.svcturnos.reserva.domain.exception.CrearHoldException.Motivo.*;

@Component
public class CrearHoldRestAdapter implements CrearHoldRemotoPort {
    private final RestClient client;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();

    public CrearHoldRestAdapter(@Qualifier("ocupacionesRestClient") RestClient client,
            @Value("${turnos.catedra.technical-token:}") String token) {
        this.client = client;
        this.token = token;
    }

    @Override
    public HoldCreado crear(SolicitudHold solicitud, String externalPatientId) {
        if (solicitud == null || externalPatientId == null || externalPatientId.isBlank() || externalPatientId.length() > 100) {
            throw new CrearHoldException(SOLICITUD_INVALIDA);
        }
        if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new CrearHoldException(CONFIGURACION_INCOMPLETA);
        }
        try {
            var dto = client.post().uri("/api/appointment-holds").contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(new CrearHoldRequest(solicitud.professionalId(), solicitud.date(), solicitud.startTime(), externalPatientId))
                    .retrieve().onStatus(status -> status.value() != 201, (request, response) -> {
                        throw errorHttp(response.getStatusCode().value(), response);
                    }).body(CrearHoldResponse.class);
            return leerRespuesta(dto);
        } catch (RestClientException _) {
            // No conocemos si se aplicó el POST: incluye timeouts, desconexiones y JSON truncado.
            throw new CrearHoldException(RESULTADO_INCIERTO);
        } catch (IllegalArgumentException _) {
            throw new CrearHoldException(CONFIGURACION_INCOMPLETA);
        }
    }

    private HoldCreado leerRespuesta(CrearHoldResponse dto) {
        try {
            if (dto == null || !"HELD".equals(dto.status())) { throw new CrearHoldException(RESULTADO_INCIERTO); }
            return new HoldCreado(dto.holdId(), dto.reservationProcessId(), dto.expiresAt());
        } catch (IllegalArgumentException _) {
            throw new CrearHoldException(RESULTADO_INCIERTO);
        }
    }

    private CrearHoldException errorHttp(int status, ClientHttpResponse response) {
        var motivo = switch (status) {
            case 400 -> SOLICITUD_INVALIDA;
            case 401, 403 -> CREDENCIAL_TECNICA_RECHAZADA;
            case 404 -> PROFESIONAL_INEXISTENTE;
            case 409 -> CONFLICTO;
            default -> RESULTADO_INCIERTO;
        };
        return new CrearHoldException(motivo, leerCodigo(response), null);
    }

    private Codigo leerCodigo(ClientHttpResponse response) {
        try {
            var body = json.readTree(response.getBody());
            return body == null ? null : Codigo.valueOf(body.path("code").asString());
        } catch (RuntimeException | IOException _) { return null; }
    }
}
