package org.pipelineframework.processor.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoordinationHostTest {
    @Test
    void defaultsToNativeAndAcceptsAwsDurableSpelling() {
        assertEquals(CoordinationHost.NATIVE, CoordinationHost.parse(null));
        assertEquals(CoordinationHost.AWS_DURABLE, CoordinationHost.parse("aws-durable"));
    }

    @Test
    void rejectsUnknownHosts() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> CoordinationHost.parse("generic-cloud"));
        assertTrue(failure.getMessage().contains("NATIVE or AWS_DURABLE"));
    }
}
