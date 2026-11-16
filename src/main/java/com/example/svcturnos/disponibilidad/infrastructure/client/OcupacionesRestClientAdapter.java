package com.example.svcturnos.disponibilidad.infrastructure.client;

import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Codigo;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Motivo;
import com.example.svcturnos.disponibilidad.domain.model.Ocupacion;
import com.example.svcturnos.disponibilidad.domain.ports.out.ConsultarOcupacionesPort;
import com.example.svcturnos.disponibilidad.infrastructure.client.dto.OcupacionesResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.List;

@Component
public class OcupacionesRestClientAdapter implements ConsultarOcupacionesPort {
    private final RestClient client;
    private final String tokenTecnico;
    private final OcupacionesResponseMapper mapper = new OcupacionesResponseMapper();
    private final JsonMapper json = JsonMapper.builder().build();

    public OcupacionesRestClientAdapter(@Qualifier("ocupacionesRestClient") RestClient client,
            @Value("${turnos.catedra.technical-token:}") String tokenTecnico) {
        this.client = client;
        this.tokenTecnico = tokenTecnico;
    }

    @Override
    public List<Ocupacion> consultarOcupaciones(Long profesionalId, LocalDate fecha) {
        if (profesionalId == null || profesionalId <= 0 || fecha == null) {
            throw new ConsultaOcupacionesException(Motivo.SOLICITUD_INVALIDA);
        }
        if (tokenTecnico == null || tokenTecnico.isBlank() || tokenTecnico.chars().anyMatch(Character::isWhitespace)) {
            throw new ConsultaOcupacionesException(Motivo.CONFIGURACION_INCOMPLETA);
        }
        try {
            var dto = client.get().uri(builder -> builder.path("/api/appointment-occupancies")
                            .queryParam("professionalId", profesionalId)
                            .queryParam("from", fecha).queryParam("to", fecha).build())
                    .headers(headers -> headers.setBearerAuth(tokenTecnico))
                    .retrieve().onStatus(status -> status.value() >= 300, (request, response) -> {
                        throw errorHttp(response);
                    }).body(OcupacionesResponse.class);
            return mapper.toDomain(dto, profesionalId, fecha);
        } catch (ResourceAccessException ex) {
            throw new ConsultaOcupacionesException(esTimeout(ex) ? Motivo.TIEMPO_AGOTADO : Motivo.NO_DISPONIBLE);
        } catch (RestClientException ex) {
            throw new ConsultaOcupacionesException(esTimeout(ex) ? Motivo.TIEMPO_AGOTADO : Motivo.RESPUESTA_INVALIDA);
        } catch (IllegalArgumentException _) {
            throw new ConsultaOcupacionesException(Motivo.CONFIGURACION_INCOMPLETA);
        }
    }

    private ConsultaOcupacionesException errorHttp(ClientHttpResponse response) throws java.io.IOException {
        Motivo motivo = switch (response.getStatusCode().value()) {
            case 400 -> Motivo.SOLICITUD_INVALIDA;
            case 401 -> Motivo.NO_AUTENTICADO;
            case 403 -> Motivo.ACCESO_DENEGADO;
            case 404 -> Motivo.NO_ENCONTRADO;
            default -> Motivo.ERROR_SERVICIO;
        };
        return new ConsultaOcupacionesException(motivo, leerCodigo(response));
    }

    private Codigo leerCodigo(ClientHttpResponse response) {
        try {
            var body = json.readTree(response.getBody());
            return body == null ? null : Codigo.valueOf(body.path("code").asString());
        } catch (RuntimeException | java.io.IOException _) {
            // Security e infraestructura pueden no enviar problem+json ni code (§13).
            return null;
        }
    }

    private boolean esTimeout(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof SocketTimeoutException || causa instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
