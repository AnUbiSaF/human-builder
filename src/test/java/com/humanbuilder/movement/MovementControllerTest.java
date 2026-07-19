package com.humanbuilder.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementControllerTest {

    @Test
    void finalFlightEndpointRequiresPreciseVerticalAlignment() {
        assertTrue(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.119));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.12));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.05, 0.95));
    }

    @Test
    void finalFlightEndpointRequiresSafeHorizontalAlignment() {
        assertTrue(MovementController.isWithinPreciseFlightEndpoint(0.119, 0.01));
        assertFalse(MovementController.isWithinPreciseFlightEndpoint(0.12, 0.01));
    }

    @Test
    void finalVerticalCaptureConsumesSmallFractionalRemainderExactly() {
        assertEquals(0.006,
                MovementController.endpointVerticalVelocity(0.006), 1.0e-9);
        assertEquals(-0.018,
                MovementController.endpointVerticalVelocity(-0.018), 1.0e-9);
    }

    @Test
    void finalVerticalCaptureCapsLargeCorrectionsWithoutChangingDirection() {
        double upward = MovementController.endpointVerticalVelocity(0.30);
        double downward = MovementController.endpointVerticalVelocity(-0.30);

        assertTrue(upward > 0.0 && upward < 0.30);
        assertTrue(downward < 0.0 && downward > -0.30);
        assertEquals(-upward, downward, 1.0e-9);
    }
}
