<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-sin%20c%C3%B3digo-lightgrey) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey)

**Calidad (7 dimensiones):** sin código en `main` (no evaluable).
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
