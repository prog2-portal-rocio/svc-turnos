package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.model.CancelacionConfirmada;
import com.example.svcturnos.reserva.domain.model.SolicitudCancelacion;
import com.example.svcturnos.reserva.domain.ports.out.CancelarReservaRemotaPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.time.Instant;
import static com.example.svcturnos.reserva.domain.exception.CancelarReservaException.Motivo.*;

@Component
public class CancelarReservaRestAdapter implements CancelarReservaRemotaPort {
    private final RestClient client;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();
    public CancelarReservaRestAdapter(@Qualifier("ocupacionesRestClient") RestClient client,
            @Value("${turnos.catedra.technical-token:}") String token) {
        this.client = client; this.token = token;
    }
    public record Peticion(String reason) { }
    public record Respuesta(Long reservationId, String reservationProcessId, String status, Instant cancelledAt) { }
    @Override
    public CancelacionConfirmada cancelar(SolicitudCancelacion solicitud, Long reservaEsperada) {
        if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new CancelarReservaException(CONFIGURACION_INCOMPLETA);
        }
        try {
            var respuesta = client.post().uri("/api/appointments/{id}/cancel", solicitud.reservationProcessId())
                    .contentType(MediaType.APPLICATION_JSON).headers(headers -> headers.setBearerAuth(token))
                    .body(new Peticion(solicitud.reason())).retrieve()
                    .onStatus(status -> status.value() != 200, (request, response) -> { throw error(response); })
                    .body(Respuesta.class);
            if (respuesta == null || !"CANCELLED".equals(respuesta.status()) || respuesta.cancelledAt() == null
                    || !reservaEsperada.equals(respuesta.reservationId())
                    || !solicitud.reservationProcessId().equals(respuesta.reservationProcessId())) {
                throw new CancelarReservaException(RESULTADO_INCIERTO);
            }
            return new CancelacionConfirmada(respuesta.reservationId(), respuesta.reservationProcessId(), respuesta.cancelledAt());
        } catch (RestClientException error) {
            throw new CancelarReservaException(esTimeout(error) ? TIMEOUT : RESULTADO_INCIERTO);
        } catch (IllegalArgumentException _) {
            throw new CancelarReservaException(RESULTADO_INCIERTO);
        }
    }
    private CancelarReservaException error(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String codigo = codigo(response);
        var motivo = switch (status) {
            case 400 -> "VALIDATION_ERROR".equals(codigo) ? SOLICITUD_INVALIDA : RESULTADO_INCIERTO;
            case 401 -> CREDENCIAL_TECNICA_RECHAZADA;
            case 403 -> motivoProhibido(codigo);
            case 404 -> "RESERVATION_NOT_FOUND".equals(codigo) ? RESERVA_NO_ENCONTRADA : RESULTADO_INCIERTO;
            case 409 -> "RESERVATION_NOT_CANCELLABLE".equals(codigo) ? ESTADO_NO_PERMITIDO : RESULTADO_INCIERTO;
            default -> RESULTADO_INCIERTO;
        };
        return new CancelarReservaException(motivo);
    }
    private CancelarReservaException.Motivo motivoProhibido(String codigo) {
        // §13: Spring Security puede rechazar la petición con 403 sin un código funcional.
        if (codigo.isBlank()) { return CREDENCIAL_TECNICA_RECHAZADA; }
        if ("RESERVATION_OWNER_MISMATCH".equals(codigo)) { return PROPIEDAD_RECHAZADA; }
        return "STUDENT_INTEGRATION_NOT_FOUND".equals(codigo) ? CREDENCIAL_TECNICA_RECHAZADA : RESULTADO_INCIERTO;
    }
    private boolean esTimeout(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof java.net.SocketTimeoutException || causa instanceof java.net.http.HttpTimeoutException) { return true; }
        }
        return false;
    }
    private String codigo(ClientHttpResponse response) {
        try { var body = json.readTree(response.getBody()); return body == null ? "" : body.path("code").asString(); }
        catch (RuntimeException | IOException _) { return ""; }
    }
}
