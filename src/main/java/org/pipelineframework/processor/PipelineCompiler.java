package org.pipelineframework.processor;

import java.util.List;
import java.util.Objects;

/** Executes the compiler's semantic and generation phases in their configured order. */
public final class PipelineCompiler {

    private final List<PipelineCompilationPhase> phases;
    private final java.util.function.Consumer<PipelineCompilationContext> completion;

    /** The production host-neutral phase order; all build frontends use this factory. */
    public static PipelineCompiler production(java.util.function.Consumer<org.pipelineframework.processor.census.BuildCensusObservation> completion) {
        return new PipelineCompiler(List.of(
            new org.pipelineframework.processor.phase.PipelineDiscoveryPhase(),
            new org.pipelineframework.processor.phase.RepresentationProviderPreparationPhase(),
            new org.pipelineframework.processor.phase.PipelineBranchPlanningPhase(),
            new org.pipelineframework.processor.phase.ModelExtractionPhase(),
            new org.pipelineframework.processor.phase.OperationRepresentationGenerationPhase(),
            new org.pipelineframework.processor.phase.PipelineRuntimeMappingPhase(),
            new org.pipelineframework.processor.phase.RepresentationProviderGenerationPhase(),
            new org.pipelineframework.processor.phase.PipelineSemanticAnalysisPhase(),
            new org.pipelineframework.processor.phase.PipelineTargetResolutionPhase(),
            new org.pipelineframework.processor.phase.PipelineBindingConstructionPhase(),
            new org.pipelineframework.processor.phase.PipelineGenerationPhase(),
            new org.pipelineframework.processor.phase.PipelineInfrastructurePhase()), context -> {
                var configuration = org.pipelineframework.processor.census.CensusConfiguration.resolve(
                        context.getCompilerOptions().asMap(), System.getenv(), System.getProperties());
                if (configuration.mode() == org.pipelineframework.processor.census.CensusConfiguration.Mode.OFF) return;
                // Without the host's actual CLASS_OUTPUT, an arbitrary preview path could
                // point into a custom packaged output directory.
                if (configuration.mode() == org.pipelineframework.processor.census.CensusConfiguration.Mode.PREVIEW
                        && context.getClassOutputRoot().isEmpty()) return;
                new org.pipelineframework.processor.census.BuildCensusProjector().project(context).ifPresent(summary -> {
                    var roots = new java.util.HashSet<java.nio.file.Path>();
                    java.util.Optional.ofNullable(context.getModuleDir()).ifPresent(module -> {
                        roots.add(module.resolve("src")); roots.add(module.resolve("target")); roots.add(module.resolve("build"));
                    });
                    java.util.Optional.ofNullable(context.getGeneratedSourcesRoot()).ifPresent(roots::add);
                    context.getClassOutputRoot().ifPresent(roots::add);
                    completion.accept(new org.pipelineframework.processor.census.BuildCensusObservation(
                            new org.pipelineframework.processor.census.BuildCensusHostContext(
                                    context.getOriginalPipelineConfig().orElseThrow(), configuration, roots), summary));
                });
            });
    }

    /**
     * Creates a phase engine with an immutable snapshot of the requested ordering.
     *
     * @param phases compilation phases in execution order
     */
    public PipelineCompiler(List<PipelineCompilationPhase> phases) {
        this(phases, ignored -> { });
    }

    public PipelineCompiler(List<PipelineCompilationPhase> phases,
            java.util.function.Consumer<PipelineCompilationContext> completion) {
        this.phases = List.copyOf(Objects.requireNonNull(phases, "phases must not be null"));
        this.completion = Objects.requireNonNull(completion);
    }

    /**
     * Executes every configured phase once for the supplied compilation context.
     *
     * @param context host-created compilation context
     * @throws PipelineCompilationException when a phase fails
     */
    public void compile(PipelineCompilationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        for (PipelineCompilationPhase phase : phases) {
            try {
                phase.execute(context);
            } catch (Exception cause) {
                throw new PipelineCompilationException(phase.name(), cause);
            }
        }
        if (!context.hasCompilationErrors()) {
            try { completion.accept(context); }
            catch (RuntimeException censusUnavailable) { /* Optional build observers cannot change compilation. */ }
        }
    }
}
