# Pruebas automatizadas y alcance de verificación

## Ejecución

Desde la raíz, con **JDK 25** seleccionado:

```bash
java -version
./mvnw clean verify
```

La suite completa requiere Docker accesible para Testcontainers y las imágenes de PostgreSQL 18/Kafka. Maven Wrapper descarga Maven y dependencias cuando no están disponibles. `-o` solo evita descargas Maven: no garantiza disponibilidad de imágenes Docker ni reemplaza un daemon accesible.

Las integraciones utilizan bases y brokers aislados; no necesitan modificar las bases de desarrollo ni conectarse a la cátedra. Las claves RSA y credenciales utilizadas son exclusivamente sintéticas de prueba. No se deben sustituir sus propiedades por credenciales de producción.

Para ejecutar el flujo integrado de reservas con contenedores:

```bash
./mvnw -Dtest=FlujoReservasIntegrationTest test
```

Selección de pruebas de aplicación sin iniciar Testcontainers:

```bash
./mvnw -Dtest=RegistrarUsuarioServiceTest,AutenticarUsuarioServiceTest,RecuperacionConfirmacionTest,RecuperarResultadosReservaServiceTest test
```

Esta selección no equivale a verificar la suite completa ni las garantías transaccionales de PostgreSQL.

## Cobertura por comportamiento

Los archivos se encuentran en [src/test/java/com/example/svcturnos](../src/test/java/com/example/svcturnos).

| Área | Pruebas representativas | Evidencia cubierta |
| --- | --- | --- |
| Usuarios y seguridad | `Argon2idAutenticacionIntegrationTest`, `JwtSecurityHttpTest`, `JwtConfigTest`, `JwtTokenAdapterTest`, servicios/controladores de usuario. | Registro/login, Argon2id, persistencia, validación de nombres, firma/claims RS256 y acceso protegido. |
| Catálogo y disponibilidad | `CatalogoJwtHttpIntegrationTest`, `DisponibilidadHttpIntegrationTest`, `DisponibilidadHttpTest`, `AgendaDiariaTest`. | Contratos HTTP, propagación de JWT, agenda, ocupaciones vigentes, fechas/estados y errores externos. |
| Holds y confirmación | `CrearHoldPersistenceIntegrationTest`, `ConfirmarHoldPersistenceIntegrationTest`, pruebas de servicios/adaptadores/controladores. | Propiedad, intención previa al POST, concurrencia, vencimiento, errores conocidos e incertidumbre. |
| Inbox | `ReservasKafkaInboxIntegrationTest`, pruebas de decoder/correlación. | Kafka aislado, recepción durable, offsets, duplicados, mensajes inválidos y correlación. |
| Outbox | `InformacionOutboxIntegrationTest`, pruebas de productor/publicador/scheduler. | Intención durable, exclusión de duplicados, acuse, timeout, concesiones, reintentos y protección del teléfono. |
| Resultados | `ResultadosReservaIntegrationTest` y pruebas de política/servicios. | Cinco eventos, reserva definitiva, rechazo reintentable, fuera de orden, atomicidad e idempotencia. |
| Consulta/cancelación | Pruebas de controladores, adaptadores y persistencia de consulta/cancelación. | Aislamiento, reservas sin proceso local, paginación, verificación incompleta, errores e incertidumbre. |
| Recuperación | `RecuperacionConfirmacionTest`, `ConfirmarHoldPersistenceIntegrationTest`, `RecuperarResultadosReservaServiceTest`, `ResultadosReservaSchedulerTest`, `ResultadosReservaIntegrationTest`. | Confirmación incierta con evidencia Kafka, búsqueda dirigida con más de 1.000 pendientes ajenos, paginación de resultados, fallos parciales y estados definitivos. |
| Flujo transversal | `FlujoReservasIntegrationTest` y `IntegracionRestSimulada`. | Registro y login, JWT real de pruebas, disponibilidad, hold, confirmación, inbox/outbox, resultado final, consulta, cancelación y aislamiento entre dos usuarios; variante de respuesta 202 perdida y expiración. |

Las migraciones se ejecutan contra PostgreSQL aislado. `ConfirmarHoldPersistenceIntegrationTest` comprueba la actualización desde V5, y `Argon2idAutenticacionIntegrationTest.migracionDesdeV9AmpliaColumnasSinCambiarUsuariosPreexistentes()` verifica V9→V10 conservando usuarios existentes. El esquema operativo no se modifica manualmente para preparar la suite.

## Qué es real y qué se simula

En `FlujoReservasIntegrationTest` se utiliza el contexto real de Spring Boot y MockMvc: seguridad, Argon2id, emisión/validación JWT, controladores, casos de uso, clientes REST y adaptadores de persistencia. PostgreSQL y Kafka se ejecutan mediante Testcontainers; el consumidor y productor utilizan el broker aislado.

[IntegracionRestSimulada](../src/test/java/com/example/svcturnos/reserva/support/IntegracionRestSimulada.java) proporciona respuestas HTTP contractuales de Catálogo y cátedra desde un servidor local. No ejecuta los backends externos ni verifica su despliegue real. Los identificadores y datos son de prueba.

Para controlar tiempos, la suite coordina explícitamente ciclos de publicación/recuperación. La recuperación se verifica mediante evidencia persistida y nuevas instancias de coordinación/reinicio del listener donde corresponde; esto no equivale a reiniciar una JVM, Docker Compose o una instalación real completa.

## Informes y CI

- Surefire: `target/surefire-reports` contiene resultados por clase y detalle de fallos.
- JaCoCo tras `verify`: `target/site/jacoco/index.html` y `target/site/jacoco/jacoco.xml`.
- [CI](../.github/workflows/ci.yml): JDK 25, servicio PostgreSQL 18, `./mvnw --batch-mode clean verify` y análisis SonarQube con secreto gestionado en GitHub. Se activa en push a `main`, `develop`, `testing`, `feature/**` y en PR dirigidos a esas ramas; un push aislado a `docs/**` no coincide con ese filtro.

Un `BUILD SUCCESS` debe corresponder a la ejecución concreta y su resumen de pruebas. Esta documentación no fija una cantidad de tests ni un estado del Quality Gate que puedan quedar desactualizados. El resultado de SonarQube debe consultarse para el commit/PR evaluado; no demuestra por sí mismo integración real ni ausencia de defectos.
