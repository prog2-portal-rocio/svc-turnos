package com.example.svcturnos;

import org.junit.jupiter.api.Test;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@SpringBootTest
@Testcontainers
class SvcTurnosApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

	@Test
	void contextLoads() {
	}

}
