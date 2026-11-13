"""Verify strict mypy acceptance against the emitted Python bindings."""
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent


def check(filename: str, *sources: str) -> None:
    expected = {
        (filename, str(number), line.split("# expect: ", 1)[1].strip())
        for number, line in enumerate((HERE / filename).read_text().splitlines(), 1)
        if "# expect: " in line
    }
    result = subprocess.run(
        [sys.executable, "-m", "mypy", "--strict", "--show-error-codes", "--explicit-package-bases",
         "--no-incremental", filename, *sources],
        cwd=HERE, text=True, capture_output=True, timeout=60, check=False,
    )
    output = result.stdout + result.stderr
    actual = set(re.findall(r"([^\n:]+\.py):(\d+): error: .*\[([^]]+)\]", output))
    if result.returncode != (1 if expected else 0) or actual != expected:
        raise AssertionError(f"{filename}: expected {expected}, got {actual}:\n{output}")
    print(output.strip())


if __name__ == "__main__":
    check("type_positive.py", "generated")
    check("type_negative.py")
