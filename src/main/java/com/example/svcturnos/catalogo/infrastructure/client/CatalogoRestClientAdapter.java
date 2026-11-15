package com.example.svcturnos.catalogo.infrastructure.client;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException.Motivo;
import com.example.svcturnos.catalogo.domain.model.HorarioSemanal;
import com.example.svcturnos.catalogo.domain.model.Profesional;
import com.example.svcturnos.catalogo.domain.ports.out.ConsultarCatalogoPort;
import com.example.svcturnos.catalogo.infrastructure.client.dto.HorarioCatalogoResponse;
import com.example.svcturnos.catalogo.infrastructure.client.dto.ProfesionalCatalogoResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.function.Supplier;

@Component
public class CatalogoRestClientAdapter implements ConsultarCatalogoPort {

    private static final String PROFESIONALES = "/api/catalogo/profesionales/{id}";
    private final RestClient restClient;
    private final CatalogoResponseMapper mapper = new CatalogoResponseMapper();

    public CatalogoRestClientAdapter(@Qualifier("catalogoRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Profesional consultarProfesional(Long id) {
        validarId(id);
        return ejecutar(() -> mapper.toProfesional(
                consultar(PROFESIONALES, id).body(ProfesionalCatalogoResponse.class), id));
    }

    @Override
    public List<HorarioSemanal> consultarHorarios(Long profesionalId) {
        validarId(profesionalId);
        return ejecutar(() -> mapper.toHorarios(consultar(PROFESIONALES + "/horarios", profesionalId)
                .body(new ParameterizedTypeReference<List<HorarioCatalogoResponse>>() {}), profesionalId));
    }

    private RestClient.ResponseSpec consultar(String ruta, Long id) {
        return restClient.get().uri(ruta, id)
                .headers(headers -> headers.setBearerAuth(obtenerJwtUsuario()))
                .retrieve()
                .onStatus(status -> status.value() >= 300, (request, response) -> {
                    Motivo motivo = switch (response.getStatusCode().value()) {
                        case 400 -> Motivo.SOLICITUD_INVALIDA;
                        case 401 -> Motivo.NO_AUTENTICADO;
                        case 403 -> Motivo.ACCESO_DENEGADO;
                        case 404 -> Motivo.PROFESIONAL_NO_ENCONTRADO;
                        default -> Motivo.ERROR_SERVICIO;
                    };
                    throw new ConsultaCatalogoException(motivo);
                });
    }

    private String obtenerJwtUsuario() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new ConsultaCatalogoException(Motivo.NO_AUTENTICADO);
        }
        return jwt.getToken().getTokenValue();
    }

    private void validarId(Long id) {
        if (id == null || id <= 0) {
            throw new ConsultaCatalogoException(Motivo.SOLICITUD_INVALIDA);
        }
    }

    private <T> T ejecutar(Supplier<T> consulta) {
        try {
            return consulta.get();
        } catch (ResourceAccessException exception) {
            throw new ConsultaCatalogoException(esTimeout(exception) ? Motivo.TIEMPO_AGOTADO : Motivo.NO_DISPONIBLE,
                    exception);
        } catch (RestClientException exception) {
            throw new ConsultaCatalogoException(esTimeout(exception) ? Motivo.TIEMPO_AGOTADO : Motivo.RESPUESTA_INVALIDA,
                    exception);
        }
    }

    private boolean esTimeout(Throwable exception) {
        for (Throwable causa = exception; causa != null; causa = causa.getCause()) {
            if (causa instanceof SocketTimeoutException || causa instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
