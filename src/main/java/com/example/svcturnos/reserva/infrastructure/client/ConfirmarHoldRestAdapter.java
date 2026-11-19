package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException.Codigo;
import com.example.svcturnos.reserva.domain.model.ConfirmacionInicialAceptada;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;
import com.example.svcturnos.reserva.domain.ports.out.ConfirmarHoldRemotoPort;
import com.example.svcturnos.reserva.infrastructure.client.dto.ConfirmarHoldRequest;
import com.example.svcturnos.reserva.infrastructure.client.dto.ConfirmarHoldResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import static com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException.Motivo.*;

@Component
public class ConfirmarHoldRestAdapter implements ConfirmarHoldRemotoPort {
    private final RestClient client;
    private final String token;
    private final JsonMapper json = JsonMapper.builder().build();

    public ConfirmarHoldRestAdapter(@Qualifier("ocupacionesRestClient") RestClient client,
            @Value("${turnos.catedra.technical-token:}") String token) {
        this.client = client;
        this.token = token;
    }

    @Override
    public ConfirmacionInicialAceptada confirmar(String holdId, PacienteReserva paciente) {
        if (holdId == null || holdId.isBlank() || paciente == null) { throw new IllegalArgumentException("Hold y paciente obligatorios"); }
        if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ConfirmarHoldException(CONFIGURACION_INCOMPLETA);
        }
        try {
            var dto = client.post().uri("/api/appointment-holds/{holdId}/confirm", holdId)
                    .contentType(MediaType.APPLICATION_JSON).headers(headers -> headers.setBearerAuth(token))
                    .body(new ConfirmarHoldRequest(paciente.externalPatientId(), paciente.patientFirstName(), paciente.patientLastName()))
                    .retrieve().onStatus(status -> status.value() != 202, (request, response) -> {
                        throw errorHttp(response);
                    }).body(ConfirmarHoldResponse.class);
            return leerRespuesta(dto);
        } catch (RestClientException _) {
            throw new ConfirmarHoldException(RESULTADO_INCIERTO);
        } catch (IllegalArgumentException _) {
            // Fuera de la validación previa no puede garantizarse que el POST no se haya enviado.
            throw new ConfirmarHoldException(RESULTADO_INCIERTO);
        }
    }

    private ConfirmacionInicialAceptada leerRespuesta(ConfirmarHoldResponse dto) {
        try {
            if (dto == null || !"WAITING_FOR_PHONE".equals(dto.status())) { throw new ConfirmarHoldException(RESULTADO_INCIERTO); }
            return new ConfirmacionInicialAceptada(dto.reservationProcessId(), dto.expiresAt());
        } catch (IllegalArgumentException _) {
            throw new ConfirmarHoldException(RESULTADO_INCIERTO);
        }
    }

    private ConfirmarHoldException errorHttp(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        var codigo = leerCodigo(response);
        var motivo = switch (status) {
            case 400 -> codigo == Codigo.VALIDATION_ERROR || codigo == Codigo.EXTERNAL_PATIENT_MISMATCH
                    || codigo == Codigo.INVALID_SLOT ? SOLICITUD_RECHAZADA : RESULTADO_INCIERTO;
            case 401 -> CREDENCIAL_TECNICA_RECHAZADA;
            case 403 -> codigo == Codigo.HOLD_OWNER_MISMATCH ? SOLICITUD_RECHAZADA : CREDENCIAL_TECNICA_RECHAZADA;
            case 404 -> motivoNoEncontrado(codigo);
            case 409 -> motivoConflicto(codigo);
            default -> RESULTADO_INCIERTO;
        };
        return new ConfirmarHoldException(motivo, codigo, null);
    }

    private ConfirmarHoldException.Motivo motivoNoEncontrado(Codigo codigo) {
        if (codigo == Codigo.HOLD_EXPIRED) { return HOLD_VENCIDO; }
        if (codigo == Codigo.HOLD_NOT_FOUND) { return HOLD_NO_ENCONTRADO; }
        return codigo == Codigo.PROFESSIONAL_NOT_FOUND ? SOLICITUD_RECHAZADA : RESULTADO_INCIERTO;
    }

    private ConfirmarHoldException.Motivo motivoConflicto(Codigo codigo) {
        if (codigo == Codigo.RESERVATION_PROCESS_ALREADY_CONFIRMED) { return PROCESO_YA_CONFIRMADO; }
        return codigo == Codigo.SLOT_ALREADY_RESERVED ? SOLICITUD_RECHAZADA : RESULTADO_INCIERTO;
    }

    private Codigo leerCodigo(ClientHttpResponse response) {
        try {
            var body = json.readTree(response.getBody());
            return body == null ? null : Codigo.valueOf(body.path("code").asString());
        } catch (RuntimeException | IOException _) { return null; }
    }
}
