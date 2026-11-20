package com.example.svcturnos.reserva.domain.model;

/** ENVIADA acredita recepción por el broker, nunca confirmación de reserva. */
public enum EstadoEnvioInformacion { PENDIENTE, PUBLICANDO, INCIERTA, ENVIADA, VENCIDA }
