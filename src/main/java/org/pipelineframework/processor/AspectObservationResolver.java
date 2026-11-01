package org.pipelineframework.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import com.squareup.javapoet.ClassName;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateDialect;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.processor.ir.*;
import org.pipelineframework.processor.routing.JavaUnionVariantResolver;
import org.pipelineframework.processor.routing.V3JavaTypeResolver;

/** Selects canonical aspect payloads without changing the value carried by the pipeline. */
public final class AspectObservationResolver {
    private final PipelineCompilationContext ctx;

    public AspectObservationResolver(PipelineCompilationContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    public List<Observation> resolve(PipelineStepModel model, PipelineAspectModel aspect) {
        if (!(ctx.getPipelineTemplateConfig() instanceof PipelineTemplateConfig config)
            || config.dialect() != PipelineTemplateDialect.V3 || !ctx.isTransportModeLocal()) {
            throw new IllegalArgumentException("Aspect '" + aspect.name() + "': targetTypes requires canonical v3 LOCAL pipelines");
        }
        Object configured = aspect.config().get("targetTypes");
        if (!(configured instanceof List<?> values) || values.isEmpty()
            || values.stream().anyMatch(value -> !(value instanceof String name) || name.isBlank())) {
            throw new IllegalArgumentException("Aspect '" + aspect.name() + "': targetTypes must be a nonempty list of canonical types");
        }
        var targets = new TreeSet<String>();
        values.forEach(value -> targets.addAll(config.typeModel().concreteTypeNames((String) value)));
        var mapping = aspect.position() == AspectPosition.BEFORE_STEP ? model.inputMapping() : model.outputMapping();
        var javaTypes = new V3JavaTypeResolver(config);
        var unionNames = config.typeModel().definitions().entrySet().stream()
            .filter(entry -> entry.getValue() instanceof PipelineTemplateTypeDefinition.UnionType)
            .filter(entry -> javaTypes.resolve(entry.getKey()).filter(mapping.domainType()::equals).isPresent())
            .map(java.util.Map.Entry::getKey).toList();
        if (unionNames.size() > 1) {
            throw new IllegalArgumentException("Aspect '" + aspect.name() + "': ambiguous canonical union Java binding for '"
                + mapping.domainType() + "': " + unionNames);
        }
        var observations = new ArrayList<Observation>();
        for (String target : targets) {
            ClassName payload = javaTypes.resolve(target).orElseThrow(() ->
                new IllegalArgumentException("Aspect '" + aspect.name() + "': cannot resolve Java type for '" + target + "'"));
            var selected = TypeMapping.canonical(payload, target);
            if (payload.equals(mapping.domainType())) {
                observations.add(new Observation(selected, Optional.of(new ObservationProjection(payload, Optional.empty(), model.serviceName()))));
            } else if (!unionNames.isEmpty()
                && config.typeModel().concreteTypeNames(unionNames.getFirst()).contains(target)) {
                if (!(mapping.domainType() instanceof ClassName carrier)) {
                    throw new IllegalArgumentException("Selected observation requires a named union Java binding");
                }
                var variant = new JavaUnionVariantResolver().resolve(ctx, carrier, payload);
                observations.add(new Observation(selected, Optional.of(new ObservationProjection(carrier,
                    variant.type(), variant.accessor(), model.serviceName()))));
            }
        }
        return List.copyOf(observations);
    }

    public record Observation(TypeMapping mapping, Optional<ObservationProjection> projection) {
        public Observation {
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(projection, "projection");
        }
    }
}
