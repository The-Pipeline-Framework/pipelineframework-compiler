from collections.abc import Generator

from generated.application import Failure, Handler, HandlerRegistry, InvocationContext, Rejected, Value
from generated.models import FirstValue, InputValue, OutputValue, SecondValue
from type_positive import finish, normalize


def wrong_result(value: FirstValue, context: InvocationContext) -> Value[FirstValue]:
    return Value(value)


def wrong_input(value: SecondValue, context: InvocationContext) -> Value[SecondValue]:
    return Value(value)


async def wrong_async_result(value: FirstValue, context: InvocationContext) -> Value[FirstValue]:
    return Value(value)


class ForeignAwaitable:
    def __await__(self) -> Generator[None, None, Value[SecondValue]]:
        yield
        return Value(SecondValue(1))


def wrong_carrier(value: FirstValue, context: InvocationContext) -> ForeignAwaitable:
    return ForeignAwaitable()


def raw_value(value: FirstValue, context: InvocationContext) -> SecondValue:
    return SecondValue(value.value)


wrong_output: Handler[FirstValue, SecondValue] = wrong_result  # expect: assignment
wrong_argument: Handler[FirstValue, SecondValue] = wrong_input  # expect: assignment
wrong_coroutine: Handler[FirstValue, SecondValue] = wrong_async_result  # expect: assignment
custom_awaitable: Handler[FirstValue, SecondValue] = wrong_carrier  # expect: assignment
missing_wrapper: Handler[FirstValue, SecondValue] = raw_value  # expect: assignment
bad_registry: HandlerRegistry = {"normalize": normalize, "class": wrong_result, "finish": finish}  # expect: typeddict-item
missing_handler: HandlerRegistry = {"normalize": normalize, "finish": finish}  # expect: typeddict-item
missing_value = InputValue()  # expect: call-arg
null_value = InputValue(None)  # expect: arg-type
string_value = OutputValue("3")  # expect: arg-type
frozen = OutputValue(3)
frozen.value = 4  # expect: misc
missing_context = InvocationContext("execution")  # expect: call-arg
missing_diagnostic = Rejected()  # expect: call-arg
nonretryable_transient = Failure("Apply", "TRANSIENT", "retry", False)  # expect: call-overload
retryable_rejection = Failure("Apply", "BUSINESS_REJECTION", "rejected", True)  # expect: call-overload
wrong_unclassified_message = Failure("Apply", "UNCLASSIFIED", "wrong message", False)  # expect: call-overload
retryable_unclassified = Failure(  # expect: call-overload
    "Apply", "UNCLASSIFIED", "Unclassified business function failure", True
)


class Subtype(InputValue):  # expect: misc
    pass
