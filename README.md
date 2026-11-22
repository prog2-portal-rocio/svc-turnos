<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-76%2F100-green) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey) ![Build](https://img.shields.io/badge/Build-OK-green) ![Duplicación](https://img.shields.io/badge/Duplicaci%C3%B3n-2.3%25-orange) ![%POO](https://img.shields.io/badge/%25POO-78%25-yellow) ![Sueltas](https://img.shields.io/badge/Sueltas-0-green) ![D1](https://img.shields.io/badge/D1-2-red) ![D2](https://img.shields.io/badge/D2-100-green) ![D4](https://img.shields.io/badge/D4-80-green) ![D5](https://img.shields.io/badge/D5-100-green) ![D6](https://img.shields.io/badge/D6-100-green) ![D7](https://img.shields.io/badge/D7-100-green)

**Calidad (heurístico, 7 dimensiones):** índice **76/100** · build **OK** · aprobar **no (7/11)** · destacar **no**
Detalle completo en [`docs/EVALUACION.md`](docs/EVALUACION.md).
<!-- calidad:fin -->

# svc-turnos
Servicio backend de turnos y reservas desarrollado para el proyecto integrador de Programación II.

Forma parte de un sistema distribuido compuesto por dos servicios backend (`svc-catalogo` y `svc-turnos`) y una aplicación Android con Kotlin Multiplatform.

## Tecnologías

- Java 25.
- Spring Boot 4.1.2.
- Maven y Maven Wrapper.
- Spring Web MVC.
- JUnit para pruebas.

## Requisitos

- JDK 25.
- Conexión a Internet para descargar las dependencias de Maven en la primera ejecución.

No es necesario instalar Maven por separado, ya que el proyecto incluye Maven Wrapper.

## Ejecución

Desde la raíz del repositorio, ejecutar:

```bash
./mvnw spring-boot:run
```

El servicio utiliza inicialmente el puerto `8080` de Spring Boot.

### Ejecución con Docker y conexión a Catálogo

Preparar la configuración privada a partir de `.env.example`, conservando las
credenciales de PostgreSQL existente. Las rutas `TURNOS_JWT_PRIVATE_KEY_FILE` y
`TURNOS_JWT_PUBLIC_KEY_FILE` deben apuntar a archivos RSA existentes; se montan en
solo lectura. El UID/GID configurado para Turnos debe poder leerlos, sin ampliar
los permisos de la clave privada. Catálogo necesita la pública correspondiente.

Ambos Compose deben usar la red externa `backends-integracion`. Prepararla una
única vez; si ya existe, verificarla y reutilizarla sin eliminarla:

```bash
docker network inspect backends-integracion --format '{{.Name}} ({{.Driver}})'
# Solo si la red no existe:
docker network create --driver bridge backends-integracion
# Desde la raíz de Turnos; no imprimir la configuración completa con secretos.
docker compose config --quiet
```

Para comprobar la comunicación, Catálogo debe incorporar el cambio del issue #42.
`svc-turnos` pertenece a `default` e `integracion`; su PostgreSQL solo a `default`.
Se conservan los puertos publicados en loopback (HTTP 8080 y PostgreSQL 5433 por
defecto) y el volumen existente. Usar el mismo directorio/nombre de proyecto
Compose para reutilizarlo. No borrar volúmenes ni ejecutar comandos de limpieza.

| Ejecución de Turnos | URL predeterminada de Catálogo |
| --- | --- |
| Java en Ubuntu | `http://localhost:8081` |
| Docker Compose, red compartida | `http://svc-catalogo:8080` |

No definir `CATALOGO_BASE_URL` permite usar estos valores según el modo elegido.
Si `.env` o el entorno conservan un valor anterior, revisarlo: prevalece sobre el
predeterminado de Compose. Un valor vacío no equivale a una variable ausente en
la configuración Java; para usar su predeterminado, dejarla sin definir.
Spring Boot en Ubuntu recibe las variables del entorno/IDE, no carga `.env`
automáticamente. Dentro del contenedor, `localhost` es el propio Turnos.

Se conserva `extra_hosts` para posibles integraciones configuradas hacia Ubuntu;
la conexión con Catálogo ya no lo necesita. Sin revisar la configuración privada
no puede descartarse que otra URL dependa de `host.docker.internal`.

Después del arranque, comprobar que solo los backends estén en la red compartida:

```bash
docker network inspect backends-integracion --format '{{range .Containers}}{{println .Name}}{{end}}'
docker compose exec -T svc-turnos curl -sS --output /dev/null --write-out '%{http_code}\n' --max-time 5 http://svc-catalogo:8080/api/catalogo/profesionales
```

El GET sin token debe devolver 401; demuestra conectividad HTTP, no acceso a datos.
La comprobación autenticada requiere JWT RS256 de Turnos con `catalogo:read`.
La red no garantiza acceso por ZeroTier a REST/Kafka de cátedra ni vigencia del
catálogo. Mantener deshabilitada la publicación Kafka durante pruebas solo de
conectividad para no enviar información pendiente de la outbox.

## Pruebas

Para compilar el proyecto y ejecutar las pruebas:

```bash
./mvnw clean verify
```

## Arquitectura

El proyecto utiliza una estructura inicial basada en arquitectura hexagonal, separando las responsabilidades en tres capas:

```text
src/main/java/com/example/svcturnos/
├── SvcTurnosApplication.java
└── turnos/
    ├── domain/
    │   ├── model/
    │   └── ports/
    │       ├── in/
    │       └── out/
    ├── application/
    │   └── usecases/
    └── infrastructure/
        └── persistence/
```

- **Domain:** modelos de negocio y contratos de entrada y salida.
- **Application:** casos de uso que coordinan la lógica de la aplicación.
- **Infrastructure:** adaptadores que implementan la comunicación con tecnologías externas.

Actualmente, estas carpetas constituyen la estructura inicial del proyecto. Los componentes se incorporarán progresivamente.

## Próximas funcionalidades

En las siguientes issues se implementarán:

- Persistencia local con PostgreSQL y Flyway.
- Registro y autenticación de usuarios mediante JWT.
- Almacenamiento seguro de contraseñas con Argon2id.
- Integración con el servicio de Catálogo y la cátedra.
- Disponibilidad, reservas de turnos y procesamiento de eventos.

Estas funcionalidades todavía no forman parte de la implementación actual.
