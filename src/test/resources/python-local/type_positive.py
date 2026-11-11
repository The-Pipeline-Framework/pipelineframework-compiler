from collections.abc import Callable

from generated.application import (
    Application, CancellationSignal, Completed, HandlerRegistry, InvocationContext,
    Rejected, RuntimeBinding, TerminalRecord, Value, create_application,
)
from generated.models import FirstValue, InputValue, OutputValue, SecondValue


def normalize(value: InputValue, context: InvocationContext) -> Value[FirstValue]:
    return Value(FirstValue(value.value + len(context.traceId)))


async def apply(value: FirstValue, context: InvocationContext) -> Value[SecondValue]:
    return Value(SecondValue(value.value * 2))


def finish(value: SecondValue, context: InvocationContext) -> Value[OutputValue]:
    return Value(OutputValue(value.value - 3))


handlers: HandlerRegistry = {"normalize": normalize, "class": apply, "finish": finish}


class Uncancelled:
    @property
    def cancelled(self) -> bool:
        return False

    def subscribe(self, callback: Callable[[], None], /) -> Callable[[], None]:
        return lambda: None


class StubApplication:
    async def run(
        self,
        input: InputValue,
        context: InvocationContext,
        signal: CancellationSignal | None = None,
    ) -> TerminalRecord:
        raise NotImplementedError("This fixture type-checks the loader boundary.")


class StubRuntime:
    def load(self, binding: RuntimeBinding, /) -> Application:
        return StubApplication()


async def type_check_application() -> TerminalRecord:
    application = create_application(StubRuntime(), handlers)
    result = await application.run(InputValue(3), InvocationContext("execution", "trace"), Uncancelled())
    if isinstance(result, Completed):
        output: OutputValue = result.outcome.value
        return Completed(Value(output))
    return Rejected("test")
