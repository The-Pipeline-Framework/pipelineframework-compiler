#!/usr/bin/env python3
"""Compile/launch the research producer against pinned released dependencies, never install it."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TARGET = ROOT / "target/polyglot-projection-v0"


def source_digest() -> str:
    """Hash named source files, not timestamps, generated outputs, absolute paths or a self-referential SHA."""
    digest = hashlib.sha256()
    for path in sorted([*HERE.glob("*.java"), HERE / "run.py", HERE / "dependencies.pom.xml"], key=lambda p: p.name):
        digest.update(path.name.encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def prepare(skip_resolve: bool = False) -> str:
    TARGET.mkdir(parents=True, exist_ok=True)
    classpath_file = TARGET / "classpath.txt"
    if not skip_resolve:
        subprocess.run([
            str(ROOT / "mvnw"), "-f", str(HERE / "dependencies.pom.xml"),
            "--batch-mode", "--no-transfer-progress",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
            f"-Dmaven.repo.local={ROOT / '.m2/repository'}", f"-Dmdep.outputFile={classpath_file}",
        ], cwd=ROOT, check=True)
    if not classpath_file.exists():
        raise SystemExit("Resolve dependencies once before using --skip-resolve")
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    classes = TARGET / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", classpath, "-d", str(classes),
                    *map(str, sorted(HERE.glob("*.java")))], cwd=ROOT, check=True)
    return os.pathsep.join([str(classes), classpath])


def generate(classpath: str, dsl: Path, bindings: Path, output: Path) -> subprocess.CompletedProcess[str]:
    # Stage bytes once. The producer's shape and semantic passes see the same input even if the
    # caller edits the original file. A temporary output is published only on success.
    with tempfile.TemporaryDirectory(prefix="projection-input-", dir=TARGET) as temporary:
        staged = Path(temporary)
        staged_dsl = staged / "pipeline.yaml"
        staged_bindings = staged / "native-bindings.json"
        staged_dsl.write_bytes(dsl.read_bytes())
        staged_bindings.write_bytes(bindings.read_bytes())
        result = subprocess.run(["java", "-cp", classpath, "PolyglotProjectionProbe", str(staged_dsl),
                                 str(staged_bindings), str(staged / "output"), source_digest()],
                                cwd=ROOT, text=True, capture_output=True)
        if result.returncode == 0:
            output.mkdir(parents=True, exist_ok=True)
            for artifact in sorted((staged / "output").iterdir()):
                shutil.copyfile(artifact, output / artifact.name)
            sums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
                           for path in sorted(output.iterdir()) if path.name in {"projection.json", "models.ts", "models.py"})
            (output / "SHA256SUMS").write_text(sums, encoding="utf-8")
        return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-resolve", action="store_true")
    parser.add_argument("--dsl", type=Path, default=HERE / "fixtures/pipeline.yaml")
    parser.add_argument("--bindings", type=Path, default=HERE / "fixtures/native-bindings.json")
    parser.add_argument("--output", type=Path, default=HERE / "generated")
    parser.add_argument("--scenarios", type=Path, default=HERE / "fixtures/scenarios.json",
                        help="shared corpus for the independent Java research reference")
    parser.add_argument("--projection-only", action="store_true",
                        help="generate another admitted flow without running the a,b,c-specific oracle")
    args = parser.parse_args()
    classpath = prepare(args.skip_resolve)
    result = generate(classpath, args.dsl.resolve(), args.bindings.resolve(), args.output.resolve())
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    if result.returncode == 0 and args.projection_only:
        # A previously generated baseline report must not look like evidence for a changed flow.
        for name in ("java-reference.json", "java-reference.sha256"):
            (args.output.resolve() / name).unlink(missing_ok=True)
    if result.returncode == 0 and not args.projection_only:
        reference = args.output.resolve() / "java-reference.json"
        subprocess.run(["java", "-cp", classpath, "JavaReferenceProbe", str(args.output.resolve() / "projection.json"),
                        str(args.scenarios.resolve()), str(reference)], cwd=ROOT, check=True)
        reference.with_suffix(".sha256").write_text(
            hashlib.sha256(reference.read_bytes()).hexdigest() + "  java-reference.json\n", encoding="utf-8")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
