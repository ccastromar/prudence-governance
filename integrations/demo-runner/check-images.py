#!/usr/bin/env python3
"""Does each runner image still resolve? Releases move the limits that split the frameworks into
two images (openai 2.x vs 3.x, mcp, pydantic), so a framework can stop fitting its image.

Asks pip to resolve each image's requirements against the live index, installs nothing, and
prints the versions that decide the split. Exits 1 if an image no longer resolves.

    cd integrations/demo-runner && python3 check-images.py
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
WATCH = ["openai", "mcp", "pydantic"]


def resolve(image: Path) -> tuple[bool, str]:
    with tempfile.TemporaryDirectory() as tmp:
        report = Path(tmp) / "report.json"
        proc = subprocess.run(
            [sys.executable, "-m", "pip", "install", "--dry-run", "--quiet", "--ignore-installed",
             "--no-cache-dir", "--report", str(report), "-r", "requirements.txt", "-r", "adapters.pip"],
            cwd=image, capture_output=True, text=True)
        if proc.returncode:
            lines = [l for l in proc.stderr.splitlines() if l.startswith("ERROR")]
            return False, "\n    ".join(lines[:3]) or proc.stderr[-400:]
        versions = {i["metadata"]["name"].lower(): i["metadata"]["version"]
                    for i in json.loads(report.read_text())["install"]}
        return True, " ".join(f"{name}={versions.get(name, '-')}" for name in WATCH)


def main() -> int:
    failed = 0
    for image in sorted(p for p in (HERE / "images").iterdir() if (p / "requirements.txt").exists()):
        ok, detail = resolve(image)
        failed += not ok
        print(f"image {image.name}: {'resolves' if ok else 'DOES NOT RESOLVE'}  {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
