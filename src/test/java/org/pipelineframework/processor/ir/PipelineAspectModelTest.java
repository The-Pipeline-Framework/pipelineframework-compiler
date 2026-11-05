package org.pipelineframework.processor.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineAspectModelTest {
    @Test
    void retainsImmutableTypedSelectorsSeparatelyFromProviderConfiguration() {
        var selectors = new ArrayList<>(List.of("Review", "Result"));
        var aspect = new PipelineAspectModel("persist", AspectScope.GLOBAL, AspectPosition.AFTER_STEP,
            Map.of("targetTypes", selectors, "provider", "store"));
        selectors.clear();
        assertEquals(List.of("Review", "Result"), aspect.targetTypes());
        assertEquals(Map.of("provider", "store"), aspect.config());
        assertThrows(UnsupportedOperationException.class, () -> aspect.targetTypes().add("Other"));
    }

    @Test
    void rejectsMalformedSelectorsAtModelConstruction() {
        for (Object invalid : List.of("Review", List.of(), List.of(1), List.of(" "))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> new PipelineAspectModel(
                "persist", AspectScope.GLOBAL, AspectPosition.AFTER_STEP, Map.of("targetTypes", invalid)));
            assertTrue(failure.getMessage().contains("persist"));
            assertTrue(failure.getMessage().contains("targetTypes"));
        }
    }
}
