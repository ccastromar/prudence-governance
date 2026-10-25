#!/usr/bin/env python3
"""Checks the contract's test vectors against the reference implementation.

    python -m pip install pyyaml
    python contract/governance/reference/check_vectors.py

Prints one line per failing case and exits 1 if any fails. The apps run the same vectors
against their own implementation (phase 2).
"""
from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from governance_model import ModelError, resolve, validate  # noqa: E402

VECTORS = Path(__file__).resolve().parent.parent / "vectors"


def load_model(spec: dict, base: Path) -> dict:
    if "file" in spec:
        return yaml.safe_load((base / spec["file"]).read_text(encoding="utf-8"))
    return copy.deepcopy(spec["inline"])


def merge_patch(target, patch):
    """RFC 7386 JSON Merge Patch: objects merge, null removes, anything else replaces."""
    if not isinstance(patch, dict):
        return copy.deepcopy(patch)
    result = copy.deepcopy(target) if isinstance(target, dict) else {}
    for key, value in patch.items():
        if value is None:
            result.pop(key, None)
        else:
            result[key] = merge_patch(result.get(key), value)
    return result


def check_resolution(failures: list[str]) -> int:
    data = json.loads((VECTORS / "resolution.json").read_text(encoding="utf-8"))
    models = {}
    for name, spec in data["models"].items():
        model = load_model(spec, VECTORS)
        try:
            validate(model)
        except ModelError as e:
            failures.append(f"resolution model {name!r} is invalid: {e}")
            continue
        models[name] = model
    for case in data["cases"]:
        model = models.get(case["model"])
        if model is None:
            failures.append(f"resolution {case['name']!r}: no valid model {case['model']!r}")
            continue
        got = resolve(model, case["claims"], case["app"])
        if got != case["expect"]:
            failures.append(f"resolution {case['name']!r}: expected {case['expect']}, got {got}")
    return len(data["cases"])


def check_validation(failures: list[str]) -> int:
    data = json.loads((VECTORS / "validation.json").read_text(encoding="utf-8"))
    for case in data["cases"]:
        model = load_model(case["model"], VECTORS) if "model" in case else merge_patch(data["base"], case["patch"])
        try:
            validate(model)
            got = None
        except ModelError as e:
            got = e.code
        expected = None if case.get("valid") else case["error"]
        if got != expected:
            failures.append(f"validation {case['name']!r}: expected {expected or 'valid'}, got {got or 'valid'}")
    return len(data["cases"])


def main() -> int:
    failures: list[str] = []
    total = check_resolution(failures) + check_validation(failures)
    for line in failures:
        print("FAIL", line)
    print(f"{total - len(failures)}/{total} vectors pass")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
