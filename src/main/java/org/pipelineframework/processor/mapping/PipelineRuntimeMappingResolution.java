package org.pipelineframework.processor.mapping;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.pipelineframework.processor.ir.PipelineStepModel;

/**
 * Resolved module and runtime assignments for pipeline steps and synthetics.
 *
 * @param moduleAssignments resolved module for each step model
 * @param clientOverrides client-name to module mapping for orchestrator client wiring
 * @param moduleByServicePackage resolved module per service package (used for synthetic per-step defaults)
 * @param errors validation errors encountered during resolution
 * @param warnings validation warnings encountered during resolution
 * @param usedModules ordered set of modules referenced by the resolution
 * @param moduleRuntimes resolved runtime name per module
 */
public record PipelineRuntimeMappingResolution(
    Map<PipelineStepModel, String> moduleAssignments,
    Map<String, String> clientOverrides,
    Map<String, String> moduleByServicePackage,
    List<String> errors,
    List<String> warnings,
    Set<String> usedModules,
    Map<String, String> moduleRuntimes
) {
    /** Compatibility constructor predating explicit module-to-runtime map retention. */
    public PipelineRuntimeMappingResolution(
        Map<PipelineStepModel, String> moduleAssignments,
        Map<String, String> clientOverrides,
        Map<String, String> moduleByServicePackage,
        List<String> errors,
        List<String> warnings,
        Set<String> usedModules
    ) {
        this(moduleAssignments, clientOverrides, moduleByServicePackage, errors, warnings, usedModules, Map.of());
    }

    public PipelineRuntimeMappingResolution {
        moduleAssignments = moduleAssignments == null ? Map.of() : Map.copyOf(moduleAssignments);
        clientOverrides = clientOverrides == null ? Map.of() : Map.copyOf(clientOverrides);
        moduleByServicePackage = moduleByServicePackage == null ? Map.of() : Map.copyOf(moduleByServicePackage);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (usedModules == null) {
            usedModules = Set.of();
        } else {
            usedModules = Collections.unmodifiableSet(new LinkedHashSet<>(usedModules));
        }
        moduleRuntimes = moduleRuntimes == null ? Map.of() : Map.copyOf(moduleRuntimes);
    }

    /**
     * Indicates whether the resolution contains any validation errors.
     *
     * @return `true` if there is at least one validation error, `false` otherwise.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public Optional<String> moduleForModel(PipelineStepModel model) {
        if (model == null) {
            return Optional.empty();
        }
        String assigned = moduleAssignments.get(model);
        return assigned == null || assigned.isBlank() ? Optional.empty() : Optional.of(assigned);
    }

    public Optional<String> runtimeForModule(String module, PipelineRuntimeMapping mapping) {
        if (module == null || module.isBlank()) {
            return Optional.empty();
        }
        String runtime = moduleRuntimes.get(module);
        if (runtime != null && !runtime.isBlank()) {
            return Optional.of(runtime);
        }
        if (mapping != null) {
            String mapped = mapping.modules().get(module);
            if (mapped != null && !mapped.isBlank()) {
                return Optional.of(mapped);
            }
            if (mapping.defaults() != null && mapping.defaults().runtime() != null && !mapping.defaults().runtime().isBlank()) {
                return Optional.of(mapping.defaults().runtime());
            }
        }
        return Optional.empty();
    }
}
