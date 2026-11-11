<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-58%2F100-yellow) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey) ![Build](https://img.shields.io/badge/Build-no%20compila-red) ![Duplicación](https://img.shields.io/badge/Duplicaci%C3%B3n-10.4%25-orange) ![%POO](https://img.shields.io/badge/%25POO-80%25-green) ![Sueltas](https://img.shields.io/badge/Sueltas-0-green) ![D1](https://img.shields.io/badge/D1-9-red) ![D2](https://img.shields.io/badge/D2-100-green) ![D4](https://img.shields.io/badge/D4-40-orange) ![D5](https://img.shields.io/badge/D5-100-green) ![D6](https://img.shields.io/badge/D6-30-red) ![D7](https://img.shields.io/badge/D7-100-green)

**Calidad (heurístico, 7 dimensiones):** índice **58/100** · build **no compila** · aprobar **no (5/11)** · destacar **no**
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
