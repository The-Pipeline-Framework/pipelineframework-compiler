#!/usr/bin/env bash
set -euo pipefail

mode=${1:?usage: candidate-version.sh pr|main BASE_VERSION PR_NUMBER SHA}
base_version=${2:?}
pr_number=${3:--}
sha=${4:?}
[[ "$base_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || { echo "base version must be plain semver" >&2; exit 2; }
[[ "$sha" =~ ^[0-9a-fA-F]{40}$ ]] || { echo "commit SHA must be a full 40-character SHA" >&2; exit 2; }
case "$mode" in
  pr)
    [[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || { echo "PR number is required" >&2; exit 2; }
    printf '%s-pr.%s.%s\n' "$base_version" "$pr_number" "${sha:0:12}"
    ;;
  main)
    printf '%s-main.%s\n' "$base_version" "${sha:0:12}"
    ;;
  *) echo "mode must be pr or main" >&2; exit 2 ;;
esac
