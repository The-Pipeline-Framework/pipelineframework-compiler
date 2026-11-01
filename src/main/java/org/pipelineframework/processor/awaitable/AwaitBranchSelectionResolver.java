package org.pipelineframework.processor.awaitable;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import com.squareup.javapoet.ClassName;
import org.pipelineframework.config.template.PipelineTemplateConfig;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.PipelineCompilationContext;
import org.pipelineframework.processor.ir.AwaitBranchSelection;
import org.pipelineframework.processor.ir.PipelineTransport;
import org.pipelineframework.processor.ir.StepDefinition;
import org.pipelineframework.processor.routing.V3JavaTypeResolver;

/** Validates the canonical branch transformation before generating an Await adapter. */
final class AwaitBranchSelectionResolver {
    Optional<AwaitBranchSelection> resolve(PipelineCompilationContext ctx, PipelineTemplateConfig config,
        StepDefinition step) {
        try {
            var completion = step.deferredCompletion().orElseThrow();
            if (ctx.getTransportMode() != PipelineTransport.LOCAL || completion.callback().isPresent()) {
                throw new IllegalArgumentException("selected Await requires LOCAL post-operation transport completion");
            }
            var template = config.steps().stream().filter(value -> value.name().equals(step.name()))
                .findFirst().orElseThrow();
            if (config.steps().stream().anyMatch(value -> !"ONE_TO_ONE".equals(value.cardinality()))) {
                throw new IllegalArgumentException("selected Await requires a unary pipeline definition");
            }
            var javaTypes = new V3JavaTypeResolver(config);
            var accepted = new TreeSet<String>();
            completion.accepts().forEach(value -> accepted.addAll(concreteTypes(config, value)));
            var returned = concreteTypes(config, completion.selectedOutputType().orElseThrow());
            if (accepted.size() != 1 || returned.size() != 1) {
                throw new IllegalArgumentException("await.accepts and await.output must each resolve to one concrete type");
            }
            String request = accepted.first();
            String response = returned.iterator().next();
            var inputs = inputVariants(config, completion.operationOutputType());
            if (!inputs.containsKey(request)) {
                throw new IllegalArgumentException("await.accepts type '" + request + "' is not an operation-output variant");
            }
            var expected = new TreeSet<>(inputs.keySet());
            expected.remove(request);
            expected.add(response);
            if (!expected.equals(config.typeModel().concreteTypeNames(template.outputTypeName()))) {
                throw new IllegalArgumentException("step output must contain the continuing operation variants and await.output: "
                    + expected);
            }
            ClassName inputType = javaTypes.resolve(completion.operationOutputType()).orElseThrow();
            ClassName outputType = javaTypes.resolve(template.outputTypeName()).orElseThrow();
            ClassName requestType = javaTypes.resolve(request).orElseThrow();
            ClassName responseType = javaTypes.resolve(response).orElseThrow();
            var outputReference = config.typeModel().resolveAliases(
                new PipelineTemplateTypeReference.Named(template.outputTypeName()));
            boolean outputUnion = outputReference instanceof PipelineTemplateTypeReference.Named named
                && config.typeModel().definition(named.name())
                    .filter(PipelineTemplateTypeDefinition.UnionType.class::isInstance).isPresent();
            var bypasses = new ArrayList<AwaitBranchSelection.Bypass>();
            for (String canonical : inputs.keySet()) {
                if (canonical.equals(request)) continue;
                ClassName payload = javaTypes.resolve(canonical).orElseThrow();
                bypasses.add(new AwaitBranchSelection.Bypass(caseFor(ctx, inputType, payload), outputUnion
                    ? Optional.of(caseFor(ctx, outputType, payload).type()) : Optional.empty()));
            }
            return Optional.of(new AwaitBranchSelection(request, requestType, response, responseType,
                caseFor(ctx, inputType, requestType), outputUnion
                    ? Optional.of(caseFor(ctx, outputType, responseType).type()) : Optional.empty(), bypasses));
        } catch (IllegalArgumentException failure) {
            ctx.getCompilerDiagnostics().error("Step '" + step.name() + "': " + failure.getMessage());
            return Optional.empty();
        }
    }

    private Set<String> concreteTypes(PipelineTemplateConfig config, String reference) {
        String name = reference.trim();
        if (name.startsWith("<") && name.endsWith(">")) {
            String protocol = name.substring(1, name.length() - 1);
            var matches = config.typeModel().contributedTypeIdentities().entrySet().stream()
                .filter(entry -> entry.getValue().qualifiedName().equals(protocol)
                    || entry.getValue().typeName().equals(protocol)).map(Map.Entry::getKey).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("ambiguous or unknown protocol type '" + reference + "'");
            name = matches.getFirst();
        }
        return config.typeModel().concreteTypeNames(name);
    }

    private Map<String, String> inputVariants(PipelineTemplateConfig config, String name) {
        if (!(resolvedDefinition(config, name) instanceof PipelineTemplateTypeDefinition.UnionType union)) {
            throw new IllegalArgumentException("await.accepts requires a union operation output");
        }
        var variants = new TreeMap<String, String>();
        for (var variant : union.variants().values()) {
            var payload = config.typeModel().resolveAliases(variant.payload());
            String canonical = switch (payload) {
                case PipelineTemplateTypeReference.Named named -> named.name();
                case PipelineTemplateTypeReference.Scalar scalar -> scalar.name();
                default -> throw new IllegalArgumentException("selected Await requires concrete operation-output variants");
            };
            if (config.typeModel().definition(canonical).filter(PipelineTemplateTypeDefinition.UnionType.class::isInstance).isPresent()) {
                throw new IllegalArgumentException("selected Await requires flat operation-output variants");
            }
            if (variants.putIfAbsent(canonical, variant.discriminator()) != null) {
                throw new IllegalArgumentException("selected Await requires one operation-output variant per concrete type");
            }
        }
        return java.util.Collections.unmodifiableSortedMap(variants);
    }

    private PipelineTemplateTypeDefinition resolvedDefinition(PipelineTemplateConfig config, String name) {
        var resolved = config.typeModel().resolveAliases(new PipelineTemplateTypeReference.Named(name));
        if (!(resolved instanceof PipelineTemplateTypeReference.Named named)) {
            throw new IllegalArgumentException("selected Await requires a named output contract");
        }
        return config.typeModel().definition(named.name()).orElseThrow();
    }

    private AwaitBranchSelection.CaseAccess caseFor(PipelineCompilationContext ctx, ClassName union, ClassName payload) {
        var variant = new org.pipelineframework.processor.routing.JavaUnionVariantResolver().resolve(ctx, union, payload);
        return new AwaitBranchSelection.CaseAccess(variant.type(), variant.accessor());
    }
}
