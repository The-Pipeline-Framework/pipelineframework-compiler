// GENERATED research bindings. Runtime validation enforces int32 range and presence.
// Logical names are compiler model identities, not Java class or wire identities.
export type Int32 = number;
export interface ProbeContext { readonly executionId: string; readonly traceId: string; }
export interface PresenceProbe {
  readonly requiredValue: Int32;
  readonly nullableValue: Int32 | null;
  readonly optionalValue?: Int32;
  readonly optionalNullableValue?: Int32 | null;
}
export interface ProbeValue {
  readonly value: Int32;
}
export interface LogicalTypes {
  readonly PresenceProbe: PresenceProbe;
  readonly ProbeValue: ProbeValue;
}
export type LogicalTypeId = keyof LogicalTypes;
export interface HandlerRegistry {
  readonly a: (input: ProbeValue, context: Readonly<ProbeContext>) => Promise<ProbeValue>;
  readonly b: (input: ProbeValue, context: Readonly<ProbeContext>) => Promise<ProbeValue>;
  readonly c: (input: ProbeValue, context: Readonly<ProbeContext>) => Promise<ProbeValue>;
}
