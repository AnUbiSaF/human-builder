package com.humanbuilder.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementControllerTest {

    @Test
    void finalFlightEndpointRequiresPreciseVerticalAlignment() {
        assertTrue(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.04));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.081));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.95));
    }

    @Test
    void finalFlightEndpointRequiresSafeHorizontalAlignment() {
        assertTrue(MovementController.isWithinPreciseFlightEndpoint(0.119, 0.01));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.12, 0.01));
    }
}
