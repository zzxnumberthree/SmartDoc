package com.spe.smartdocjp;

import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("deterministic-test")
@Import(DeterministicAiTestConfiguration.class)
class SmartDocJpApplicationTests {

    @Test
    void contextLoads() {
    }

}
