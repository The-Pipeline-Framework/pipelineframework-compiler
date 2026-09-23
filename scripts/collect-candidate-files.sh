#!/usr/bin/env bash
set -euo pipefail

output_dir=${1:?usage: collect-candidate-files.sh OUTPUT_DIR}
[[ ! -e "$output_dir" ]] || { echo "output directory already exists: $output_dir" >&2; exit 2; }
repo_root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$output_dir/repository"
version=$(python3 - "$repo_root/pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
print(ET.parse(sys.argv[1]).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version", ""))
PY
)
local_repository="$repo_root/.m2/repository/org/pipelineframework"
artifact_id=pipelineframework-compiler
artifact_dir="$local_repository/$artifact_id/$version"
[[ -d "$artifact_dir" ]] || { echo "candidate artifact directory is missing: $artifact_dir" >&2; exit 1; }
relative="org/pipelineframework/$artifact_id/$version"
destination="$output_dir/repository/$relative"
mkdir -p "$destination"
for extension in pom jar; do
  file="$artifact_dir/$artifact_id-$version.$extension"
  [[ -f "$file" && ! -L "$file" ]] || { echo "candidate file is missing or unsafe: $file" >&2; exit 1; }
  cp "$file" "$destination/"
done
