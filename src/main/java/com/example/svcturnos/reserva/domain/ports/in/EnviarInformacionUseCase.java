package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.EnvioInformacion;
public interface EnviarInformacionUseCase { EnvioInformacion enviar(Long procesoId, String telefono); }
