"""Retrieve verified native binding generation using only Python, Git and GitHub CLI."""

import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import unicodedata
from urllib.parse import quote
from zipfile import BadZipFile, ZIP_DEFLATED, ZIP_STORED, ZipFile
from zlib import error as ZlibError

COMPILER_REPOSITORY = "The-Pipeline-Framework/pipelineframework-compiler"
CONTRACTS_VERSION = "26.11.1"
WORKFLOW = "tpf-node-generation.yml"
WORKFLOW_PATH = f".github/workflows/{WORKFLOW}"
PROFILE = "tpf-polyglot-unary-policy/1"
RECEIPT = "generation-receipt.json"
DIAGNOSTICS = "diagnostics.txt"
TARGETS = json.loads(Path(__file__).with_name("native-generation-targets.json").read_bytes())
GENERATED_FILES = tuple(TARGETS["node"]["files"])
INPUT_NAMES = ("pipeline.yaml", "native-bindings.json")
LIMIT = 8 * 1024 * 1024


class GenerationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise GenerationError(message)


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value, re.ASCII) is not None


def digest(data):
    return hashlib.sha256(data).hexdigest()


def record(value):
    return value if isinstance(value, dict) else {}


def reject_json_constant(token):
    raise ValueError(f"{token} is not a JSON number.")


def json_number_one(value):
    # JSON.parse has one numeric type. Preserve that comparison without
    # admitting Python's True == 1.
    return type(value) in (int, float) and value == 1


def json_object(data, description):
    try:
        value = json.loads(data.decode("utf-8"), parse_constant=reject_json_constant)
    except (ValueError, RecursionError):
        raise GenerationError(f"Invalid JSON in {description}.") from None
    require(isinstance(value, dict), f"Expected a JSON object in {description}.")
    return value


def safe_text(message):
    return "".join(
        character for character in message
        if character in "\t\n" or unicodedata.category(character) not in {"Cc", "Cf", "Cs"}
    )


def execute(args, *, cwd=None, data=None, timeout=60):
    # Spool command output so a malformed or unusually large response cannot
    # allocate unbounded memory. Never echo raw CLI errors or response headers.
    with tempfile.TemporaryFile() as output:
        try:
            result = subprocess.run(
                args, cwd=cwd, input=data, stdout=output, stderr=subprocess.DEVNULL,
                timeout=timeout, check=False,
            )
        except FileNotFoundError:
            raise GenerationError(f"Install {args[0]} and put it on PATH.") from None
        except subprocess.TimeoutExpired:
            raise GenerationError(f"{args[0]} {args[1]} timed out.") from None
        output.seek(0)
        data = output.read(LIMIT + 1)
        require(len(data) <= LIMIT, f"{args[0]} {args[1]} exceeded the output size limit.")
    return result.returncode, data


def checked(args, **options):
    code, output = execute(args, **options)
    require(code == 0, f"{args[0]} {args[1]} failed (exit {code}).")
    return output


def gh_api(endpoint, body=None):
    args = ["gh", "api", "--hostname", "github.com", "--header", "X-GitHub-Api-Version: 2022-11-28"]
    if body is not None:
        args.extend(("--method", "POST", "--input", "-"))
    data = None if body is None else json.dumps(body).encode("utf-8")
    response = checked([*args, endpoint], data=data)
    return json_object(response, "GitHub response") if response else {}


def watch_run(repository, run_id, *, run=execute, pause=time.sleep):
    # Dispatch can return before the exact run is visible on the read endpoint.
    endpoint = f"repos/{repository}/actions/runs/{run_id}?exclude_pull_requests=true"
    for delay in (1, 2, 4, 8, 0):
        code, output = run(
            ["gh", "api", "--hostname", "github.com", "--include", "--silent", endpoint], timeout=10,
        )
        if code == 0:
            break
        status = re.match(rb"HTTP/\S+ (\d{3})\b", output)
        status_code = status[1].decode("ascii") if status else None
        http_error = f"HTTP {status_code}" if status_code else "HTTP status unavailable"
        require(status_code == "404",
                f"Could not read generation run {run_id} ({http_error}). Inspect the linked run before dispatching again.")
        require(delay, f"Generation run {run_id} is not visible yet. Check the linked run before dispatching again.")
        pause(delay)
    code, _ = run(
        ["gh", "run", "watch", "--repo", f"github.com/{repository}", run_id, "--interval", "10"],
        timeout=30 * 60,
    )
    require(code == 0, "Could not watch the generation run. Check its GitHub Actions page.")


def download_run(repository, run_id, destination):
    checked([
        "gh", "run", "download", "--repo", f"github.com/{repository}", run_id,
        "--name", f"tpf-node-generation-{run_id}-1", "--dir", str(destination),
    ], timeout=300)


def input_paths(project):
    require(project == "." or (
        len(project) <= 200 and all(matches(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*", part)
                                    for part in project.split("/"))
    ), "Use a repository-relative project directory without dot segments or special characters.")
    return [name if project == "." else f"{project}/{name}" for name in INPUT_NAMES]


def committed_source(cwd, repository, sha, project):
    def git(*args):
        return checked(["git", *args], cwd=cwd)

    paths = input_paths(project)
    require(git("rev-parse", "HEAD").strip() == sha.encode("ascii"),
            "Check out the requested application revision before generating.")
    require(not git("status", "--porcelain", "--", *paths),
            "Commit changes to pipeline.yaml and native-bindings.json before generating.")
    inputs = []
    for path in paths:
        entry = git("ls-tree", "-z", sha, "--", path)
        blob = re.fullmatch(rb"100(?:644|755) blob ([0-9a-f]{40})\t([^\0]+)\0", entry)
        require(blob and blob[2] == path.encode("ascii"), f"Expected a committed regular file at {path} in {sha}.")
        blob_sha = blob[1].decode("ascii")
        size = git("cat-file", "-s", blob_sha).strip()
        require(size.isdigit() and int(size) <= LIMIT, f"Committed input exceeds the size limit: {path}")
        inputs.append({"path": path, "sha256": digest(git("cat-file", "blob", blob_sha))})
    return {"repository": repository.lower(), "sha": sha, "project": project, "inputs": inputs}


def verify_run(run, source, compiler_sha, run_id, *, completed=True):
    repository = record(run.get("head_repository")).get("full_name")
    path = run.get("path")
    require(
        str(run.get("id")) == run_id and run.get("event") == "workflow_dispatch"
        and isinstance(path, str) and path.split("@", 1)[0] == WORKFLOW_PATH
        and run.get("head_sha") == source["sha"]
        and isinstance(repository, str) and repository.lower() == source["repository"],
        "GitHub returned a run for a different workflow, repository or source revision.",
    )
    require(json_number_one(run.get("run_attempt")),
            "This run was retried. Dispatch a new generation.")
    if completed:
        require(run.get("status") == "completed", "The generation run has not completed.")
    pinned = f"{COMPILER_REPOSITORY}/{WORKFLOW_PATH}@{compiler_sha}"
    references = run.get("referenced_workflows")
    require(isinstance(references, list) and any(
        record(reference).get("path") == pinned and record(reference).get("sha") == compiler_sha
        for reference in references
    ), "The run did not use the requested compiler workflow revision.")
    if completed:
        require(isinstance(run.get("conclusion"), str) and run["conclusion"],
                "The completed generation run has no conclusion.")


def verify_compiler(compiler):
    dependencies = compiler.get("dependencies")
    require(matches(r"pipelineframework-compiler-[0-9.]+(?:-SNAPSHOT)?\.jar", compiler.get("artifact"))
            and matches(r"[0-9a-f]{64}", compiler.get("sha256"))
            and compiler.get("contractsVersion") == CONTRACTS_VERSION
            and isinstance(dependencies, list) and dependencies,
            "Generation receipt is missing compiler or dependency identity.")
    seen = set()
    for dependency in dependencies:
        artifact = record(dependency).get("artifact")
        require(isinstance(artifact, str) and artifact.endswith(".jar") and "SNAPSHOT" not in artifact
                and all(matches(r"[\w-][\w.+-]*", part) for part in artifact.split("/"))
                and artifact not in seen and matches(r"[0-9a-f]{64}", dependency.get("sha256")),
                "Generation receipt has an invalid dependency identity.")
        seen.add(artifact)


def read_regular_file(path):
    error = f"Expected a regular file within the size limit: {path.name}"
    require(stat.S_ISREG(path.lstat().st_mode), error)
    # O_NOFOLLOW keeps a swapped leaf symlink out and O_NONBLOCK prevents
    # opening a swapped FIFO from blocking before fstat can reject it.
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        require(stat.S_ISREG(metadata.st_mode) and metadata.st_size <= LIMIT, error)
        content = stream.read(LIMIT + 1)
    require(len(content) <= LIMIT, error)
    return content


def verify_download(directory, source, compiler_sha, run_id, conclusion, target="node"):
    require(target in TARGETS, "Unsupported generation target.")
    binding = TARGETS[target]
    require(stat.S_ISDIR(directory.lstat().st_mode), "Expected a regular artifact directory.")
    receipt_bytes = read_regular_file(directory / RECEIPT)
    receipt = json_object(receipt_bytes, RECEIPT)
    require(receipt.get("target", "node") == target, "Generation receipt does not match the requested target.")
    compiler = record(receipt.get("compiler"))
    run = record(receipt.get("run"))
    require(json_number_one(receipt.get("schemaVersion"))
            and receipt.get("profile") == PROFILE and run.get("id") == run_id and run.get("attempt") == "1"
            and compiler.get("repository") == COMPILER_REPOSITORY and compiler.get("sha") == compiler_sha,
            "Generation receipt does not match this run, compiler revision or profile.")
    success = conclusion == "success"
    require(receipt.get("status") == ("success" if success else "failed"),
            "Generation receipt and workflow conclusion disagree.")
    received_source = record(receipt.get("source"))
    if success:
        verify_compiler(compiler)
        require(received_source == source, "Generation receipt does not match the committed inputs.")
    else:
        require(all(received_source.get(key) == source[key] for key in ("repository", "sha", "project"))
                and ("inputs" not in received_source or received_source["inputs"] == source["inputs"]),
                "Failed generation receipt does not match the requested source revision.")
    expected = (*binding["files"], DIAGNOSTICS) if success else (DIAGNOSTICS,)
    checksums = record(receipt.get("files"))
    require({path.name for path in directory.iterdir()} == {*expected, RECEIPT} and set(checksums) == set(expected),
            "Generation artifact contains an unexpected set of files.")
    files = {}
    for name in expected:
        content = read_regular_file(directory / name)
        require(digest(content) == checksums[name], f"Generation artifact digest mismatch for {name}.")
        files[name] = content
    if success:
        projection = json_object(files["projection.json"], "projection.json")
        provenance = record(projection.get("provenance"))
        require(json_number_one(projection.get("schemaVersion"))
                and projection.get("profile") == PROFILE and projection.get("kind") == binding["kind"]
                and provenance.get("dslSha256") == source["inputs"][0]["sha256"]
                and provenance.get("bindingsSha256") == source["inputs"][1]["sha256"],
                "Compiler projection does not match the requested profile or committed inputs.")
        if "compiled-truth.zip" in files:
            verify_compiled_truth(files)
    return {**files, RECEIPT: receipt_bytes}


def verify_compiled_truth(files):
    expected = {
        "META-INF/pipeline/native-execution-projection.json": files["projection.json"],
        "META-INF/pipeline/pipeline-contract.json": files["pipeline-contract.json"],
    }
    try:
        with ZipFile(BytesIO(files["compiled-truth.zip"])) as archive:
            entries = archive.infolist()
            require(len(entries) == len(expected) and {entry.filename for entry in entries} == set(expected),
                    "Unexpected Compiled Truth resources.")
            for entry in entries:
                content = expected[entry.filename]
                require(entry.compress_type in (ZIP_STORED, ZIP_DEFLATED)
                        and entry.file_size == len(content) and archive.read(entry) == content,
                        f"Compiled Truth resource differs from generated output: {entry.filename}.")
    except (BadZipFile, RuntimeError, EOFError, OSError, ValueError, ZlibError):
        raise GenerationError("Invalid Compiled Truth ZIP.") from None


def require_new_output(output):
    require(not os.path.lexists(output), f"Output already exists at {output}. Choose a new directory.")


def generate_remote(repository, ref, source_sha, compiler_sha, output_arg, project=".", *,
                    cwd=None, api=gh_api, watch=watch_run, download=download_run, target="node", run_id=None):
    require(isinstance(target, str) and target in TARGETS, "Unsupported generation target.")
    require(run_id is None or matches(r"[1-9][0-9]*", run_id), "Provide a positive decimal --run-id.")
    # Fail before Git/network work: secure artifact reads require POSIX open flags.
    require(os.name == "posix" and all(hasattr(os, flag) for flag in ("O_NOFOLLOW", "O_NONBLOCK")),
            "The Python client requires POSIX O_NOFOLLOW/O_NONBLOCK support; native Windows is unsupported.")
    require(matches(r"[\w-]+/[\w.-]+", repository) and repository.split("/")[1] not in {".", ".."},
            "Use a GitHub repository in OWNER/REPO form.")
    require(ref and not ref.startswith("-"), "Provide the branch or tag to dispatch.")
    require(all(matches(r"[0-9a-f]{40}", sha) for sha in (source_sha, compiler_sha)),
            "Provide exact 40-character application and compiler commit SHAs.")
    checked(["git", "check-ref-format", f"refs/heads/{ref}"], cwd=cwd)
    if cwd is None:
        cwd = os.fsdecode(checked(["git", "rev-parse", "--show-toplevel"]).removesuffix(b"\n"))
    output = Path(os.path.abspath(output_arg))
    require_new_output(output)
    require(output.parent.is_dir(), "The output parent directory must exist.")
    source = committed_source(cwd, repository, source_sha, project)
    endpoint = f"repos/{repository}"
    resuming = run_id is not None
    # Recovery never resolves the mutable ref or dispatches another workflow.
    if run_id is None:
        remote = api(f"{endpoint}/commits/{quote(ref, safe='')}")
        require(remote.get("sha") == source_sha, "Push the committed revision and use a ref pointing to its exact SHA.")
        try:
            dispatch = api(f"{endpoint}/actions/workflows/{WORKFLOW}/dispatches", {
                "ref": ref, "return_run_details": True,
                "inputs": {"source_sha": source_sha, "compiler_sha": compiler_sha, "project": project,
                           **({"target": target} if target != "node" else {})},
            })
            run_id = str(dispatch.get("workflow_run_id"))
            require(matches(r"[0-9]+", run_id), "GitHub did not return the dispatched run ID.")
        except GenerationError as error:
            raise GenerationError(f"{error} Check Actions before dispatching again.") from None
    run_endpoint = f"{endpoint}/actions/runs/{run_id}"
    run_url = f"https://github.com/{repository}/actions/runs/{run_id}"
    print(f"Generation run {run_url}", flush=True)
    print(f"To resume without dispatching, repeat this command with --run-id {run_id} and the same source/compiler/project/target.", flush=True)
    if resuming:
        # Check identity before waiting too; completion and artifact checks below
        # still run even if the first read already reports success.
        verify_run(api(run_endpoint), source, compiler_sha, run_id, completed=False)
    watch(repository, run_id)
    run = api(run_endpoint)
    verify_run(run, source, compiler_sha, run_id)

    with tempfile.TemporaryDirectory(prefix=".tpf-generation-", dir=output.parent) as temporary:
        unpacked = Path(temporary) / "download"
        try:
            download(repository, run_id, unpacked)
        except GenerationError as error:
            raise GenerationError(f"Could not retrieve the generation artifact. Inspect {run_url}. {error}") from None
        files = verify_download(unpacked, source, compiler_sha, run_id, run["conclusion"], target)
        latest = api(run_endpoint)
        verify_run(latest, source, compiler_sha, run_id)
        require(latest["conclusion"] == run["conclusion"], "The generation run conclusion changed during download.")
        if run["conclusion"] != "success":
            diagnostics = safe_text(files[DIAGNOSTICS].decode("utf-8", errors="replace"))
            raise GenerationError(f"Generation failed. Inspect {run_url}.\n{diagnostics}")
        require(committed_source(cwd, repository, source_sha, project) == source,
                "The committed inputs changed during download.")
        staged = Path(temporary) / "verified"
        staged.mkdir()
        for name, content in files.items():
            with (staged / name).open("xb") as file:
                file.write(content)
        require_new_output(output)
        staged.rename(output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", metavar="OWNER/REPO")
    parser.add_argument("ref")
    parser.add_argument("source_sha")
    parser.add_argument("compiler_sha")
    parser.add_argument("output", metavar="NEW_OUTPUT_DIR")
    parser.add_argument("project", nargs="?", default=".", metavar="PROJECT_DIR")
    parser.add_argument("--target", choices=tuple(TARGETS), default="node",
                        help="explicit compiler output target (default: node)")
    parser.add_argument("--run-id", help="resume verification/retrieval of an existing run; never dispatch")
    args = parser.parse_args()
    try:
        version = re.match(rb"gh version (\d+)\.(\d+)", checked(["gh", "--version"]))
        require(version and tuple(map(int, version.groups())) >= (2, 100),
                "Install GitHub CLI 2.100 or newer and sign in with gh auth login.")
        output = generate_remote(args.repository, args.ref, args.source_sha, args.compiler_sha, args.output, args.project, target=args.target, run_id=args.run_id)
        print(safe_text(f"Verified generated bindings in {output}"))
        return 0
    except (GenerationError, OSError) as error:
        print(safe_text(str(error)), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrupted. Resume the printed run with --run-id; check Actions before dispatching again.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
