package com.spe.smartdocjp.integration;

import com.spe.smartdocjp.support.DeterministicAiTestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Default, non-Docker vertical slice. H2 runs in MySQL compatibility mode but is not MySQL proof.
 */
@Import(DeterministicAiTestConfiguration.class)
class DeterministicVerticalSliceTest extends AbstractVerticalSliceIntegrationTest {
}
