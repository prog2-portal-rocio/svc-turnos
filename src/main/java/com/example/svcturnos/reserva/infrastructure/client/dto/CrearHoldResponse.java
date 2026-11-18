package com.example.svcturnos.reserva.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrearHoldResponse(String holdId, String reservationProcessId, String status, Instant expiresAt) { }
