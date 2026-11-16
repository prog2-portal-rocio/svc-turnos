package com.example.svcturnos.disponibilidad.infrastructure.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class DisponibilidadConfiguration {
    @Bean
    public Clock disponibilidadClock() {
        return Clock.systemUTC();
    }
}
