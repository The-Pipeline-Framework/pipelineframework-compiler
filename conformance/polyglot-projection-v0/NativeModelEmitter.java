/** Emits language declarations from the already admitted, normalized projection only. */
final class NativeModelEmitter {
    String typescript(PolyglotProjectionProbe.Projection projection) {
        var out = new StringBuilder("// GENERATED research bindings. Runtime validation enforces int32 range and presence.\n")
            .append("// Logical names are compiler model identities, not Java class or wire identities.\n")
            .append("export type Int32 = number;\n")
            .append("export interface ProbeContext { readonly executionId: string; readonly traceId: string; }\n");
        for (PolyglotProjectionProbe.Type type : projection.types()) {
            out.append("export interface ").append(type.id()).append(" {\n");
            for (PolyglotProjectionProbe.Field field : type.fields()) {
                out.append("  readonly ").append(field.name()).append(field.presence().equals("OPTIONAL") ? "?" : "")
                    .append(": Int32").append(field.nullability().equals("NULLABLE") ? " | null" : "").append(";\n");
            }
            out.append("}\n");
        }
        out.append("export interface LogicalTypes {\n");
        projection.types().forEach(type -> out.append("  readonly ").append(type.id()).append(": ").append(type.id()).append(";\n"));
        out.append("}\nexport type LogicalTypeId = keyof LogicalTypes;\nexport interface HandlerRegistry {\n");
        for (PolyglotProjectionProbe.Step step : projection.steps()) {
            out.append("  readonly ").append(step.handler()).append(": (input: ").append(step.inputType())
                .append(", context: Readonly<ProbeContext>) => Promise<").append(step.outputType()).append(">;\n");
        }
        return out.append("}\n").toString();
    }

    String python(PolyglotProjectionProbe.Projection projection) {
        var out = new StringBuilder("# GENERATED research bindings; runtime checks enforce int32 and presence.\n")
            .append("from typing import Awaitable, Callable, NotRequired, TypedDict\n\n")
            .append("class ProbeContext(TypedDict):\n    executionId: str\n    traceId: str\n\n");
        for (PolyglotProjectionProbe.Type type : projection.types()) {
            out.append("class ").append(type.id()).append("(TypedDict):\n");
            if (type.fields().isEmpty()) out.append("    pass\n");
            for (PolyglotProjectionProbe.Field field : type.fields()) {
                String annotation = "int" + (field.nullability().equals("NULLABLE") ? " | None" : "");
                out.append("    ").append(field.name()).append(": ")
                    .append(field.presence().equals("OPTIONAL") ? "NotRequired[" + annotation + "]" : annotation).append("\n");
            }
            out.append("\n");
        }
        out.append("class HandlerRegistry(TypedDict):\n");
        for (PolyglotProjectionProbe.Step step : projection.steps()) {
            out.append("    ").append(step.handler()).append(": Callable[[").append(step.inputType())
                .append(", ProbeContext], Awaitable[").append(step.outputType()).append("]]\n");
        }
        return out.toString();
    }

}
