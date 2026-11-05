package org.pipelineframework.processor.ir;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * Contains only semantic information derived from pipeline aspect configurations.
 * This class captures all the essential information needed to apply pipeline aspects.
 *
 * <p>GLOBAL aspects apply to all steps present in the pipeline definition at generation time.
 * Future steps added later require regeneration. STEPS scope is reserved for future extensions.
 * In the current version, STEPS scope MUST be empty or treated as GLOBAL with a warning.</p>
 *
 * <p>When multiple aspects share the same order and position, execution order is deterministic
 * but unspecified; implementations MUST preserve declaration order. Order is a signed integer.
 * Lower values execute closer to the step boundary. Negative values are allowed and execute
 * before zero-valued aspects. BEFORE_STEP aspects always execute before the step, and
 * AFTER_STEP aspects always execute after the step, regardless of order value.</p>
 *
 * @param name Gets the name of the aspect.
 * @param scope Gets the scope of the aspect application (GLOBAL or STEPS).
 * @param position Gets the position of the aspect relative to the step (BEFORE_STEP or AFTER_STEP).
 * @param order Gets the order of application (lower executes closer to the step boundary, default is 0).
 * @param config Gets the provider configuration for the aspect.
 * @param targetTypes Concrete or union contract selectors parsed from the authored configuration.
 */
public record PipelineAspectModel(
        String name,
        AspectScope scope,
        AspectPosition position,
        int order,
        Map<String, Object> config,
        List<String> targetTypes
) {
    /**
     * Creates a new PipelineAspectModel instance.
     *
     * @param name the name of the aspect
     * @param scope the scope of the aspect application
     * @param position the position of the aspect relative to the step
     * @param order the order of application (lower executes closer to the step boundary)
     * @param config the free-form configuration for the aspect
     */
    public PipelineAspectModel(String name, AspectScope scope, AspectPosition position,
            int order, Map<String, Object> config) {
        this(name, scope, position, order, config, readTargetTypes(name, config));
    }

    public PipelineAspectModel {
        targetTypes = List.copyOf(targetTypes);
        if (targetTypes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("targetTypes must contain non-blank canonical types");
        }
        // Validate non-null invariants
        if (name == null)
            throw new IllegalArgumentException("name cannot be null");
        if (scope == null)
            throw new IllegalArgumentException("scope cannot be null");
        if (position == null)
            throw new IllegalArgumentException("position cannot be null");
        if (config == null)
            throw new IllegalArgumentException("config cannot be null");
        var providerConfig = new LinkedHashMap<>(config);
        providerConfig.remove("targetTypes");
        config = Collections.unmodifiableMap(providerConfig);
    }

    /**
     * Creates a new PipelineAspectModel instance with default order (0).
     *
     * @param name the name of the aspect
     * @param scope the scope of the aspect application
     * @param position the position of the aspect relative to the step
     * @param config the free-form configuration for the aspect
     */
    public PipelineAspectModel(String name, AspectScope scope, AspectPosition position, 
                               Map<String, Object> config) {
        this(name, scope, position, 0, config);
    }
    /** Converts the extensible configuration map into the compiler-owned selector once. */
    private static List<String> readTargetTypes(String name, Map<String, Object> config) {
        if (config == null || !config.containsKey("targetTypes")) {
            return List.of();
        }
        Object configured = config.get("targetTypes");
        if (!(configured instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("Aspect '" + name
                + "': targetTypes must be a nonempty list of canonical types");
        }
        var targets = new ArrayList<String>();
        for (Object value : values) {
            if (!(value instanceof String type) || type.isBlank()) {
                throw new IllegalArgumentException("Aspect '" + name
                    + "': targetTypes must be a nonempty list of canonical types");
            }
            targets.add(type);
        }
        return List.copyOf(targets);
    }

}