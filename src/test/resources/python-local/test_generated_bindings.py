"""Exercise the emitted package and its boundary with a supplied loader."""
from dataclasses import FrozenInstanceError
import hashlib
import json
from pathlib import Path
import unittest

from generated.application import (
    Cancelled, Completed, Failed, Failure, InvocationContext, PROJECTION_BYTES,
    PROJECTION_SHA256, Rejected, RuntimeBinding, Value, create_application,
)
from generated.models import FirstValue, InputValue, OutputValue, SecondValue


class LoaderProbe:
    def __init__(self):
        self.bindings = []
        self.application = object()

    def load(self, binding):
        self.bindings.append(binding)
        return self.application


class GeneratedBindingsTest(unittest.TestCase):
    def test_nominal_frozen_models_and_context(self):
        first = InputValue(7)
        second = FirstValue(7)
        self.assertEqual(first.value, second.value)
        self.assertNotEqual(first, second)
        self.assertIs(type(first), InputValue)
        self.assertTrue(InputValue.__final__)
        self.assertFalse(hasattr(first, "__dict__"))
        with self.assertRaises(FrozenInstanceError):
            first.value = 8
        context = InvocationContext("execution", "trace")
        with self.assertRaises(FrozenInstanceError):
            context.traceId = "another"

    def test_loader_receives_exact_projection_and_readonly_class_and_handler_snapshots(self):
        loader = LoaderProbe()

        def business_function(*args):
            self.fail("Generation and binding must not invoke business handlers.")

        class AliasingCopy(dict):
            def copy(self):
                return self

        handlers = AliasingCopy({name: business_function for name in ("normalize", "class", "finish")})
        self.assertIs(create_application(loader, handlers), loader.application)
        self.assertEqual(len(loader.bindings), 1)
        binding = loader.bindings[0]
        self.assertIs(type(binding), RuntimeBinding)
        self.assertEqual(binding.schema_version, 1)
        projection_file = Path(__file__).with_name("generated") / "projection.json"
        self.assertEqual(binding.projection_bytes, PROJECTION_BYTES)
        self.assertEqual(binding.projection_bytes, projection_file.read_bytes())
        self.assertEqual(binding.projection_sha256, PROJECTION_SHA256)
        self.assertEqual(binding.projection_sha256, hashlib.sha256(binding.projection_bytes).hexdigest())
        projection = json.loads(binding.projection_bytes)
        self.assertEqual(projection["kind"], "compiler-python-local")
        self.assertEqual([step["id"] for step in projection["steps"]], ["Normalize", "Apply", "Finish"])
        self.assertEqual(binding.model_types, {
            "InputValue": InputValue, "FirstValue": FirstValue, "SecondValue": SecondValue, "OutputValue": OutputValue,
        })
        self.assertIs(binding.carrier_types.invocation_context, InvocationContext)
        self.assertIs(binding.carrier_types.value, Value)
        self.assertIs(binding.carrier_types.failure, Failure)
        self.assertIs(binding.carrier_types.completed, Completed)
        self.assertIs(binding.carrier_types.failed, Failed)
        self.assertIs(binding.carrier_types.rejected, Rejected)
        self.assertIs(binding.carrier_types.cancelled, Cancelled)
        self.assertEqual(dict(binding.handlers), handlers)
        handlers.clear()
        self.assertEqual(set(binding.handlers), {"normalize", "class", "finish"})
        with self.assertRaises(TypeError):
            binding.handlers["class"] = None
        with self.assertRaises(TypeError):
            binding.model_types["InputValue"] = FirstValue
        with self.assertRaises(FrozenInstanceError):
            binding.projection_sha256 = ""

    def test_terminal_carriers_expose_the_runtime_discriminator(self):
        self.assertEqual(Value(OutputValue(3)).kind, "VALUE")
        self.assertEqual(Completed(Value(OutputValue(3))).kind, "COMPLETED")
        failure = Failure("Apply", "TRANSIENT", "retry", True)
        self.assertEqual(Failed(failure).kind, "FAILED")
        self.assertEqual(failure.owner, "LOCAL_SHELL")
        self.assertEqual(Rejected("invalid").kind, "REJECTED")
        self.assertEqual(Cancelled().kind, "CANCELLED")


if __name__ == "__main__":
    unittest.main()
