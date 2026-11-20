package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;

@Service
public class PublicarInformacionService {
    private final OutboxInformacionPort outbox;
    private final PublicarInformacionPort kafka;
    private final Clock clock;
    public PublicarInformacionService(OutboxInformacionPort outbox, PublicarInformacionPort kafka, Clock clock) {
        this.outbox = outbox; this.kafka = kafka; this.clock = clock;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int publicarPendientes(int limite, int maxIntentos, Duration concesion, Duration demora) {
        outbox.depurarVencidos(clock.instant());
        int procesados = 0;
        for (int i = 0; i < limite; i++) {
            var siguiente = outbox.reclamar(clock.instant(), concesion, maxIntentos);
            if (siguiente.isEmpty()) { break; }
            var envio = siguiente.get();
            boolean enviada = false;
            try {
                if (envio.expiresAt().isAfter(clock.instant())) { kafka.publicar(envio); enviada = true; }
            }
            catch (RuntimeException _) { /* El broker podría haberlo recibido: conservar eventId al recuperar. */ }
            outbox.registrarResultado(envio.eventId(), envio.intentoId(), enviada, clock.instant(), demora);
            procesados++;
        }
        return procesados;
    }
}
