package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OutboxInformacionPort {
    EnvioInformacion registrar(Long procesoId, Long propietario, Long solicitudId, Telefono telefono, DestinoInformacion destino);
    Optional<EnvioInformacion> reclamar(Instant ahora, Duration concesion, int maxIntentos);
    void registrarResultado(UUID eventoId, UUID intentoId, boolean enviada, Instant ahora, Duration demora);
    void depurarVencidos(Instant ahora);
}
