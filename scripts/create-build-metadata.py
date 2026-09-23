#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
repository = os.environ["GITHUB_REPOSITORY"]
source_repository = os.environ["SOURCE_REPOSITORY"]
source_sha = os.environ["SOURCE_SHA"].lower()
event = os.environ["GITHUB_EVENT_NAME"]
pull_request = os.environ.get("PULL_REQUEST_NUMBER", "")
candidate_version = json.loads((root / "candidate-manifest.json").read_text())["candidateVersion"]
if event == "pull_request":
    if not pull_request.isdigit():
        raise SystemExit("pull request number is required for PR candidate metadata")
    pull_request_number = int(pull_request)
    expected_suffix = f"-pr.{pull_request_number}.{source_sha[:12]}"
elif event == "push":
    pull_request_number = None
    expected_suffix = f"-main.{source_sha[:12]}"
else:
    raise SystemExit(f"unsupported candidate build event: {event}")
if not candidate_version.endswith(expected_suffix):
    raise SystemExit("reactor candidate version does not match source event and SHA")

coordinates = [("org.pipelineframework", "pipelineframework-compiler", "jar")]
artifacts = []
for group_id, artifact_id, packaging in coordinates:
    directory = root / "repository" / pathlib.Path(*group_id.split(".")) / artifact_id / candidate_version
    names = [f"{artifact_id}-{candidate_version}.pom"]
    if packaging == "jar":
        names.append(f"{artifact_id}-{candidate_version}.jar")
    files = []
    for name in names:
        path = directory / name
        if not path.is_file() or path.is_symlink():
            raise SystemExit(f"missing candidate file: {path}")
        files.append({"name": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    artifacts.append({
        "groupId": group_id,
        "artifactId": artifact_id,
        "version": candidate_version,
        "packaging": packaging,
        "files": files,
    })

metadata = {
    "schemaVersion": 1,
    "repository": repository,
    "sourceRepository": source_repository,
    "component": "compiler",
    "sourceSha": source_sha,
    "pullRequestNumber": pull_request_number,
    "candidateVersion": candidate_version,
    "provenance": {
        "build": {
            "repository": repository,
            "runId": int(os.environ["GITHUB_RUN_ID"]),
            "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
            "workflowPath": ".github/workflows/tpf-candidate-build.yml",
            "event": event,
        }
    },
    "mavenArtifacts": artifacts,
}
(root / "build-metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
