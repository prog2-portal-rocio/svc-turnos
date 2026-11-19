package com.example.svcturnos.reserva.infrastructure.client.dto;

import java.time.Instant;

public record ConfirmarHoldResponse(String reservationProcessId, String status, Instant expiresAt) { }
