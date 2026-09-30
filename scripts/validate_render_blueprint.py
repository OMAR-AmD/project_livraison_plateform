"""Validate render.yaml against Render's published JSON Schema.

A one-off verification tool, not part of CI: it needs the schema over the
network, and a grade-relevant pipeline should not become red because a schema
URL moved. What *is* in CI is check_render_blueprint.py, which is hermetic and
catches the failure the schema cannot (a dangling service reference).
"""
import json
import sys
import urllib.request

import jsonschema
import yaml

SCHEMA_URL = "https://render.com/schema/render.yaml.json"


def main() -> int:
    blueprint_path = sys.argv[1] if len(sys.argv) > 1 else "render.yaml"

    with urllib.request.urlopen(SCHEMA_URL, timeout=30) as resp:
        schema = json.load(resp)

    with open(blueprint_path, encoding="utf-8") as fh:
        blueprint = yaml.safe_load(fh)

    validator_cls = jsonschema.validators.validator_for(schema)
    validator_cls.check_schema(schema)
    errors = sorted(validator_cls(schema).iter_errors(blueprint), key=lambda e: list(e.path))

    if not errors:
        print(f"{blueprint_path}: VALID against Render schema")
        return 0

    print(f"{blueprint_path}: INVALID ({len(errors)} error(s))")
    for err in errors:
        where = "/".join(str(p) for p in err.absolute_path) or "(root)"
        print(f"  {where}: {err.message}")
    return 1


if __name__ == "__main__":
    sys.exit(main())