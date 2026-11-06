"""Falsification checks for real v3 generation, closed admission, and typed handler binding."""
from __future__ import annotations

import copy
import ast
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

from jsonschema import Draft202012Validator, ValidationError

import run


class ProjectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # Resolve once with run.py before invoking these focused tests.
        cls.prepared = run.prepare(skip_resolve=True)
        cls.yaml = (run.HERE / "fixtures/pipeline.yaml").read_text(encoding="utf-8")
        cls.bindings = json.loads((run.HERE / "fixtures/native-bindings.json").read_text(encoding="utf-8"))

    def invoke(self, yaml: str | None = None, bindings: dict | str | None = None,
               prepared: run.Prepared | None = None):
        temporary = tempfile.TemporaryDirectory(prefix="projection-check-", dir=run.TARGET)
        self.addCleanup(temporary.cleanup)
        folder = Path(temporary.name)
        dsl = folder / "pipeline.yaml"
        native = folder / "bindings.json"
        dsl.write_text(self.yaml if yaml is None else yaml, encoding="utf-8")
        # Preserve source bytes when checking reproducibility: formatting is part of its digest.
        native.write_text((run.HERE / "fixtures/native-bindings.json").read_text(encoding="utf-8")
                          if bindings is None else bindings if isinstance(bindings, str) else json.dumps(bindings),
                          encoding="utf-8")
        output = folder / "generated"
        result = run.generate(self.prepared if prepared is None else prepared, dsl, native, output)
        return result, output

    def rejected(self, code: str, **inputs) -> None:
        result, output = self.invoke(**inputs)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"PROJECTION_REJECT:{code}:", result.stderr)
        self.assertFalse(output.exists(), "rejection must not publish any artifact")

    def test_reproducible_checked_in_output_and_normalized_presence(self):
        result, output = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        for name in ("projection.json", "models.ts", "models.py", "SHA256SUMS"):
            self.assertEqual((run.HERE / "generated" / name).read_bytes(), (output / name).read_bytes(), name)
        projection = json.loads((output / "projection.json").read_text())
        ast.parse((output / "models.py").read_text(), filename="models.py")
        self.assertEqual(["A", "B", "C"], [s["id"] for s in projection["steps"]])
        fields = next(t for t in projection["types"] if t["id"] == "PresenceProbe")["fields"]
        self.assertEqual([("REQUIRED", "NON_NULL"), ("REQUIRED", "NULLABLE"),
                          ("OPTIONAL", "NON_NULL"), ("OPTIONAL", "NULLABLE")],
                         [(f["presence"], f["nullability"]) for f in fields])

    def test_changed_dsl_generates_changed_models_from_real_v3_normalization(self):
        # Change logical type identity and requiredness in DSL, with compact v3 field syntax.
        # Bindings must follow the model; they cannot independently rename its type identity.
        changed = self.yaml.replace("ProbeValue", "ChangedValue").replace(
            "{ name: value, type: int32, presence: required, nullability: non_null }", "[value?, int32?]")
        bindings = json.loads(json.dumps(self.bindings).replace("ProbeValue", "ChangedValue"))
        result, output = self.invoke(yaml=changed, bindings=bindings)
        self.assertEqual(0, result.returncode, result.stderr)
        projection = json.loads((output / "projection.json").read_text())
        self.assertEqual("ChangedValue", projection["pipeline"]["inputType"])
        normalized = next(t for t in projection["types"] if t["id"] == "ChangedValue")["fields"][0]
        self.assertEqual({"name": "value", "type": "int32", "presence": "OPTIONAL", "nullability": "NULLABLE"}, normalized)
        self.assertIn("readonly value?: Int32 | null", (output / "models.ts").read_text())
        self.assertIn("value: NotRequired[int | None]", (output / "models.py").read_text())
        baseline = json.loads((run.HERE / "generated/projection.json").read_text())
        self.assertNotEqual(baseline["provenance"]["dslSha256"], projection["provenance"]["dslSha256"])

    def test_changed_dsl_order_is_projected_without_binding_array_reordering(self):
        changed = (run.HERE / "fixtures/changed-order.yaml").read_text()
        result, output = self.invoke(yaml=changed)
        self.assertEqual(0, result.returncode, result.stderr)
        projection = json.loads((output / "projection.json").read_text())
        self.assertEqual(["B", "A", "C"], [step["id"] for step in projection["steps"]])
        self.assertEqual(["b", "a", "c"], [step["handler"] for step in projection["steps"]])
        # Binding array stays a,b,c: only DSL order decides the generated executable order.
        self.assertEqual(["a", "b", "c"], [binding["handler"] for binding in self.bindings["handlers"]])
        for name in ("projection.json", "models.ts", "models.py", "SHA256SUMS"):
            self.assertEqual((run.HERE / "generated-changed-order" / name).read_bytes(), (output / name).read_bytes(), name)

    def test_missing_ambiguous_extra_and_wrong_type_handlers(self):
        for kind in ("missing", "duplicate-step", "duplicate-symbol", "extra", "wrong-type"):
            with self.subTest(kind=kind):
                bindings = copy.deepcopy(self.bindings)
                code = {"missing": "MISSING_HANDLER", "duplicate-step": "AMBIGUOUS_HANDLER",
                        "duplicate-symbol": "AMBIGUOUS_HANDLER", "extra": "EXTRA_HANDLER", "wrong-type": "HANDLER_TYPE"}[kind]
                if kind == "missing":
                    bindings["handlers"].pop(0)
                elif kind == "duplicate-step":
                    bindings["handlers"].append(copy.deepcopy(bindings["handlers"][0]))
                elif kind == "duplicate-symbol":
                    bindings["handlers"][1]["handler"] = "a"
                elif kind == "extra":
                    bindings["handlers"].append({**bindings["handlers"][0], "step": "D", "handler": "d"})
                else:
                    bindings["handlers"][0]["inputType"] = "PresenceProbe"
                self.rejected(code, bindings=bindings)

    def test_canonical_model_rejects_unknown_and_incompatible_types(self):
        self.rejected("DSL_MODEL", yaml=self.yaml.replace("name: B, cardinality: ONE_TO_ONE, input: ProbeValue",
                                                          "name: B, cardinality: ONE_TO_ONE, input: MissingType"))
        self.rejected("DSL_MODEL", yaml=self.yaml.replace("name: B, cardinality: ONE_TO_ONE, input: ProbeValue",
                                                          "name: B, cardinality: ONE_TO_ONE, input: PresenceProbe"))

    def test_non_adjacent_only_flow_is_outside_linear_profile(self):
        # The full v3 loader allows C to consume A's earlier output. A linear shell cannot.
        yaml = self.yaml.replace("name: B, cardinality: ONE_TO_ONE, input: ProbeValue, output: ProbeValue",
                                 "name: B, cardinality: ONE_TO_ONE, input: ProbeValue, output: PresenceProbe")
        bindings = copy.deepcopy(self.bindings)
        bindings["handlers"][1]["outputType"] = "PresenceProbe"
        self.rejected("INCOMPATIBLE_LINK", yaml=yaml, bindings=bindings)

    def test_known_and_unknown_features_do_not_disappear(self):
        for declaration in ("await: {}", "kind: query", "execution: {mode: REMOTE}", "timeout: 5s",
                            "config: {retryLimit: 2}", "unknownFuturePolicy: true", "accepts: [ProbeValue]"):
            with self.subTest(declaration=declaration):
                self.rejected("UNSUPPORTED", yaml=self.yaml.replace("name: A,", f"name: A, {declaration},"))
        for root in ("aspects: {}", "connectors: {}", "pipelines: {}", "materialization: {}", "executionMode: QUEUE_ASYNC"):
            with self.subTest(root=root):
                self.rejected("UNSUPPORTED", yaml=self.yaml + "\n" + root + "\n")
        self.rejected("UNSUPPORTED", yaml=self.yaml.replace("cardinality: ONE_TO_ONE", "cardinality: ONE_TO_MANY"))
        self.rejected("UNSUPPORTED", yaml=self.yaml.replace("transport: LOCAL", "transport: REST"))
        self.rejected("UNSUPPORTED", yaml=self.yaml.replace("platform: COMPUTE", "platform: UNKNOWN"))
        self.rejected("UNSUPPORTED_TYPE", yaml=self.yaml.replace("type: int32", "type: int64"))

    def test_duplicate_steps_do_not_alias(self):
        self.rejected("AMBIGUOUS_STEP", yaml=self.yaml.replace("name: B,", "name: A,"))

    def test_unsafe_generated_names_reject_and_uncommon_valid_name_survives(self):
        for keyword in ("const", "if", "TypedDict", "Callable", "number", "int", "Promise"):
            with self.subTest(keyword=keyword):
                self.rejected("IDENTIFIER", yaml=self.yaml.replace("PresenceProbe", keyword))
        self.rejected("IDENTIFIER", yaml=self.yaml.replace("name: optionalValue", "name: if"))
        bindings = copy.deepcopy(self.bindings)
        bindings["handlers"][0]["handler"] = "if"
        self.rejected("IDENTIFIER", bindings=bindings)
        result, output = self.invoke(yaml=self.yaml.replace("PresenceProbe", "Uncommon_Record7"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("interface Uncommon_Record7", (output / "models.ts").read_text())
        ast.parse((output / "models.py").read_text(), filename="models.py")

    def test_prepared_sources_keep_their_actual_compiled_identity_after_edit(self):
        # Work only on a temporary source copy: this never edits a tracked producer file.
        temporary = tempfile.TemporaryDirectory(prefix="source-copy-", dir=run.TARGET)
        self.addCleanup(temporary.cleanup)
        source = Path(temporary.name)
        for name, content in run.source_snapshot(run.HERE):
            (source / name).write_bytes(content)
        before = run.prepare(skip_resolve=True, source_dir=source)
        emitter = source / "NativeModelEmitter.java"
        emitter.write_text(emitter.read_text().replace("GENERATED research bindings.", "GENERATED amended research bindings."))
        edited_digest = run.snapshot_digest(run.source_snapshot(source))
        self.assertNotEqual(before.generator_tree_sha256, edited_digest)
        result, old_output = self.invoke(prepared=before)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before.generator_tree_sha256,
                         json.loads((old_output / "projection.json").read_text())["provenance"]["generatorTreeSha256"])
        self.assertNotIn("amended research bindings", (old_output / "models.ts").read_text())
        after = run.prepare(skip_resolve=True, source_dir=source)
        self.assertEqual(edited_digest, after.generator_tree_sha256)
        self.assertNotEqual(before.classpath, after.classpath)
        result, new_output = self.invoke(prepared=after)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(after.generator_tree_sha256,
                         json.loads((new_output / "projection.json").read_text())["provenance"]["generatorTreeSha256"])
        self.assertIn("amended research bindings", (new_output / "models.ts").read_text())

    def test_failed_changed_order_oracle_removes_previous_baseline_reference(self):
        temporary = tempfile.TemporaryDirectory(prefix="oracle-refresh-", dir=run.TARGET)
        self.addCleanup(temporary.cleanup)
        output = Path(temporary.name)
        command = [sys.executable, str(run.HERE / "run.py"), "--skip-resolve", "--output", str(output)]
        baseline = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(0, baseline.returncode, baseline.stderr)
        self.assertTrue((output / "java-reference.json").is_file())
        self.assertTrue((output / "java-reference.sha256").is_file())
        changed = subprocess.run([*command, "--dsl", str(run.HERE / "fixtures/changed-order.yaml")],
                                 text=True, capture_output=True)
        self.assertNotEqual(0, changed.returncode)
        self.assertIn("JAVA_REFERENCE_REJECT:oracle only realizes a,b,c in that order", changed.stderr)
        self.assertEqual(["B", "A", "C"], [step["id"] for step in json.loads((output / "projection.json").read_text())["steps"]])
        self.assertFalse((output / "java-reference.json").exists())
        self.assertFalse((output / "java-reference.sha256").exists())

    def test_generated_projections_match_draft202012_schema_and_detect_schema_drift(self):
        schema = json.loads((run.HERE / "projection.schema.json").read_text())
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema)
        for yaml in (self.yaml, (run.HERE / "fixtures/changed-order.yaml").read_text()):
            with self.subTest(changed_order=yaml != self.yaml):
                result, output = self.invoke(yaml=yaml)
                self.assertEqual(0, result.returncode, result.stderr)
                projection = json.loads((output / "projection.json").read_text())
                validator.validate(projection)
                # A schema-only change must not silently contradict an otherwise unchanged golden.
                narrowed = copy.deepcopy(schema)
                narrowed["properties"]["steps"]["items"]["properties"]["retryLimit"]["maximum"] = 1
                with self.assertRaises(ValidationError):
                    Draft202012Validator(narrowed).validate(projection)

    def test_java_reference_is_reproducible_and_bad_fixed_answer_fails(self):
        result, output = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        reference = output / "java-reference.json"
        command = ["java", "-cp", self.prepared.classpath, "JavaReferenceProbe", str(output / "projection.json"),
                   str(run.HERE / "fixtures/scenarios.json"), str(reference)]
        actual = subprocess.run(command, text=True, capture_output=True)
        self.assertEqual(0, actual.returncode, actual.stderr)
        self.assertEqual((run.HERE / "generated/java-reference.json").read_bytes(), reference.read_bytes())
        scenarios = json.loads((run.HERE / "fixtures/scenarios.json").read_text())
        scenarios["cases"][0]["expected"]["result"]["value"] = 6
        mutation = output / "wrong-answer.json"
        mutation.write_text(json.dumps(scenarios))
        bad = subprocess.run([*command[:5], str(mutation), str(reference)], text=True, capture_output=True)
        self.assertNotEqual(0, bad.returncode)
        self.assertIn("JAVA_REFERENCE_MISMATCH:success:", bad.stderr)

    def test_configuration_numbers_preserve_exact_value(self):
        # Profile configuration uses exact numerical value, unlike int32 data's lexical rules.
        raw = json.dumps(self.bindings).replace('"schemaVersion": 1', '"schemaVersion": 1.0')
        raw = raw.replace('"retryLimit": 2', '"retryLimit": 2e0')
        result, output = self.invoke(bindings=raw)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, json.loads((output / "projection.json").read_text())["steps"][0]["retryLimit"])
        for token in ("true", '"2"', "2.0000000000000001", "3", "-1", "99999999999999999999999"):
            with self.subTest(token=token):
                self.rejected("UNSUPPORTED_POLICY", bindings=json.dumps(self.bindings).replace('"retryLimit": 2', '"retryLimit": ' + token))
        for token in ("true", '"1"', "1.0000000000000001", "4294967297"):
            with self.subTest(token=token):
                self.rejected("BINDING_PROFILE", bindings=json.dumps(self.bindings).replace('"schemaVersion": 1', '"schemaVersion": ' + token))

    def test_binding_profile_unknown_policy_and_duplicate_json_keys(self):
        bindings = copy.deepcopy(self.bindings)
        bindings["profile"] = "unknown-profile"
        self.rejected("BINDING_PROFILE", bindings=bindings)
        bindings = copy.deepcopy(self.bindings)
        bindings["handlers"][0]["timeoutMs"] = 1
        self.rejected("UNSUPPORTED", bindings=bindings)
        self.rejected("BINDINGS_FORMAT", bindings=json.dumps(self.bindings).replace('"pipelineId":', '"profile":"tpf-polyglot-probe/0","pipelineId":'))


if __name__ == "__main__":
    unittest.main()
