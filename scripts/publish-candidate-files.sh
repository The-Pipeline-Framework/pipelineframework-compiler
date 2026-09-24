#!/usr/bin/env bash
set -euo pipefail

root_arg=${1:?usage: publish-candidate-files.sh CANDIDATE_FILES}
root=$(cd "$root_arg" && pwd)
repo_root=$(cd "$(dirname "$0")/.." && pwd)
repository="$root/repository"
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["candidateVersion"])' "$root/build-metadata.json")
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
resolve_repo="$temporary/maven-repository"
logs="$temporary/logs"
mkdir -p "$logs"
cat > "$temporary/minimal-pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.pipelineframework.candidate</groupId>
  <artifactId>candidate-publisher</artifactId>
  <version>1</version>
</project>
EOF

coordinates=("org.pipelineframework:pipelineframework-compiler:jar")
registry=https://maven.pkg.github.com/The-Pipeline-Framework/pipelineframework-compiler
for coordinate in "${coordinates[@]}"; do
  IFS=: read -r group_id artifact_id packaging <<< "$coordinate"
  directory="$repository/${group_id//./\/}/$artifact_id/$version"
  pom="$directory/$artifact_id-$version.pom"
  jar=""
  if [[ "$packaging" == jar ]]; then jar="$directory/$artifact_id-$version.jar"; fi
  resolved_directory="$resolve_repo/${group_id//./\/}/$artifact_id/$version"
  resolved_pom="$resolved_directory/$artifact_id-$version.pom"
  resolved_artifact=""
  if [[ "$packaging" == jar ]]; then resolved_artifact="$resolved_directory/$artifact_id-$version.jar"; fi

  # Resolve POM first, non-transitively, against only this repository. Treat only
  # Maven's explicit not-found response as absence; auth/network failures must stop.
  log="$logs/$artifact_id-pom.log"
  if ! "$repo_root/mvnw" -B -N -f "$temporary/minimal-pom.xml" \
      -Dmaven.repo.local="$resolve_repo" \
      -DremoteRepositories="github-candidates::default::$registry" \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
      "-Dartifact=$group_id:$artifact_id:$version:pom" -Dtransitive=false >"$log" 2>&1; then
    if grep -Fq "Could not find artifact $group_id:$artifact_id:pom:$version in github-candidates" "$log" \
      || grep -Fq "Could not find artifact $group_id:$artifact_id:$version:pom in github-candidates" "$log" \
      || { grep -Fq "artifacts could not be resolved: $group_id:$artifact_id:pom:$version (absent)" "$log" \
        && grep -Fq "$group_id:$artifact_id:pom:$version was not found in " "$log"; }; then
      existing=false
    else
      cat "$log" >&2
      echo "failed to resolve existing candidate POM for $group_id:$artifact_id:$version" >&2
      exit 1
    fi
  else
    existing=true
  fi

  if [[ "$existing" == true ]]; then
    if [[ "$packaging" == jar ]]; then
      log="$logs/$artifact_id-artifact.log"
      "$repo_root/mvnw" -B -N -f "$temporary/minimal-pom.xml" \
        -Dmaven.repo.local="$resolve_repo" \
        -DremoteRepositories="github-candidates::default::$registry" \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
        "-Dartifact=$group_id:$artifact_id:$version:jar" -Dtransitive=false >"$log" 2>&1 || {
          cat "$log" >&2
          echo "candidate POM exists but primary JAR could not be resolved; refusing version drift" >&2
          exit 1
        }
    fi
    python3 "$repo_root/scripts/compare-published-candidate.py" \
      "$packaging" "$pom" "$jar" "$resolved_pom" "$resolved_artifact"
    echo "identical candidate already published: $group_id:$artifact_id:$version"
    continue
  fi

  args=(-B -N -f "$temporary/minimal-pom.xml" -Dmaven.repo.local="$repo_root/.m2/repository"
    -Dmaven.deploy.skip=false -Dgpg.skip=true
    org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file
    "-Durl=$registry" -DrepositoryId=github-candidates
    "-DgroupId=$group_id" "-DartifactId=$artifact_id" "-Dversion=$version"
    "-Dpackaging=$packaging" "-DpomFile=$pom")
  if [[ "$packaging" == jar ]]; then args+=("-Dfile=$jar"); else args+=("-Dfile=$pom"); fi
  "$repo_root/mvnw" "${args[@]}"
done
