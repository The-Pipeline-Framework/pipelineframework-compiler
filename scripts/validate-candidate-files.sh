#!/usr/bin/env bash
set -euo pipefail

root=${1:?usage: validate-candidate-files.sh BUILD_ROOT CURRENT_PR_JSON}
current_pr=${2:?}
python3 - "$root" "$current_pr" <<'PY'
import hashlib
import json
import os
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
pr_path = pathlib.Path(sys.argv[2])
base_version = ET.parse("pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", "")
assert base_version.endswith("-SNAPSHOT") and re.fullmatch(r"\d+\.\d+\.\d+-SNAPSHOT", base_version)
base_version = base_version.removesuffix("-SNAPSHOT")
meta_path = root / "build-metadata.json"
metadata = json.loads(meta_path.read_text())
base_repo = "The-Pipeline-Framework/pipelineframework-compiler"
coords = [("org.pipelineframework", "pipelineframework-compiler", "jar")]
required_keys = {"schemaVersion", "repository", "sourceRepository", "component", "sourceSha",
                 "pullRequestNumber", "candidateVersion", "provenance", "mavenArtifacts"}
assert set(metadata) == required_keys, "unexpected or missing build metadata fields"
assert metadata["schemaVersion"] == 1 and metadata["repository"] == base_repo
assert metadata["component"] == "compiler"
source_sha = metadata["sourceSha"]
assert re.fullmatch(r"[0-9a-f]{40}", source_sha), "invalid source SHA"
build = metadata["provenance"]["build"]
assert build == {
    "repository": base_repo,
    "runId": int(os.environ["BUILD_RUN_ID"]),
    "runAttempt": int(os.environ["BUILD_RUN_ATTEMPT"]),
    "workflowPath": ".github/workflows/tpf-candidate-build.yml",
    "event": os.environ["BUILD_RUN_EVENT"],
}, "build provenance does not match triggering workflow_run"
assert os.environ["BUILD_RUN_PATH"] == ".github/workflows/tpf-candidate-build.yml"
assert os.environ["BUILD_RUN_REPOSITORY"] == base_repo
version = metadata["candidateVersion"]
event = os.environ["BUILD_RUN_EVENT"]
if event == "pull_request":
    number = metadata["pullRequestNumber"]
    assert isinstance(number, int) and number > 0
    associated = json.loads(os.environ.get("BUILD_ASSOCIATED_PR_NUMBERS", "[]"))
    assert associated and number in associated, "PR number is not associated with triggering workflow run"
    assert re.fullmatch(rf"{re.escape(base_version)}-pr\.{number}\.{source_sha[:12]}", version), "invalid PR candidate version"
    pr = json.loads(pr_path.read_text())
    assert pr["state"] == "open", "candidate PR is no longer open"
    assert pr["number"] == number
    head = pr["head"]
    assert head["sha"] == source_sha, "PR head changed since build"
    assert head["repo"]["full_name"].lower() == metadata["sourceRepository"].lower()
    assert os.environ["BUILD_RUN_HEAD_BRANCH"] == head["ref"]
    if head["repo"]["full_name"].lower() != base_repo.lower():
        labels = {label["name"] for label in pr.get("labels", [])}
        assert "safe-to-system-test" in labels, "fork PR lacks safe-to-system-test label"
elif event == "push":
    assert metadata["pullRequestNumber"] is None
    assert metadata["sourceRepository"] == base_repo
    assert os.environ["BUILD_RUN_HEAD_BRANCH"] == "main"
    assert source_sha == os.environ["BUILD_RUN_HEAD_SHA"]
    assert re.fullmatch(rf"{re.escape(base_version)}-main\.{source_sha[:12]}", version), "invalid main candidate version"
else:
    raise AssertionError(f"unsupported build event: {event}")

artifacts = metadata["mavenArtifacts"]
assert len(artifacts) == len(coords), "unexpected coordinate count"
expected_paths = set()
for artifact, (group, name, packaging) in zip(artifacts, coords, strict=True):
    assert set(artifact) == {"groupId", "artifactId", "version", "packaging", "files"}
    assert (artifact["groupId"], artifact["artifactId"], artifact["packaging"]) == (group, name, packaging)
    assert artifact["version"] == version
    directory = root / "repository" / pathlib.Path(*group.split(".")) / name / version
    expected_names = [f"{name}-{version}.pom"]
    if packaging == "jar":
        expected_names.append(f"{name}-{version}.jar")
    assert [f["name"] for f in artifact["files"]] == expected_names, f"bad files for {name}"
    for file in artifact["files"]:
        path = directory / file["name"]
        assert path.is_file() and not path.is_symlink(), f"missing/unsafe candidate file: {path}"
        assert hashlib.sha256(path.read_bytes()).hexdigest() == file["sha256"], f"checksum mismatch: {path}"
        expected_paths.add(path.relative_to(root / "repository").as_posix())
    pom = ET.parse(directory / f"{name}-{version}.pom").getroot()
    ns = "{http://maven.apache.org/POM/4.0.0}"
    assert pom.findtext(ns + "groupId") == group, f"bad POM group: {name}"
    assert pom.findtext(ns + "artifactId") == name and pom.findtext(ns + "version") == version
actual_paths = {p.relative_to(root / "repository").as_posix() for p in (root / "repository").rglob("*") if p.is_file()}
assert actual_paths == expected_paths, f"unexpected repository files: {actual_paths ^ expected_paths}"
assert not any(p.is_symlink() for p in (root / "repository").rglob("*")), "symlink in candidate repository"
PY
