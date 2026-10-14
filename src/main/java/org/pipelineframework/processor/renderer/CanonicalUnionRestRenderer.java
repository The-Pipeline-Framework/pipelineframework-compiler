package org.pipelineframework.processor.renderer;

import com.squareup.javapoet.ClassName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import com.squareup.javapoet.TypeName;
import org.pipelineframework.config.template.PipelineTemplateTypeDefinition;
import org.pipelineframework.config.template.PipelineTemplateTypeReference;
import org.pipelineframework.processor.ir.TypeMapping;

/** Emits a discriminated REST envelope and mapper for a normalized union of records. */
final class CanonicalUnionRestRenderer {
    private final GenerationContext context;
    private final CanonicalTransportBindingResolver resolver;

    CanonicalUnionRestRenderer(GenerationContext context, CanonicalTransportBindingResolver resolver) {
        this.context = context;
        this.resolver = resolver;
    }

    void ensureRest(CanonicalTransportTypeBinding binding, PipelineTemplateTypeDefinition.UnionType union)
        throws IOException {
        List<VariantBinding> variants = new ArrayList<>();
        for (PipelineTemplateTypeDefinition.Variant variant : union.variants().values()) {
            if (variant.discriminator() == null || variant.discriminator().isBlank()) {
                throw new IllegalStateException("REST union '" + union.name()
                    + "' requires a non-empty variant discriminator");
            }
        }
        for (PipelineTemplateTypeDefinition.Variant variant : union.variants().values().stream()
            .sorted(Comparator.comparing(PipelineTemplateTypeDefinition.Variant::discriminator)).toList()) {
            if ("discriminator".equals(variant.discriminator())) {
                throw new IllegalStateException("REST union '" + union.name()
                    + "' cannot use reserved wire property 'discriminator' as a variant");
            }
            if (!(variant.payload() instanceof PipelineTemplateTypeReference.Named
                || variant.payload() instanceof PipelineTemplateTypeReference.Contributed)) {
                throw new IllegalStateException("REST union '" + union.name()
                    + "' requires named record variant payloads: " + variant.discriminator());
            }
            String payloadName = variant.payload().name();
            ClassName javaType = payloadJavaType(binding, variant.discriminator());
            CanonicalTransportTypeBinding payload = resolver.resolve(TypeMapping.canonical(javaType, payloadName))
                .orElseThrow(() -> new IllegalStateException("REST union '" + union.name()
                    + "' variant '" + variant.discriminator() + "' requires a canonical record payload: " + payloadName));
            new CanonicalRecordTransportRenderer(context, resolver).ensureRest(payload);
            variants.add(new VariantBinding(variant.discriminator(), payload, "payload" + variants.size()));
        }
        renderRest(binding, variants);
    }

    void renderRest(CanonicalTransportTypeBinding binding, List<VariantBinding> variants) throws IOException {
        writeDto(binding, variants);
        writeMapper(binding, variants);
    }

    private void writeDto(CanonicalTransportTypeBinding binding, List<VariantBinding> variants) throws IOException {
        Path path = sourcePath(binding.restDtoType());
        if (Files.exists(path)) return;
        String name = binding.restDtoType().simpleName();
        StringBuilder source = new StringBuilder("package ").append(binding.restDtoType().packageName())
            .append(";\n\n/** REST representation of canonical union ").append(binding.canonicalName())
            .append(". The discriminator and exactly one matching typed payload are required. */\n")
            .append("public record ").append(name).append("(String discriminator");
        for (VariantBinding variant : variants) {
            source.append(", @com.fasterxml.jackson.annotation.JsonProperty(\"")
                .append(escaped(variant.discriminator())).append("\") ")
                .append(variant.payload().restDtoType().canonicalName()).append(' ')
                .append(variant.fieldName());
        }
        source.append(") {\n    public ").append(name).append(" {\n")
            .append("        if (discriminator == null) throw new IllegalArgumentException(\"Union discriminator must not be null\");\n")
            .append("        switch (discriminator) {\n");
        for (VariantBinding selected : variants) {
            source.append("            case \"").append(escaped(selected.discriminator())).append("\" -> {\n")
                .append("                if (").append(selected.fieldName()).append(" == null");
            for (VariantBinding other : variants) {
                if (other != selected) source.append(" || ").append(other.fieldName()).append(" != null");
            }
            source.append(") throw new IllegalArgumentException(\"Union payload must match discriminator: \" + discriminator);\n")
                .append("            }\n");
        }
        source.append("            default -> throw new IllegalArgumentException(\"Unknown union discriminator: \" + discriminator);\n")
            .append("        }\n    }\n}\n");
        write(path, source.toString());
    }

    private void writeMapper(CanonicalTransportTypeBinding binding, List<VariantBinding> variants)
        throws IOException {
        Path path = sourcePath(binding.restMapperType());
        if (Files.exists(path)) return;
        String domain = binding.javaType().canonicalName();
        String dto = binding.restDtoType().canonicalName();
        StringBuilder source = new StringBuilder("package ").append(binding.restMapperType().packageName())
            .append(";\n\npublic final class ").append(binding.restMapperType().simpleName())
            .append(" implements org.pipelineframework.mapper.Mapper<").append(domain).append(", ")
            .append(dto).append("> {\n");
        for (VariantBinding variant : variants) {
            source.append("    private final ").append(variant.payload().restMapperType().canonicalName())
                .append(' ').append(variant.fieldName()).append("Mapper = new ")
                .append(variant.payload().restMapperType().canonicalName()).append("();\n");
        }
        source.append("\n    @Override public ").append(domain).append(" fromExternal(").append(dto)
            .append(" external) {\n        java.util.Objects.requireNonNull(external, \"external must not be null\");\n")
            .append("        return switch (external.discriminator()) {\n");
        for (VariantBinding variant : variants) {
            source.append("            case \"").append(escaped(variant.discriminator())).append("\" -> new ")
                .append(domain).append('.').append(variantClass(variant.discriminator()))
                .append('(').append(variant.fieldName()).append("Mapper.fromExternal(external.")
                .append(variant.fieldName()).append("()));\n");
        }
        source.append("            default -> throw new IllegalArgumentException(\"Unknown union discriminator: \" + external.discriminator());\n")
            .append("        };\n    }\n\n    @Override public ").append(dto).append(" toExternal(")
            .append(domain).append(" domain) {\n        java.util.Objects.requireNonNull(domain, \"domain must not be null\");\n");
        for (VariantBinding selected : variants) {
            source.append("        if (domain instanceof ").append(domain).append('.')
                .append(variantClass(selected.discriminator())).append(" variant) {\n")
                .append("            return new ").append(dto).append("(\"")
                .append(escaped(selected.discriminator())).append("\"");
            for (VariantBinding field : variants) {
                source.append(", ").append(field == selected
                    ? field.fieldName() + "Mapper.toExternal(java.util.Objects.requireNonNull(variant.value(), \"Union payload must not be null\"))"
                    : "null");
            }
            source.append(");\n        }\n");
        }
        source.append("        throw new IllegalArgumentException(\"Unknown union variant: \" + domain.getClass().getName());\n")
            .append("    }\n}\n");
        write(path, source.toString());
    }

    private Path sourcePath(ClassName type) {
        return context.outputDir().resolve(type.packageName().replace('.', '/')).resolve(type.simpleName() + ".java");
    }

    private ClassName payloadJavaType(CanonicalTransportTypeBinding binding, String discriminator) {
        if (!context.compilerServices().available()) {
            throw new IllegalStateException("REST union transport requires Java source discovery");
        }
        String wrapperName = binding.javaType().canonicalName() + "." + variantClass(discriminator);
        TypeElement wrapper = context.compilerServices().elements().getTypeElement(wrapperName);
        if (wrapper == null || wrapper.getKind() != ElementKind.RECORD || wrapper.getRecordComponents().size() != 1
            || !wrapper.getRecordComponents().getFirst().getSimpleName().contentEquals("value")) {
            throw new IllegalStateException("REST union variant '" + wrapperName
                + "' requires a record wrapper with one value component");
        }
        TypeName type = TypeName.get(wrapper.getRecordComponents().getFirst().asType());
        if (!(type instanceof ClassName javaType)) {
            throw new IllegalStateException("REST union variant '" + wrapperName
                + "' requires a named record payload: " + type);
        }
        return javaType;
    }

    private void write(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private String variantClass(String discriminator) {
        return Character.toUpperCase(discriminator.charAt(0)) + discriminator.substring(1);
    }

    private String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record VariantBinding(String discriminator, CanonicalTransportTypeBinding payload, String fieldName) { }
}
