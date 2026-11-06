#!/usr/bin/env python3
"""Compile/launch the research producer against pinned released dependencies, never install it."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
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


def source_snapshot(source_dir: Path) -> tuple[tuple[str, bytes], ...]:
    """Capture exactly the source bytes that will be staged for compilation and identified."""
    paths = [*source_dir.glob("*.java"), source_dir / "run.py", source_dir / "dependencies.pom.xml"]
    return tuple((path.name, path.read_bytes()) for path in sorted(paths, key=lambda p: p.name))


def snapshot_digest(snapshot: tuple[tuple[str, bytes], ...]) -> str:
    """Hash names and captured bytes, not timestamps, absolute paths or a self-referential SHA."""
    digest = hashlib.sha256()
    for name, content in snapshot:
        digest.update(name.encode("utf-8") + b"\0" + content + b"\0")
    return digest.hexdigest()


@dataclass(frozen=True)
class Prepared:
    classpath: str
    generator_tree_sha256: str


def prepare(skip_resolve: bool = False, *, source_dir: Path = HERE) -> Prepared:
    TARGET.mkdir(parents=True, exist_ok=True)
    snapshot = source_snapshot(source_dir)
    # Each preparation owns both an immutable source copy and its classes. Later preparation
    # cannot overwrite a classpath still used by an earlier Prepared value.
    staged = Path(tempfile.mkdtemp(prefix="prepared-", dir=TARGET))
    for name, content in snapshot:
        (staged / name).write_bytes(content)
    classpath_file = TARGET / "classpath.txt"
    if not skip_resolve:
        subprocess.run([
            str(ROOT / "mvnw"), "-f", str(staged / "dependencies.pom.xml"),
            "--batch-mode", "--no-transfer-progress",
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
            f"-Dmaven.repo.local={ROOT / '.m2/repository'}", f"-Dmdep.outputFile={classpath_file}",
        ], cwd=ROOT, check=True)
    if not classpath_file.exists():
        raise SystemExit("Resolve dependencies once before using --skip-resolve")
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    classes = staged / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", classpath, "-d", str(classes),
                    *map(str, sorted(staged.glob("*.java")))], cwd=ROOT, check=True)
    return Prepared(os.pathsep.join([str(classes), classpath]), snapshot_digest(snapshot))


def generate(prepared: Prepared, dsl: Path, bindings: Path, output: Path) -> subprocess.CompletedProcess[str]:
    # Stage bytes once. The producer's shape and semantic passes see the same input even if the
    # caller edits the original file. A temporary output is published only on success.
    with tempfile.TemporaryDirectory(prefix="projection-input-", dir=TARGET) as temporary:
        staged = Path(temporary)
        staged_dsl = staged / "pipeline.yaml"
        staged_bindings = staged / "native-bindings.json"
        staged_dsl.write_bytes(dsl.read_bytes())
        staged_bindings.write_bytes(bindings.read_bytes())
        result = subprocess.run(["java", "-cp", prepared.classpath, "PolyglotProjectionProbe", str(staged_dsl),
                                 str(staged_bindings), str(staged / "output"), prepared.generator_tree_sha256],
                                cwd=ROOT, text=True, capture_output=True, check=False)
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
    prepared = prepare(args.skip_resolve)
    result = generate(prepared, args.dsl.resolve(), args.bindings.resolve(), args.output.resolve())
    print(result.stdout, end="")
    print(result.stderr, end="", file=sys.stderr)
    if result.returncode == 0:
        # A previously generated baseline report must not look like evidence for a changed flow.
        for name in ("java-reference.json", "java-reference.sha256"):
            (args.output.resolve() / name).unlink(missing_ok=True)
    if result.returncode == 0 and not args.projection_only:
        reference = args.output.resolve() / "java-reference.json"
        subprocess.run(["java", "-cp", prepared.classpath, "JavaReferenceProbe", str(args.output.resolve() / "projection.json"),
                        str(args.scenarios.resolve()), str(reference)], cwd=ROOT, check=True)
        reference.with_suffix(".sha256").write_text(
            hashlib.sha256(reference.read_bytes()).hexdigest() + "  java-reference.json\n", encoding="utf-8")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
