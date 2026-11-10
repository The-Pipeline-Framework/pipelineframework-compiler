import {
  createApplication,
  type ApplicationOutput,
  type HandlerRegistry,
  type InvocationContext,
  type NodeLocalRuntime,
  type TerminalRecord,
} from "./application.js";
import type { FirstValue, InputValue, OutputValue, SecondValue } from "./models.js";

const handlers: HandlerRegistry = {
  normalize(input: InputValue, context: InvocationContext) {
    return { kind: "VALUE", value: { value: input.value + context.traceId.length } };
  },
  async apply(input: FirstValue) {
    return { kind: "VALUE", value: { value: input.value * 2 } };
  },
  finish(input: SecondValue) {
    return { kind: "VALUE", value: { value: input.value - 3 } };
  },
};

declare const runtime: NodeLocalRuntime;
const application = createApplication(runtime, handlers);
const context = { executionId: "execution", traceId: "trace" };
const input: InputValue = { value: 3 };
const terminal: Promise<TerminalRecord> = application.run(input, context, new AbortController().signal);
const protocolRejection: TerminalRecord = { kind: "REJECTED", diagnostic: "INVALID_VALUE" };
const output: ApplicationOutput = { value: 5 } satisfies OutputValue;

const wrongType: HandlerRegistry = {
  ...handlers,
  // @ts-expect-error A string payload cannot satisfy a canonical int32 value.
  apply: () => ({ kind: "VALUE", value: { value: "wrong" } }),
};
const wrongCarrier: HandlerRegistry = {
  ...handlers,
  // @ts-expect-error Returning a raw payload bypasses the required VALUE carrier.
  apply: input => input,
};
// @ts-expect-error A handler for each authored logical step is required.
const incomplete: HandlerRegistry = { normalize: handlers.normalize, apply: handlers.apply };
// @ts-expect-error Invocation context cannot omit traceId.
application.run(input, { executionId: "execution" });
// @ts-expect-error A protocol rejection requires a diagnostic field.
const malformed: TerminalRecord = { kind: "REJECTED", failure: {} };

void [terminal, protocolRejection, output, wrongType, wrongCarrier, incomplete, malformed];
