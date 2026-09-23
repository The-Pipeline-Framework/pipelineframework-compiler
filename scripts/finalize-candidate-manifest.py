#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
metadata = json.loads((root / "build-metadata.json").read_text())
publication = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "runId": int(os.environ["GITHUB_RUN_ID"]),
    "runAttempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
    "workflowPath": ".github/workflows/tpf-candidate-publish.yml",
    "event": "workflow_run",
}
manifest = {
    "schemaVersion": 1,
    "repository": metadata["repository"],
    "component": "compiler",
    "sourceSha": metadata["sourceSha"],
    "pullRequestNumber": metadata["pullRequestNumber"],
    "candidateVersion": metadata["candidateVersion"],
    "provenance": {"build": metadata["provenance"]["build"], "publication": publication},
    "mavenArtifacts": metadata["mavenArtifacts"],
    "images": [],
}
manifest_dir = root / "candidate-manifest"
event_dir = root / "candidate-event"
manifest_dir.mkdir(parents=True, exist_ok=True)
event_dir.mkdir(parents=True, exist_ok=True)
manifest_path = manifest_dir / "candidate-manifest.json"
manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
manifest_path.write_bytes(manifest_bytes)
event = {
    "schema_version": 1,
    "source_repository": metadata["repository"],
    "source_sha": metadata["sourceSha"],
    "pull_request_number": metadata["pullRequestNumber"],
    "component": "compiler",
    "candidate_version": metadata["candidateVersion"],
    "publication_run_id": publication["runId"],
    "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
    "compatibility_set_id": None,
}
(event_dir / "event.json").write_text(json.dumps(event, indent=2, sort_keys=True) + "\n")
