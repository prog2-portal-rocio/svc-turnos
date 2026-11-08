package com.example.svcturnos;

import org.junit.jupiter.api.Test;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@SpringBootTest
class SvcTurnosApplicationTests {

	@Test
	void contextLoads() {
	}

}
