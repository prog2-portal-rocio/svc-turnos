# Contratos REST e integración

## Convenciones

Las rutas siguientes corresponden al código de los [controladores de usuario](../src/main/java/com/example/svcturnos/usuario/infrastructure/web/controller), [disponibilidad](../src/main/java/com/example/svcturnos/disponibilidad/infrastructure/web/controller) y [reserva](../src/main/java/com/example/svcturnos/reserva/infrastructure/web/controller). No existe un proxy público de profesionales en Turnos.

Salvo registro y login, se requiere `Authorization: Bearer <JWT de usuario validado>`. Disponibilidad exige además `catalogo:read`. Las operaciones de reserva validan al usuario persistido y su propiedad; no reciben un propietario arbitrario.

Las fechas utilizan `yyyy-MM-dd`; las horas son locales, sin zona, y los instantes se expresan en ISO-8601 UTC. Las respuestas de disponibilidad usan `HH:mm:ss`. Los identificadores locales (`procesoId`) son numéricos y distintos de `reservationProcessId`, `holdId` y `reservationId` externos.

## API de Turnos

| Método y ruta | Entrada | Resultado correcto |
| --- | --- | --- |
| `POST /api/register` | `login`, `password`, `firstName`, `lastName`, `email`; opcionales `imageUrl`, `langKey`. | 201: `id`, `login`, `firstName`, `lastName`, `email`, `imageUrl`, `langKey`, `activated`. Nunca devuelve el hash. |
| `POST /api/authenticate` | `username` (login), `password`. | 200: `{ "id_token": "<JWT>" }`. |
| `GET /api/disponibilidad` | Query obligatoria `profesionalId` positivo, `fecha` ISO válida. | 200: lista de `{fecha,inicio,fin}`; `[]` cuando no hay bloques. |
| `POST /api/reservas/holds` | JSON `professionalId` positivo, `date`, `startTime`. | 201: `{procesoId,estado,expiresAt}` con `HOLD_REGISTRADO`. |
| `POST /api/reservas/holds/{procesoId}/confirm` | ID local positivo; no requiere cuerpo. Los datos del paciente proceden del usuario persistido. | 202: `{procesoId,estado,expiresAt}`, `ESPERANDO_TELEFONO`; no es confirmación definitiva. |
| `POST /api/reservas/holds/{procesoId}/telefono` | JSON `phoneNumber` no blanco. | 202: `{procesoId,eventId,publicacion,expiresAt}`. Describe la publicación local, no la reserva final. |
| `GET /api/procesos-reserva` | Sin filtros ni paginación. | 200: lista de procesos propios ordenada por identificador; `[]` si no existen. |
| `GET /api/procesos-reserva/{id}` | ID local positivo. | 200: proceso propio; 404 para inexistente o ajeno. |
| `GET /api/reservas` | Filtros/paginación descritos debajo. | 200: `{content,totalElements,page,size}` con reservas centrales del paciente autenticado. |
| `POST /api/reservas/{reservationProcessId}/cancel` | ID **externo del proceso**; cuerpo opcional `{reason}` de hasta 500 caracteres. | 200 si `CANCELADA`; 202 si el resultado local sigue pendiente/incierto. |

### Validaciones y DTO

Registro: login de 3–50 caracteres `[A-Za-z0-9_.@-]+`, contraseña de 4–100, email válido de 5–254, nombre y apellido no blancos de 2–100 después de `trim()`. `imageUrl` admite hasta 256 caracteres; `langKey`, si se informa, 2–10. El servicio normaliza login y email a minúsculas después de `trim()` y aplica el idioma predeterminado `es` cuando corresponde. No se habilita edición de perfil en esta API.

El teléfono elimina espacios ASCII, tabulaciones, saltos de línea, guiones, paréntesis y puntos; después exige `+` opcional y 7–15 dígitos ASCII. La respuesta no expone el teléfono. Un reintento de la misma solicitud solo es idempotente cuando la outbox puede comprobar que corresponde a la misma información. Una solicitud ya atendida cuyo teléfono fue eliminado no acepta silenciosamente un número nuevo: responde conflicto.

`ProcesoReservaResponse` contiene `procesoId`, `procesoExternoId`, `profesionalId`, `fecha`, `hora`, `estado`, `vencimientoHold`, `reservaId`, `creadoEn`, `actualizadoEn`, `resultadoOcurridoEn`. Los identificadores externos o timestamps todavía no obtenidos pueden ser nulos.

Cada elemento de `ReservasResponse.content` contiene `reservationId`, `reservationProcessId`, `professionalId`, `professionalFirstName`, `professionalLastName`, `appointmentDate`, `startTime`, `endTime`, `status`, `createdAt`, `confirmedAt`, `cancelledAt`. No incluye nombres, teléfono ni identificador externo del paciente.

La cancelación devuelve `reservationProcessId`, `reservationId`, `estado`, `resultadoEn`, `requiereReconciliacion`. Un 202 de esta API representa incertidumbre local; no cambia el contrato externo de cancelación, cuyo 200 `CANCELLED` es definitivo.

### Consulta paginada de reservas

| Parámetro | Regla |
| --- | --- |
| `status` | `PHONE_PENDING`, `CONFIRMED`, `CANCELLED` o `FAILED`; predeterminado `CONFIRMED`. |
| `from`, `to` | Fechas opcionales inclusivas; `to` no puede ser anterior a `from`. |
| `page` | Desde 0; predeterminado 0. |
| `size` | De 1 a 100; predeterminado 20. |
| `sort` | Repetible, `campo,asc` o `campo,desc`; campos: `appointmentDate`, `startTime`, `createdAt`, `reservationId`. Sin parámetro se conserva el orden central predeterminado. |

Turnos envía `externalPatientId=user-{id persistido}` a la cátedra; no acepta ese filtro del cliente. Convierte el array y `X-Total-Count` centrales en la envoltura JSON local; no reproduce ese encabezado como contrato propio. Conserva el total y la página, sin filtrar silenciosamente reservas legítimas que no tengan proceso local. Asociaciones locales contradictorias o una respuesta central inválida producen error, no un total reducido ficticio.

Ejemplo de consulta sin datos personales:

```http
GET /api/disponibilidad?profesionalId=1&fecha=2026-11-26
Authorization: Bearer <JWT de usuario>
```

Respuesta ilustrativa; los bloques dependen de ambas fuentes reales:

```json
[{"fecha":"2026-11-26","inicio":"09:00:00","fin":"09:30:00"}]
```

### Errores HTTP locales

Los handlers utilizan `ProblemDetail` y códigos funcionales seguros cuando corresponden. Los rechazos de Spring Security pueden no tener ese mismo cuerpo.

| Código | Condición relevante |
| --- | --- |
| 400 | Parámetros/JSON/datos personales inválidos; rechazo contractual de validación. |
| 401 | JWT ausente/inválido; login incorrecto o usuario no utilizable según la operación. |
| 403 | Permiso insuficiente o intento de operar un proceso ajeno. La consulta individual oculta la existencia mediante 404. |
| 404 | Profesional, proceso o reserva no encontrado conforme a la operación y a su comprobación de propiedad. |
| 409 | Estado incompatible, hold equivalente bloqueante, horario ocupado, duplicado de registro o información ya atendida/incompatible. |
| 410 | Hold o solicitud de información vencidos en operaciones que exigen vigencia. |
| 502/503/504 | La traducción de errores externos depende de la operación, según la tabla siguiente. No se convierten en resultados vacíos. |

| Operación y resolución HTTP | 502 | 503 | 504 |
| --- | --- | --- | --- |
| Disponibilidad: handlers locales de `DisponibilidadController` | Respuesta inválida de Catálogo; configuración, autenticación técnica, solicitud o respuesta inválidas de ocupaciones. | Servicio externo con error o no disponible. | Timeout de Catálogo u ocupaciones. |
| Creación de hold: `HoldExceptionHandler` | Configuración o credencial técnica del POST rechazada. | Resultado incierto/persistencia fallida; otros fallos de Catálogo; cualquier `ConsultaOcupacionesException`. | No lo implementa; esos timeouts se traducen a 503. |
| Confirmación: `ConfirmacionHoldExceptionHandler` | Configuración o credencial técnica rechazada. | Resultado incierto, persistencia fallida o correlación pendiente. | No lo implementa; timeout remoto de confirmación queda incierto y responde 503. |
| Teléfono: `InformacionAdicionalExceptionHandler` | No lo implementa. | Configuración incompleta o persistencia fallida. | No lo implementa; Kafka se publica posteriormente desde la outbox. |
| Consulta: `ConsultaReservasExceptionHandler` | Configuración/credencial rechazada o respuesta central inválida. | Conexión/servicio no disponible o error de acceso a datos manejado. | Timeout de la consulta central. |
| Cancelación: `CancelacionReservaExceptionHandler` | Configuración/credencial técnica rechazada o `CORRELACION_INCONSISTENTE`. | Resultado incierto, conexión/persistencia fallida o verificación incompleta, incluida una respuesta inválida de la consulta central. | Timeout propagado por el caso de uso. |

Si la cancelación ya conserva una intención incierta y el servicio devuelve ese progreso, el controlador responde 202. Si propaga una excepción, se aplica el handler indicado; no todo fallo del POST tiene el mismo resultado HTTP.

La clasificación contractual utiliza HTTP y `code`, no el texto libre del error. El detalle y los códigos específicos están en los [handlers de reserva](../src/main/java/com/example/svcturnos/reserva/infrastructure/web/handler) y [DisponibilidadController](../src/main/java/com/example/svcturnos/disponibilidad/infrastructure/web/controller/DisponibilidadController.java).

## Turnos → Catálogo

`CatalogoRestClientAdapter` consume exclusivamente estos contratos con el JWT validado del usuario, `iss=svc-turnos`, audiencia `svc-catalogo-api` y scope `catalogo:read`:

| GET | DTO externo consumido |
| --- | --- |
| `/api/catalogo/profesionales/{id}` | `id`, `categoryId`, `firstName`, `lastName`, `enabled`, `createdAt`, `updatedAt`; `category` con `id`, `name`, `description`, `enabled`, `createdAt`, `updatedAt`. |
| `/api/catalogo/profesionales/{id}/horarios` | Array de `id`, `professionalId`, `dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes`, `enabled`, `createdAt`, `updatedAt`. |

`dayOfWeek` utiliza `MONDAY`–`SUNDAY`. Los DTO propios se encuentran en [catalogo/infrastructure/client/dto](../src/main/java/com/example/svcturnos/catalogo/infrastructure/client/dto). No se comparten entidades JPA. Se conservan elementos deshabilitados; disponibilidad decide cuáles utilizar. Un profesional existente sin horarios devuelve lista vacía; 404 identifica ausencia, 401/403 autenticación/permisos y 5xx/timeout/conexión son fallos diferenciados. No se convierten en agendas vacías.

## Turnos → REST de cátedra

Todos los endpoints siguientes requieren el JWT **técnico**, distinto del token del paciente.

| Operación | Contrato de petición y respuesta|
| --- | --- |
| `GET /api/appointment-occupancies` | `professionalId`, `from`, `to` inclusivos. Sin paginación. Objeto `professionalId/from/to/occupancies`; cada ocupación contiene `date`, `startTime`, `status` y, para `HELD`, `expiresAt`. No proporciona duración ni fin. |
| `POST /api/appointment-holds` | `professionalId`, `date`, `startTime`, `externalPatientId`. 201: `holdId`, `reservationProcessId`, `status=HELD`, `expiresAt`. |
| `POST /api/appointment-holds/{holdId}/confirm` | `externalPatientId`, `patientFirstName`, `patientLastName`. 202: `reservationProcessId`, `status=WAITING_FOR_PHONE`, `expiresAt`. |
| `GET /api/appointments` | `externalPatientId`, `status`, fechas inclusivas, `page`, `size`, `sort`. Array con `X-Total-Count`; el DTO central incluye datos del paciente que la API propia no expone. |
| `POST /api/appointments/{reservationProcessId}/cancel` | Cuerpo opcional `reason`. 200: `reservationId`, `reservationProcessId`, `status=CANCELLED`, `cancelledAt`. |

Errores relevantes: `HOLD_NOT_FOUND`, `HOLD_EXPIRED`, `HOLD_OWNER_MISMATCH`, `EXTERNAL_PATIENT_MISMATCH`, `RESERVATION_PROCESS_ALREADY_CONFIRMED` y rechazos de validación/ocupación. 401/403 técnicos, 5xx y problemas de transporte se distinguen de rechazos funcionales. Una respuesta perdida a un POST no prueba que no se haya ejecutado. Los adaptadores no reenvían automáticamente esos POST.

La publicación y recepción Kafka, sus campos y garantías se especifican en [reservas y recuperación](reservas-y-recuperacion.md).
