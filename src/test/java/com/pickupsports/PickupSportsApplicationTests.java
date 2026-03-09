package com.pickupsports;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PickupSportsApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the full Spring application context starts successfully with test properties
    }

}
