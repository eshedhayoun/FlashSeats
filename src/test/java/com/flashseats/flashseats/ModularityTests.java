package com.flashseats.flashseats;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Mechanically enforces the module boundaries the documentation asserts.
 *
 * <p>This test exists <em>before</em> the modules have content, on purpose: a boundary violation
 * then fails the build the day it is written rather than being discovered after seven modules have
 * grown into each other. It is the only thing standing between a modular monolith and a big ball of
 * mud with packages.
 *
 * <p>The permitted graph (ADR-005, ADR-025, ADR-031):
 *
 * <pre>
 *   shared                                       open module; everyone may depend on it
 *   hold     --&gt; queue, catalog
 *   queue    --&gt; catalog                         window check + remaining stock (ADR-031)
 *   order    --&gt; hold, catalog, payment, queue
 *   saleflow --&gt; queue, hold, order, catalog     read-only leaf; nothing depends on it
 * </pre>
 *
 * <p>Note what is absent: {@code payment} calls no facade at all. Adding one would make the graph
 * cyclic, because the webhook path already runs {@code payment -> order} as an event.
 */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of("com.flashseats");

    @Test
    void moduleGraphIsAcyclicAndBoundariesAreRespected() {
        MODULES.verify();
    }

    /**
     * Writes the module canvas and PlantUML diagrams to {@code target/spring-modulith-docs}, so the
     * documented graph can be checked against the built one rather than trusted.
     */
    @Test
    void writesDocumentation() throws Exception {
        new Documenter(MODULES).writeDocumentation();
    }
}
