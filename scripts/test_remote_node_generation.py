import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

import remote_node_generation as client

REPOSITORY = "owner/app"
COMPILER_SHA = "a" * 40
RUN_ID = "123456"
PROJECT = "apps/invoice"
REF = "test/branch"
VECTORS = json.loads(Path(__file__).with_name("node-generation-client-cases.json").read_bytes())


class Fixture:
    def __init__(self, cwd):
        self.cwd = cwd
        self.output = cwd / "generated"
        self.project = cwd / PROJECT
        self.project.mkdir(parents=True)
        # Force a checkout conversion so matching the Git blob cannot happen
        # accidentally by hashing the working-tree file.
        (cwd / ".gitattributes").write_text("*.yaml text eol=crlf\n")
        (self.project / "pipeline.yaml").write_bytes(b"version: 3\n")
        (self.project / "native-bindings.json").write_bytes(b"{}\n")
        self.git("init", "-q")
        self.commit()
        (self.project / "pipeline.yaml").unlink()
        self.git("checkout-index", "--index", "--force", "--all")
        self.source_sha = self.git("rev-parse", "HEAD").decode().strip()
        self.source = {
            "repository": REPOSITORY,
            "sha": self.source_sha,
            "project": PROJECT,
            "inputs": [
                {"path": f"{PROJECT}/pipeline.yaml", "sha256": client.digest(b"version: 3\n")},
                {"path": f"{PROJECT}/native-bindings.json", "sha256": client.digest(b"{}\n")},
            ],
        }
        self.run = {
            "id": int(RUN_ID), "path": client.WORKFLOW_PATH, "event": "workflow_dispatch", "run_attempt": 1,
            "status": "completed", "conclusion": "success", "head_sha": self.source_sha,
            "head_repository": {"full_name": REPOSITORY},
            "referenced_workflows": [{
                "path": f"{client.COMPILER_REPOSITORY}/{client.WORKFLOW_PATH}@{COMPILER_SHA}", "sha": COMPILER_SHA,
            }],
        }
        self.projection = {
            "schemaVersion": 1, "profile": "tpf-polyglot-unary-policy/1", "kind": "compiler-node-local",
            "provenance": {
                "dslSha256": self.source["inputs"][0]["sha256"],
                "bindingsSha256": self.source["inputs"][1]["sha256"],
            },
        }
        self.files = {
            "application.ts": VECTORS["application"].encode(),
            "models.ts": VECTORS["models"].encode(),
            "projection.json": json.dumps(self.projection).encode(),
            "diagnostics.txt": b"",
        }
        self.receipt = {
            "schemaVersion": 1, "profile": "tpf-polyglot-unary-policy/1", "status": "success",
            "run": {"id": RUN_ID, "attempt": "1"}, "source": copy.deepcopy(self.source),
            "compiler": {
                "repository": "The-Pipeline-Framework/pipelineframework-compiler",
                "sha": COMPILER_SHA,
                "artifact": "pipelineframework-compiler-26.11.2-SNAPSHOT.jar",
                "sha256": "b" * 64, "contractsVersion": "26.11.1",
                "dependencies": [{
                    "artifact": "org/pipelineframework/pipelineframework-dsl/26.11.1/pipelineframework-dsl-26.11.1.jar",
                    "sha256": "c" * 64,
                }],
            },
            "files": {name: client.digest(content) for name, content in self.files.items()},
        }
        self.api_calls = []
        self.watches = []
        self.downloads = []
        self.remote_sha = self.source_sha
        self.latest = None
        self.after_download = lambda path: None

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.cwd, stderr=subprocess.DEVNULL,
        )

    def commit(self):
        self.git("add", "-A")
        self.git("-c", "user.name=Generation test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "Generation inputs")

    def api(self, endpoint, body=None):
        self.api_calls.append((endpoint, body))
        if endpoint.endswith("/commits/test%2Fbranch"):
            return {"sha": self.remote_sha}
        if endpoint == "repos/owner/app/actions/workflows/tpf-node-generation.yml/dispatches":
            assert body == {
                "ref": REF, "return_run_details": True,
                "inputs": {"source_sha": self.source_sha, "compiler_sha": COMPILER_SHA, "project": PROJECT},
            }
            return {"workflow_run_id": int(RUN_ID)}
        assert endpoint == f"repos/{REPOSITORY}/actions/runs/{RUN_ID}"
        return copy.deepcopy(self.latest if self.latest is not None and len(self.api_calls) == 4 else self.run)

    def download(self, repository, run_id, path):
        assert (repository, run_id) == (REPOSITORY, RUN_ID)
        self.downloads.append(path)
        path.mkdir()
        for name, content in self.files.items():
            (path / name).write_bytes(content)
        (path / "generation-receipt.json").write_bytes(json.dumps(self.receipt).encode())
        self.after_download(path)

    def generate(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return client.generate_remote(
                REPOSITORY, REF, self.source_sha, COMPILER_SHA, self.output, PROJECT,
                cwd=self.cwd, api=self.api, watch=lambda *args: self.watches.append(args), download=self.download,
            )

    def fail_workflow(self, diagnostic=b"pipeline.yaml:8 unsupported transport\n"):
        self.run["conclusion"] = "failure"
        self.receipt["status"] = "failed"
        self.files = {"diagnostics.txt": diagnostic}
        self.receipt["files"] = {name: client.digest(content) for name, content in self.files.items()}

    def change(self, target, path, value):
        if target == "file":
            self.files[path] = value.encode()
            return
        if target == "latest":
            self.latest = copy.deepcopy(self.run)
        record = getattr(self, target)
        keys = path.split(".")
        for key in keys[:-1]:
            record = record[int(key) if isinstance(record, list) else key]
        record[keys[-1]] = value
        if target == "projection":
            content = json.dumps(self.projection).encode()
            self.files["projection.json"] = content
            self.receipt["files"]["projection.json"] = client.digest(content)


class RemoteGenerationTest(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return Fixture(Path(temporary.name))

    def no_output(self, fixture):
        self.assertFalse(os.path.lexists(fixture.output))
        self.assertEqual(list(fixture.cwd.glob(".tpf-generation-*")), [])

    def test_shared_receipt_run_and_artifact_matrix(self):
        for case in VECTORS["cases"]:
            with self.subTest(case["name"]):
                fixture = self.fixture()
                if "change" in case:
                    fixture.change(*case["change"])
                    with self.assertRaisesRegex(client.GenerationError, case["error"]):
                        fixture.generate()
                    self.no_output(fixture)
                else:
                    self.assertEqual(fixture.generate(), fixture.output)
                    self.assertEqual({file.name for file in fixture.output.iterdir()},
                                     {*fixture.files, "generation-receipt.json"})
                    for name, content in fixture.files.items():
                        self.assertEqual((fixture.output / name).read_bytes(), content)
                    self.assertEqual(json.loads((fixture.output / client.RECEIPT).read_bytes()), fixture.receipt)
                    self.assertEqual(fixture.watches, [(REPOSITORY, RUN_ID)])
                    self.assertEqual(len(fixture.api_calls), 4)
                self.assertEqual(sum(body is not None for _, body in fixture.api_calls), 1)

    def test_committed_bytes_are_read_from_git(self):
        fixture = self.fixture()
        self.assertEqual((fixture.project / "pipeline.yaml").read_bytes(), b"version: 3\r\n")
        self.assertEqual(client.committed_source(fixture.cwd, REPOSITORY, fixture.source_sha, PROJECT), fixture.source)

    def test_ref_mismatch_and_local_edit_fail_before_dispatch(self):
        fixture = self.fixture()
        fixture.remote_sha = "f" * 40
        with self.assertRaisesRegex(client.GenerationError, "Push the committed revision"):
            fixture.generate()
        self.assertEqual(len(fixture.api_calls), 1)
        (fixture.project / "pipeline.yaml").write_bytes(b"version: 2\n")
        with self.assertRaisesRegex(client.GenerationError, "Commit changes"):
            fixture.generate()
        self.assertEqual(len(fixture.api_calls), 1)
        self.no_output(fixture)

    def test_rejects_symlink_and_oversized_committed_git_blobs(self):
        for symlink in (True, False):
            with self.subTest(symlink=symlink):
                fixture = self.fixture()
                authored = fixture.project / "pipeline.yaml"
                authored.unlink()
                if symlink:
                    authored.symlink_to("native-bindings.json")
                else:
                    authored.write_bytes(b"x" * (client.LIMIT + 1))
                fixture.commit()
                fixture.source_sha = fixture.git("rev-parse", "HEAD").decode().strip()
                with self.assertRaisesRegex(client.GenerationError, "committed regular file|size limit"):
                    fixture.generate()
                self.assertEqual(fixture.api_calls, [])
                self.no_output(fixture)

    def test_project_traversal_and_existing_output_are_rejected(self):
        for name in ("..", "/abs/path", "apps/../other", "x\\path", "apps//other"):
            with self.subTest(name=name), self.assertRaisesRegex(client.GenerationError, "repository-relative"):
                client.input_paths(name)
        fixture = self.fixture()
        fixture.output.symlink_to(fixture.cwd / "missing")
        with self.assertRaisesRegex(client.GenerationError, "Output already exists"):
            fixture.generate()
        self.assertEqual(fixture.api_calls, [])

    def test_rejects_symlinked_oversized_and_missing_download_entries(self):
        for mutation in ("symlink", "oversized", "missing", "directory symlink"):
            with self.subTest(mutation=mutation):
                fixture = self.fixture()

                def change_download(directory):
                    if mutation == "directory symlink":
                        target = directory.with_name("real")
                        directory.rename(target)
                        directory.symlink_to(target, target_is_directory=True)
                        return
                    file = directory / "models.ts"
                    file.unlink()
                    if mutation == "symlink":
                        target = fixture.cwd / "target"
                        target.write_bytes(fixture.files["models.ts"])
                        file.symlink_to(target)
                    elif mutation == "oversized":
                        with file.open("wb") as stream:
                            stream.truncate(client.LIMIT + 1)

                fixture.after_download = change_download
                with self.assertRaisesRegex(client.GenerationError, "regular|unexpected set"):
                    fixture.generate()
                self.no_output(fixture)

    def test_failure_checks_receipt_and_prints_only_verified_safe_diagnostics(self):
        fixture = self.fixture()
        fixture.fail_workflow(b"pipeline.yaml:8 unsupported transport\n\x1b[0m\x00\r"
                              + "\u202e".encode("utf-8"))
        del fixture.receipt["source"]["inputs"]
        with self.assertRaisesRegex(client.GenerationError, r"Generation failed[\s\S]*unsupported transport") as failure:
            fixture.generate()
        self.assertNotRegex(str(failure.exception), "[\x00\x1b\r\u202e]")
        self.assertEqual(len(fixture.api_calls), 4)
        self.no_output(fixture)

        fixture = self.fixture()
        fixture.fail_workflow()
        fixture.files[client.DIAGNOSTICS] += b"forged diagnostic\n"
        with self.assertRaisesRegex(client.GenerationError, "digest mismatch") as failure:
            fixture.generate()
        self.assertNotIn("forged diagnostic", str(failure.exception))
        self.no_output(fixture)

        fixture = self.fixture()
        fixture.fail_workflow()
        fixture.receipt["source"]["project"] = "another"
        with self.assertRaisesRegex(client.GenerationError, "Failed generation receipt"):
            fixture.generate()
        self.no_output(fixture)

        # The host either omits inputs after an early failure or records the
        # exact input array after loading the blobs. Null is malformed.
        fixture = self.fixture()
        fixture.fail_workflow()
        fixture.receipt["source"]["inputs"] = None
        with self.assertRaisesRegex(client.GenerationError, "Failed generation receipt"):
            fixture.generate()
        self.no_output(fixture)

    def test_rechecks_checkout_and_destination_after_download(self):
        fixture = self.fixture()
        fixture.after_download = lambda _: (fixture.project / "pipeline.yaml").write_bytes(b"changed\n")
        with self.assertRaisesRegex(client.GenerationError, "Commit changes"):
            fixture.generate()
        self.no_output(fixture)

        fixture = self.fixture()
        fixture.after_download = lambda _: fixture.output.mkdir()
        with self.assertRaisesRegex(client.GenerationError, "Output already exists"):
            fixture.generate()
        self.assertEqual(list(fixture.output.iterdir()), [])
        self.assertEqual(list(fixture.cwd.glob(".tpf-generation-*")), [])

    def test_rejects_duplicate_dependency_and_receipt_type_mismatch(self):
        fixture = self.fixture()
        dependencies = fixture.receipt["compiler"]["dependencies"]
        dependencies.append(copy.deepcopy(dependencies[0]))
        with self.assertRaisesRegex(client.GenerationError, "invalid dependency identity"):
            fixture.generate()
        self.no_output(fixture)

        fixture = self.fixture()
        fixture.receipt["schemaVersion"] = True
        with self.assertRaisesRegex(client.GenerationError, "compiler revision or profile"):
            fixture.generate()
        self.no_output(fixture)

    def test_dispatch_is_never_retried(self):
        fixture = self.fixture()
        api = Mock(side_effect=[{"sha": fixture.source_sha}, {}])
        with self.assertRaisesRegex(client.GenerationError, "Check Actions before dispatching again"):
            client.generate_remote(REPOSITORY, REF, fixture.source_sha, COMPILER_SHA, fixture.output, PROJECT,
                                   cwd=fixture.cwd, api=api)
        self.assertEqual(api.call_count, 2)
        self.no_output(fixture)

    def test_waits_for_dispatched_run_and_then_watches_that_run(self):
        run = Mock(side_effect=[(1, b"HTTP/2.0 404 Not Found\n"), (0, b""), (0, b"")])
        pause = Mock()
        client.watch_run(REPOSITORY, RUN_ID, run=run, pause=pause)
        pause.assert_called_once_with(1)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])
        self.assertEqual(run.call_args_list[0].args[0][-1],
                         f"repos/{REPOSITORY}/actions/runs/{RUN_ID}?exclude_pull_requests=true")
        self.assertEqual(run.call_args.args[0], [
            "gh", "run", "watch", "--repo", f"github.com/{REPOSITORY}", RUN_ID, "--interval", "10",
        ])

    def test_bounds_404_polling_and_does_not_retry_other_errors(self):
        pause = Mock()
        run = Mock(return_value=(1, b"HTTP/2.0 404 Not Found\n"))
        with self.assertRaisesRegex(client.GenerationError, "not visible yet"):
            client.watch_run(REPOSITORY, RUN_ID, run=run, pause=pause)
        self.assertEqual(run.call_count, 5)
        self.assertEqual([call.args[0] for call in pause.call_args_list], [1, 2, 4, 8])
        for status, message in ((b"HTTP/2.0 503 Service Unavailable\n", "HTTP 503"),
                                (b"", "HTTP status unavailable")):
            run = Mock(return_value=(1, status + b"X-Private: do-not-echo\n"))
            pause = Mock()
            with self.assertRaisesRegex(client.GenerationError, message) as failure:
                client.watch_run(REPOSITORY, RUN_ID, run=run, pause=pause)
            run.assert_called_once()
            pause.assert_not_called()
            self.assertNotIn("do-not-echo", str(failure.exception))

    def test_cli_uses_git_and_gh_from_path_with_no_other_runtime(self):
        fixture = self.fixture()
        binaries = fixture.cwd / "bin"
        binaries.mkdir()
        real_git = shutil.which("git")
        (binaries / "git").symlink_to(real_git)
        versioned_cli = binaries / "gh"
        versioned_cli.write_text("#!/bin/sh\nprintf 'gh version 2.99.0\\n'\n")
        versioned_cli.chmod(0o755)
        args = [sys.executable, "-B", client.__file__, REPOSITORY, REF, fixture.source_sha,
                COMPILER_SHA, str(fixture.output), PROJECT]
        environment = {**os.environ, "PATH": str(binaries)}
        result = subprocess.run(args, cwd=fixture.cwd, env=environment, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"GitHub CLI 2.100 or newer", result.stderr)

        source = binaries / "artifact"
        fixture.download(REPOSITORY, RUN_ID, source)
        run_json = json.dumps(fixture.run)
        fixture_payload = {
            "ref": REF, "return_run_details": True,
            "inputs": {"source_sha": fixture.source_sha, "compiler_sha": COMPILER_SHA, "project": PROJECT},
        }
        versioned_cli.write_text(
            f"#!{sys.executable}\n"
            "import json, shutil, sys\n"
            "from pathlib import Path\n"
            "args = sys.argv[1:]\n"
            "if args == ['--version']:\n"
            "    print('gh version 2.100.0')\n"
            "elif args[:2] == ['api', '--hostname']:\n"
            "    if args[-1].endswith('/commits/test%2Fbranch'):\n"
            f"        print(json.dumps({{'sha': {fixture.source_sha!r}}}))\n"
            "    elif args[-1] == 'repos/owner/app/actions/workflows/tpf-node-generation.yml/dispatches':\n"
            "        assert args[-5:-1] == ['--method', 'POST', '--input', '-']\n"
            f"        assert json.load(sys.stdin) == {fixture_payload!r}\n"
            "        print(json.dumps({'workflow_run_id': 123456}))\n"
            "    else:\n"
            "        assert args[-1] in ('repos/owner/app/actions/runs/123456', "
            "'repos/owner/app/actions/runs/123456?exclude_pull_requests=true')\n"
            f"        print({run_json!r})\n"
            "elif args[:2] == ['run', 'watch']:\n"
            "    assert args[2:] == ['--repo', 'github.com/owner/app', '123456', '--interval', '10']\n"
            "else:\n"
            "    assert args[:-1] == ['run', 'download', '--repo', 'github.com/owner/app', '123456', "
            "'--name', 'tpf-node-generation-123456-1', '--dir']\n"
            f"    shutil.copytree(Path({str(source)!r}), Path(args[-1]))\n"
        )
        result = subprocess.run(args, cwd=fixture.cwd, env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn(b"Verified generated bindings", result.stdout)
        self.assertEqual((fixture.output / "models.ts").read_bytes(), fixture.files["models.ts"])


if __name__ == "__main__":
    unittest.main()


class PlatformPreflight(unittest.TestCase):
    def test_unsupported_platform_fails_before_git_or_dispatch(self):
        for operating_system in (SimpleNamespace(name="nt"), SimpleNamespace(name="posix")):
            with self.subTest(platform=operating_system.name):
                api = Mock()
                with patch.object(client, "os", operating_system), patch.object(client, "checked") as checked:
                    with self.assertRaisesRegex(client.GenerationError, "requires POSIX"):
                        client.generate_remote(REPOSITORY, REF, "a" * 40, COMPILER_SHA, "output", api=api)
                checked.assert_not_called()
                api.assert_not_called()
