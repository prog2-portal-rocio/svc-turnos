package com.example.svcturnos.disponibilidad.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OcupacionesRestClientConfiguration {
    @Bean
    public RestClient ocupacionesRestClient(@Value("${turnos.catedra.base-url:}") String baseUrl,
            @Value("${turnos.catedra.connect-timeout:3s}") Duration conexion,
            @Value("${turnos.catedra.response-timeout:5s}") Duration respuesta) {
        validarTimeout(conexion);
        validarTimeout(respuesta);
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(conexion);
        factory.setReadTimeout(respuesta);
        var builder = RestClient.builder().requestFactory(factory);
        // Sin configuración técnica, el resto del servicio puede arrancar; la consulta falla cerrada.
        if (!baseUrl.isBlank()) {
            URI uri = URI.create(baseUrl);
            Assert.isTrue(uri.isAbsolute() && uri.getHost() != null
                    && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                    "La URL de cátedra debe ser HTTP(S), sin credenciales, query ni fragmento");
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private void validarTimeout(Duration timeout) {
        Assert.isTrue(timeout != null && timeout.toMillis() > 0 && timeout.toMillis() <= Integer.MAX_VALUE,
                "Los timeouts de cátedra deben ser positivos y limitados");
    }
}
