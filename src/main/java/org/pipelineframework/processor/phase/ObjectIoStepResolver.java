package org.pipelineframework.processor.phase;

import java.util.List;
import java.util.Optional;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.PipelineStepModel;

/** Finds the boundary business steps without including side effects. */
final class ObjectIoStepResolver {
    Optional<PipelineStepModel> firstBusinessStepWithDeploymentRole(PipelineCompilationContext ctx) {
        List<PipelineStepModel> models = ctx.getStepModels() == null ? List.of() : ctx.getStepModels();
        for (PipelineStepModel model : models) {
            if (model != null && !model.sideEffect() && model.deploymentRole() != null) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    Optional<PipelineStepModel> terminalBusinessStepWithDeploymentRole(PipelineCompilationContext ctx) {
        List<PipelineStepModel> models = ctx.getStepModels() == null ? List.of() : ctx.getStepModels();
        for (int i = models.size() - 1; i >= 0; i--) {
            PipelineStepModel model = models.get(i);
            if (model != null && !model.sideEffect() && model.deploymentRole() != null) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

}
