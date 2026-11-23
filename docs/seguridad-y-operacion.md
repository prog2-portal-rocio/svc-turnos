# Seguridad, configuración y operación

## Autenticación y autorización

Fuentes: [SecurityConfig](../src/main/java/com/example/svcturnos/usuario/infrastructure/security/SecurityConfig.java), [JwtConfig](../src/main/java/com/example/svcturnos/usuario/infrastructure/security/JwtConfig.java) y [adaptadores de seguridad de reserva](../src/main/java/com/example/svcturnos/reserva/infrastructure/security).

El registro y login son públicos únicamente mediante POST. El resto de solicitudes requiere autenticación; `GET /api/disponibilidad` exige `SCOPE_catalogo:read`. Se utiliza Resource Server JWT, sesión stateless, sin formulario ni HTTP Basic. CSRF se exceptúa para `/api/**`; no se desactiva globalmente. No se configura una política CORS específica ni autenticación mediante cookies.

Turnos firma con RS256 y valida firma, `iss=svc-turnos`, audiencia `svc-turnos-api`, sujeto no blanco, vencimiento obligatorio y validaciones temporales de Spring Security. Los tokens emitidos incluyen sujeto con ID persistido, login, audiencia de Turnos y `svc-catalogo-api`, scope `catalogo:read`, emisión y vencimiento a dos horas. No se emite `catalogo:sync:read` para usuarios comunes ni se implementan refresh tokens.

`UsuarioAutenticadoAdapter` deriva la identidad del JWT validado y comprueba ID, login y usuario activo en PostgreSQL. El paciente externo es estable: `user-{id}`. La propiedad se verifica antes de modificar reservas; los clientes no eligen `usuarioId`, paciente externo ni correlaciones Kafka. Las consultas individuales ocultan procesos ajenos mediante 404. La cancelación también comprueba reservas centrales legítimas sin proceso local y rechaza asociaciones contradictorias.

[Argon2idPasswordHasherAdapter](../src/main/java/com/example/svcturnos/usuario/infrastructure/security/Argon2idPasswordHasherAdapter.java) conserva `PasswordHasherPort`: memoria 19.456 KiB, 2 iteraciones, paralelismo 1, salt aleatorio de 16 bytes y hash de 32 bytes. No aplica SHA-256 previo ni compatibilidad BCrypt. El registro nunca persiste la contraseña en texto plano ni la devuelve en su DTO.

### Separación de identidades

| Comunicación | Credencial |
| --- | --- |
| Cliente → Turnos | JWT de usuario firmado por Turnos. |
| Turnos → Catálogo | El mismo JWT validado del usuario; Catálogo verifica su pública, issuer, audiencia y `catalogo:read`. |
| Turnos → REST de cátedra | JWT técnico configurado externamente, independiente del paciente. |
| Turnos → Kafka | Configuración de integración/broker y topics asignados; no se utiliza el JWT del usuario como credencial del broker. |

No existe login ni renovación automática del JWT técnico. Debe obtenerse mediante el procedimiento de la cátedra y actualizarse externamente. Las URLs HTTP locales/privadas no proporcionan cifrado de transporte; no hay proxy TLS configurado en este repositorio.

## Configuración externa

Fuentes de verdad: [application.properties](../src/main/resources/application.properties), [compose.yaml](../compose.yaml) y [.env.example](../.env.example). Compose interpreta `.env`; Spring Boot ejecutado en Ubuntu no lo carga automáticamente. Nunca se deben imprimir configuraciones completas que puedan contener secretos.

| Variable | Valor predeterminado / condición |
| --- | --- |
| `TURNOS_DB_PASSWORD` | Obligatoria; conservar la correspondiente al volumen existente. |
| `TURNOS_DB_PORT` | Compose: 5433, publicado en `127.0.0.1`; puerto interno PostgreSQL 5432. |
| `TURNOS_HTTP_PORT` | Compose: 8080 en loopback; Java interno 8080. |
| `TURNOS_JWT_PRIVATE_KEY_FILE`, `TURNOS_JWT_PUBLIC_KEY_FILE` | Compose: rutas absolutas del host a archivos existentes, sin prefijo `file:`. |
| `TURNOS_CONTAINER_UID`, `TURNOS_CONTAINER_GID` | Compose: 10001/10001, sustituibles por una identidad no root que pueda leer los archivos. |
| `TURNOS_JWT_PRIVATE_KEY_LOCATION`, `TURNOS_JWT_PUBLIC_KEY_LOCATION` | Java fuera de Compose: recursos `file:/ruta/externa/...`. Compose los fija a `/run/keys/` con prefijo `file:`. |
| `CATALOGO_BASE_URL` | Java: `http://localhost:8081`; Compose: `http://svc-catalogo:8080`. |
| `CATALOGO_CONNECT_TIMEOUT`, `CATALOGO_RESPONSE_TIMEOUT` | 3s / 5s. |
| `CATEDRA_BASE_URL`, `CATEDRA_TECHNICAL_TOKEN` | Vacíos por defecto; requeridos para operaciones REST de integración. |
| `CATEDRA_CONNECT_TIMEOUT`, `CATEDRA_RESPONSE_TIMEOUT` | 3s / 5s. |
| `KAFKA_BOOTSTRAP_SERVERS` | Direcciones accesibles de los brokers asignados. |
| `KAFKA_GROUP_ID` | Consumer group de la integración; no confundir con identidad del usuario. |
| `KAFKA_APPOINTMENT_ACTIONS_TOPIC` | Topic completo asignado de acciones. |
| `KAFKA_LISTENER_AUTO_STARTUP` | `false`; controla consumidor y scheduler de recuperación de resultados. |
| `CATEDRA_GROUP_ID` | `groupId` contractual incluido en los mensajes de salida y validado en resultados. |
| `KAFKA_APPOINTMENT_PHONE_TOPIC` | Topic completo asignado de teléfono. |
| `KAFKA_PHONE_PUBLICATION_ENABLED` | `false`; permite aceptar teléfono por HTTP y habilita el scheduler publicador/depurador de outbox. No condiciona la creación del adaptador productor. |

Una variable `CATALOGO_BASE_URL` vacía no equivale a ausente para Java; para usar su valor predeterminado debe quedar sin definir. En Compose, una configuración privada anterior puede sustituir la URL de red compartida.

Los parámetros adicionales declarados como propiedades Spring incluyen `turnos.kafka.phone-send-timeout=20s`, `turnos.kafka.outbox-poll-interval=5000` y los valores predeterminados de `turnos.kafka.results-poll-interval=5000`, `turnos.kafka.results-initial-delay=0`, `turnos.kafka.outbox-initial-delay=0` de los schedulers. El Compose no declara variables dedicadas para todos esos ajustes; una modificación operativa requiere proporcionar configuración Spring externa compatible.

El productor configura `acks=all`, idempotencia y 3 reintentos; límites `max.block.ms=3000`, `request.timeout.ms=5000`, `delivery.timeout.ms=15000`. El consumidor utiliza bytes, `enable-auto-commit=false`, `manual_immediate`, `earliest` y no crea topics automáticamente. Estas opciones no reemplazan la deduplicación funcional ni el control durable de intentos.

## Claves RSA

Se requiere un par RSA de al menos 2048 bits: privada PEM PKCS#8 y pública PEM X.509. `JwtConfig` comprueba tamaño, correspondencia y capacidad de firma/verificación al iniciar. No se incluyen claves en la imagen ni en Git. La misma pública debe estar configurada en Catálogo para validar los tokens de Turnos.

Para un entorno nuevo sin claves existentes, el siguiente procedimiento genera un par **fuera del repositorio**. No debe sustituir el par de un entorno activo sin coordinar su cambio con Catálogo y los tokens emitidos:

```bash
mkdir -p "$HOME/.config/svc-turnos/keys"
chmod 700 "$HOME/.config/svc-turnos/keys"
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$HOME/.config/svc-turnos/keys/turnos-private.pem"
openssl pkey -in "$HOME/.config/svc-turnos/keys/turnos-private.pem" -pubout -out "$HOME/.config/svc-turnos/keys/turnos-public.pem"
```

Configurar las rutas absolutas en las variables `*_FILE` de Compose. El UID/GID del proceso Java debe poder atravesar los directorios y leer los archivos; una privada 0600 requiere un propietario compatible. No resolver errores de permisos haciéndola legible por todos ni ejecutando Java como root. Los montajes son de solo lectura y no crean rutas de origen ausentes.

## Ejecución y conectividad

Los comandos principales de Docker se mantienen en el [README](../README.md). El [Dockerfile](../Dockerfile) compila con Maven Wrapper/JDK 25 y omite pruebas durante la construcción. La etapa final usa JRE 25, instala `curl` para la sonda HTTP y ejecuta el JAR con usuario no privilegiado. La suite se ejecuta por separado.

Dentro de Compose, la conexión es `jdbc:postgresql://postgres:5432/turnos_db`, usuario `turnos_user`. El volumen `turnos_postgres_data` se monta en `/var/lib/postgresql`, conforme a la imagen PostgreSQL 18. El backend espera el healthcheck de PostgreSQL; Flyway aplica las migraciones al arrancar. Cambiar la contraseña de entorno no cambia automáticamente la contraseña de una base ya inicializada.

Solo los backends pertenecen a `backends-integracion`. `svc-catalogo:8080` debe resolver al servicio de Catálogo conectado a esa red. `localhost` en Turnos apunta al contenedor de Turnos. Se conserva `host.docker.internal:host-gateway` para configuraciones hacia Ubuntu, pero no se requiere para Catálogo en la red compartida. Un puerto del host publicado únicamente en loopback no es una alternativa equivalente a un servicio compartido por DNS Docker.

REST y brokers de cátedra requieren conectividad desde el contenedor, no solo desde Ubuntu/ZeroTier. También deben ser accesibles las direcciones anunciadas por los brokers. No se afirma accesibilidad externa sin una verificación real. Redis pertenece a Catálogo y no se configura en Turnos.

Para ejecutar Java directamente, proporcionar las variables en el entorno o IDE, las ubicaciones `*_LOCATION`, y acceso a PostgreSQL en `localhost:5433`:

```bash
java -version
./mvnw spring-boot:run
```

Para iniciar únicamente PostgreSQL mediante el Compose existente, con su configuración requerida preparada:

```bash
docker compose up -d postgres
```

No ejecutar simultáneamente Java local y el backend Docker sobre el mismo puerto. El Compose puede exigir sus variables de montaje aunque se seleccione solo PostgreSQL, porque valida el archivo completo.

## Diagnóstico

| Síntoma | Verificación segura |
| --- | --- |
| Compose no valida | `docker compose config --quiet`; comprobar variables obligatorias sin imprimir valores. |
| Red externa ausente | `docker network inspect backends-integracion --format '{{.Name}}'`; crearla una sola vez según README. |
| Fallo al leer RSA | Formato, correspondencia de claves y permisos del UID/GID; nunca mostrar su contenido. |
| Flyway/datasource falla | Logs de arranque, disponibilidad de PostgreSQL y credenciales correspondientes al volumen. No borrar datos para corregirlo. |
| Catálogo no resuelve | Comprobar que ambos backends estén en la red compartida y que la URL no conserve un valor antiguo. |
| 401 de Catálogo con usuario autenticado | Pública de Turnos, issuer, audiencia, vencimiento; 403 apunta a scope insuficiente. |
| REST técnico rechazado | Vigencia y configuración del JWT técnico; no sustituirlo por el JWT del usuario. |
| No se procesan/publican eventos | Flags habilitados, topics, consumer group, conectividad y logs seguros; verificar estados durables antes de repetir operaciones. |
| Consumidor detenido tras error | Corregir dependencia/persistencia y reiniciar el servicio de forma controlada. El scheduler no reinicia por sí mismo el listener detenido. |

Comprobación HTTP interna sin autenticación, desde Turnos y sin crear datos:

```bash
docker compose exec -T svc-turnos curl --silent --output /dev/null --write-out '%{http_code}\n' --max-time 5 http://svc-catalogo:8080/api/catalogo/profesionales
```

El resultado esperado es 401 si Catálogo está accesible y protegido; no demuestra acceso autenticado, catálogo actualizado ni funcionamiento de cátedra. La sonda de Turnos también comprueba solo HTTP/seguridad. No hay un endpoint Actuator de salud implementado por esta aplicación.

Para recargar variables de Compose se requiere `docker compose up -d`; `restart` no incorpora cambios de entorno. Habilitar el publicador puede enviar intenciones previamente persistidas: no debe activarse como una simple prueba de conectividad.

## Privacidad y límites operativos

No se deben registrar tokens, claves ni teléfonos. El productor evita incluir payloads sensibles en sus mensajes de error. La outbox almacena temporalmente el teléfono en PostgreSQL sin cifrado específico de aplicación y lo elimina bajo las condiciones documentadas en [reservas](reservas-y-recuperacion.md). La inbox conserva bytes/JSON para diagnóstico y recuperación, que pueden contener información personal contractual; no dispone de una política automática general de retención. Su acceso y los respaldos requieren control operativo.

Los logs y los estados persistidos son evidencia diagnóstica; no sustituyen la reconciliación de un resultado incierto. No existe una API administrativa pública de inbox/outbox ni un mecanismo general que resuelva toda contradicción con la cátedra. El estado del cliente KMP y los límites contractuales se delimitan en [arquitectura](arquitectura.md) y [recuperación](reservas-y-recuperacion.md).
