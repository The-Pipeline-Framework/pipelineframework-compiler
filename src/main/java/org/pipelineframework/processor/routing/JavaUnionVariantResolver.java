package org.pipelineframework.processor.routing;

import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import com.squareup.javapoet.ClassName;
import org.pipelineframework.processor.PipelineCompilationContext;

/** Resolves the actual Java record and accessor for a union payload, independently of case spelling. */
public final class JavaUnionVariantResolver {
    public Variant resolve(PipelineCompilationContext ctx, ClassName union, ClassName payload) {
        TypeElement declaration = Optional.ofNullable(ctx.getProcessingEnv().getElementUtils()
            .getTypeElement(union.canonicalName())).orElseThrow(() ->
                new IllegalArgumentException("cannot resolve union Java type '" + union + "'"));
        var cases = declaration.getEnclosedElements().stream()
            .filter(value -> value.getKind() == ElementKind.RECORD).map(TypeElement.class::cast)
            .filter(value -> value.getRecordComponents().size() == 1)
            .filter(value -> {
                var component = value.getRecordComponents().getFirst().asType();
                String type = component.getKind().isPrimitive()
                    ? ctx.getProcessingEnv().getTypeUtils().boxedClass((PrimitiveType) component).getQualifiedName().toString()
                    : component.toString();
                return type.equals(payload.canonicalName());
            }).toList();
        if (cases.size() != 1) {
            throw new IllegalArgumentException("union '" + union + "' requires one record variant for payload '" + payload + "'");
        }
        var selected = cases.getFirst();
        return new Variant(ClassName.get(selected), selected.getRecordComponents().getFirst().getSimpleName().toString());
    }

    public record Variant(ClassName type, String accessor) {
        public Variant {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(accessor, "accessor");
        }
    }
}
