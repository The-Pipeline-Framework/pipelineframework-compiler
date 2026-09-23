#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  candidate-version)
    mode=${2:?usage: system-tests.sh candidate-version pull_request|push PR_NUMBER SHA}
    pr_number=${3:--}
    sha=${4:?}
    case "$mode" in
      pull_request) mode=pr ;;
      push) mode=main ;;
      *) echo "event must be pull_request or push" >&2; exit 2 ;;
    esac
    current_version=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse("pom.xml").getroot()
print(root.findtext("{http://maven.apache.org/POM/4.0.0}version", ""))
PY
)
    if [[ "$current_version" == *-SNAPSHOT ]]; then
      base_version=${current_version%-SNAPSHOT}
    else
      base_version=${current_version%%-pr.*}
      base_version=${base_version%%-main.*}
    fi
    actual=$(bash scripts/candidate-version.sh "$mode" "$base_version" "$pr_number" "$sha")
    if [[ "$mode" == pr ]]; then
      expected="${base_version}-pr.${pr_number}.${sha:0:12}"
    elif [[ "$mode" == main ]]; then
      expected="${base_version}-main.${sha:0:12}"
    else
      exit 2
    fi
    [[ "$actual" == "$expected" ]] || exit 1
    ;;
  reactor-coordinates)
    python3 - <<'PY'
import pathlib
import xml.etree.ElementTree as ET

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root_pom = pathlib.Path("pom.xml")
expected = ET.parse(root_pom).getroot().findtext("m:version", namespaces=ns)
assert expected and ("-pr." in expected or "-main." in expected)
for pom in [root_pom, *sorted(pathlib.Path(".").glob("*/pom.xml"))]:
    root = ET.parse(pom).getroot()
    parent = root.find("m:parent", ns)
    if parent is not None:
        assert parent.findtext("m:version", namespaces=ns) == expected, pom
    if pom == root_pom:
        assert root.findtext("m:artifactId", namespaces=ns) == "pipelineframework-compiler"
        assert root.findtext("m:version", namespaces=ns) == expected
PY
    ;;
  reactor-dependencies)
    python3 - <<'PY'
import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET

candidate = "3.2.1-pr.42.0123456789ab"
with tempfile.TemporaryDirectory(prefix="tpf-reactor-dependency-test-") as temporary:
    root = pathlib.Path(temporary)
    (root / "pom.xml").write_text("""<project><groupId>org.pipelineframework</groupId><artifactId>pipelineframework-compiler</artifactId><version>3.2.1-pr.42.0123456789ab</version><properties><tpf.version>3.2.1</tpf.version></properties><dependencies><dependency><groupId>org.pipelineframework</groupId><artifactId>pipelineframework-api</artifactId><version>${tpf.version}</version></dependency></dependencies></project>""")
    subprocess.run(["python3", str(pathlib.Path("scripts/rewrite-reactor-dependencies.py").resolve()), str(root), candidate], check=True)
    pom = ET.parse(root / "pom.xml").getroot()
    assert pom.findtext("version") == candidate
    assert pom.findtext("properties/tpf.version") == "3.2.1"
    dependency = pom.find("dependencies/dependency")
    assert dependency.findtext("artifactId") == "pipelineframework-api"
    assert dependency.findtext("version") == "${tpf.version}", ET.tostring(dependency, encoding="unicode")
PY
    ;;
  *) echo "usage: system-tests.sh candidate-version pr|main PR_NUMBER SHA | reactor-coordinates | reactor-dependencies" >&2; exit 2 ;;
esac
