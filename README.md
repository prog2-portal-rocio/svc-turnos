<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-76%2F100-green) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey) ![Build](https://img.shields.io/badge/Build-OK-green) ![Duplicación](https://img.shields.io/badge/Duplicaci%C3%B3n-2.3%25-orange) ![%POO](https://img.shields.io/badge/%25POO-78%25-yellow) ![Sueltas](https://img.shields.io/badge/Sueltas-0-green) ![D1](https://img.shields.io/badge/D1-2-red) ![D2](https://img.shields.io/badge/D2-100-green) ![D4](https://img.shields.io/badge/D4-80-green) ![D5](https://img.shields.io/badge/D5-100-green) ![D6](https://img.shields.io/badge/D6-100-green) ![D7](https://img.shields.io/badge/D7-100-green)

**Calidad (heurístico, 7 dimensiones):** índice **76/100** · build **OK** · aprobar **no (7/11)** · destacar **no**
Detalle completo en [`docs/EVALUACION.md`](docs/EVALUACION.md).
<!-- calidad:fin -->
# svc-turnos

Servicio de usuarios, disponibilidad y procesos de reserva de Programación II. Consulta Catálogo y coordina operaciones REST y Kafka con la cátedra; conserva el progreso local en PostgreSQL.

## Tecnologías y requisitos

Java 25, Spring Boot 4.1.2, Maven Wrapper, Spring Security, PostgreSQL 18, Flyway y Spring Kafka. Pruebas con JUnit, Mockito y Testcontainers.

- Docker Engine y Docker Compose v2 para ejecutar el entorno y las pruebas con contenedores.
- JDK 25 para ejecutar Maven en Ubuntu; no se requiere Maven instalado. Docker construye con su propio JDK 25.
- Acceso a los repositorios de dependencias e imágenes en la primera ejecución.
- Par de claves RSA externo al repositorio y configuración de PostgreSQL. Para el flujo completo: Catálogo y acceso a REST/Kafka de cátedra mediante la red correspondiente.

## Configuración

Preparar un archivo privado `.env` a partir de [.env.example](.env.example), sin versionarlo ni imprimir sus valores. Conservar la contraseña de una base existente.

| Variable | Uso |
| --- | --- |
| `TURNOS_DB_PASSWORD` | Contraseña de PostgreSQL, obligatoria. |
| `TURNOS_JWT_PRIVATE_KEY_FILE`, `TURNOS_JWT_PUBLIC_KEY_FILE` | Rutas absolutas a archivos RSA del host, obligatorias en Compose. Montajes de solo lectura. |
| `TURNOS_CONTAINER_UID`, `TURNOS_CONTAINER_GID` | Identidad no root con permiso de lectura de las claves; predeterminado 10001. |
| `TURNOS_HTTP_PORT`, `TURNOS_DB_PORT` | Puertos del host, predeterminados 8080 y 5433, publicados en loopback. |
| `CATALOGO_BASE_URL` | En Compose: `http://svc-catalogo:8080` si no se define. |
| `CATEDRA_BASE_URL`, `CATEDRA_TECHNICAL_TOKEN` | REST de cátedra y JWT técnico para operaciones de reserva. |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_GROUP_ID`, `KAFKA_APPOINTMENT_ACTIONS_TOPIC` | Conexión y consumo de acciones asignados por la integración técnica. |
| `CATEDRA_GROUP_ID`, `KAFKA_APPOINTMENT_PHONE_TOPIC` | Identidad de integración y topic de publicación del teléfono. |
| `KAFKA_LISTENER_AUTO_STARTUP`, `KAFKA_PHONE_PUBLICATION_ENABLED` | Ambos `false` por defecto; habilitar con configuración válida para completar el flujo asincrónico. |

Los formatos de claves, timeouts, permisos y la ejecución sin Docker se especifican en [seguridad y operación](docs/seguridad-y-operacion.md).

## Ejecución con Docker Compose

Desde la raíz del repositorio, comprobar si existe la red externa compartida:

```bash
docker network inspect backends-integracion --format '{{.Name}} ({{.Driver}})'
```

Solo si no existe, crearla una vez:

```bash
docker network create --driver bridge backends-integracion
```

Validar, construir e iniciar:

```bash
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose logs --tail=100 svc-turnos
```

PostgreSQL conserva `turnos_postgres_data`; Flyway aplica V1–V10 al iniciar. Usar el mismo directorio/nombre de proyecto Compose para reutilizar el volumen existente. Catálogo debe estar conectado a `backends-integracion`; su PostgreSQL permanece en su red privada.

La sonda utilizada por Compose comprueba HTTP y seguridad, sin consultar dependencias ni crear reservas. Con el puerto predeterminado, debe obtenerse **401**:

```bash
curl --silent --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8080/api/reservas/holds
```

No es una consulta funcional de holds ni garantiza la disponibilidad de Catálogo o cátedra.

Detener sin eliminar contenedores ni datos:

```bash
docker compose stop
```

Para iniciarlos nuevamente: `docker compose start`. Para aplicar cambios de configuración: `docker compose up -d`. No utilizar `down -v` ni comandos de limpieza de volúmenes.

## Pruebas

Con JDK 25 y Docker disponible, ejecutar la suite completa, incluidas las integraciones PostgreSQL/Kafka mediante Testcontainers:

```bash
java -version
./mvnw clean verify
```

Ejecutar únicamente el flujo integrado de reservas, también con Testcontainers:

```bash
./mvnw -Dtest=FlujoReservasIntegrationTest test
```

Las dependencias REST externas se simulan; no se utilizan cuentas ni reservas reales. Véanse la [matriz de pruebas y los informes](docs/pruebas.md).

## Documentación técnica

- [Arquitectura, límites y trazabilidad de la consigna](docs/arquitectura.md).
- [Contratos REST e integración](docs/contratos.md).
- [Modelo de datos, migraciones y estados](docs/datos-y-estados.md).
- [Flujo de reservas, mensajería y recuperación](docs/reservas-y-recuperacion.md).
- [Seguridad, configuración y operación](docs/seguridad-y-operacion.md).
- [Pruebas y límites de verificación](docs/pruebas.md).
