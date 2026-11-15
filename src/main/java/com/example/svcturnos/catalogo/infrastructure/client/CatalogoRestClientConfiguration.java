package com.example.svcturnos.catalogo.infrastructure.client;

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
public class CatalogoRestClientConfiguration {

    @Bean
    public RestClient catalogoRestClient(@Value("${turnos.catalogo.base-url}") URI baseUrl,
                                        @Value("${turnos.catalogo.connect-timeout}") Duration connectTimeout,
                                        @Value("${turnos.catalogo.response-timeout}") Duration responseTimeout) {
        Assert.isTrue(baseUrl != null && baseUrl.isAbsolute() && baseUrl.getHost() != null
                        && ("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                        && baseUrl.getUserInfo() == null && baseUrl.getQuery() == null && baseUrl.getFragment() == null,
                "La URL de Catalogo debe ser HTTP(S), sin credenciales, query ni fragmento");
        validarTimeout(connectTimeout);
        validarTimeout(responseTimeout);
        var factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(responseTimeout);
        return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }

    private void validarTimeout(Duration timeout) {
        Assert.isTrue(timeout != null && timeout.toMillis() > 0, "Los timeouts de Catalogo deben ser positivos");
    }
}
