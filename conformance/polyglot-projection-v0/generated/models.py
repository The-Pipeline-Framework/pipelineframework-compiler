# GENERATED research bindings; runtime checks enforce int32 and presence.
from typing import Awaitable, Callable, NotRequired, TypedDict

class ProbeContext(TypedDict):
    executionId: str
    traceId: str

class PresenceProbe(TypedDict):
    requiredValue: int
    nullableValue: int | None
    optionalValue: NotRequired[int]
    optionalNullableValue: NotRequired[int | None]

class ProbeValue(TypedDict):
    value: int

class HandlerRegistry(TypedDict):
    a: Callable[[ProbeValue, ProbeContext], Awaitable[ProbeValue]]
    b: Callable[[ProbeValue, ProbeContext], Awaitable[ProbeValue]]
    c: Callable[[ProbeValue, ProbeContext], Awaitable[ProbeValue]]
