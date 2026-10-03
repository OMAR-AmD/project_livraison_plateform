"""Structural checks for render.yaml that the published JSON Schema cannot make.

Render's schema validates each resource in isolation. It cannot see that
`fromDatabase: {name: swiftdeliver-db}` points at a database that this file
actually declares -- the most likely real mistake in a blueprint, and the one a
deploy cycle is the only way to discover otherwise. That is what this checks.

Deliberately hermetic: no network, no extra dependency beyond PyYAML, so CI can
run it without a schema URL being reachable. The full spec check lives in
validate_render_blueprint.py and is run by hand before a release deploy.

    python scripts/check_render_blueprint.py [path/to/render.yaml]

Exits non-zero on the first class of failure found. Prints every finding.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

# Pulled from Render's blueprint spec. Kept inline rather than fetched so that a
# check which gates CI cannot go green because a URL 404s.
SERVICE_TYPES = {"web", "pserv", "worker", "cron", "keyvalue", "redis", "workflow"}
WEB_RUNTIMES = {"docker", "image", "node", "python", "ruby", "go", "elixir", "rust", "static"}
FREE_PLANS = {"free"}
DATABASE_PLANS = {"free", "0.1c-256mb", "0.5c-1g", "1c-2g"}
REGIONS = {"oregon", "ohio", "virginia", "frankfurt", "singapore"}

# The application reads these from the environment; if a blueprint stops setting
# one the service still boots and then quietly falls back to a localhost default.
# That is exactly how the four config bugs in this project were found, and the
# failure mode is silent, so it is asserted here rather than reviewed by eye.
REQUIRED_BACKEND_ENV = {
    "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD",
    "REDIS_HOST", "REDIS_PORT", "JWT_SECRET_KEY", "OSRM_BASE_URL",
}


def fail(problems: list[str], message: str) -> None:
    problems.append(message)


def main(argv: list[str]) -> int:
    path = Path(argv[1]) if len(argv) > 1 else Path("render.yaml")
    problems: list[str] = []

    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        print(f"{path}: missing")
        return 1
    except yaml.YAMLError as exc:
        print(f"{path}: not parseable YAML\n  {exc}")
        return 1

    if not isinstance(doc, dict):
        print(f"{path}: top level is not a mapping")
        return 1

    services = doc.get("services") or []
    databases = doc.get("databases") or []

    service_names = {s.get("name") for s in services if isinstance(s, dict)}
    database_names = {d.get("name") for d in databases if isinstance(d, dict)}

    # ------------------------------------------------------------ shape
    for svc in services:
        if not isinstance(svc, dict):
            continue
        name = svc.get("name", "<unnamed>")
        stype = svc.get("type")
        if stype not in SERVICE_TYPES:
            fail(problems, f"service {name}: type {stype!r} not in {sorted(SERVICE_TYPES)}")
        if stype in ("web", "pserv", "worker", "cron") and not svc.get("runtime"):
            fail(problems, f"service {name}: runtime is required for type {stype}")
        if svc.get("runtime") and svc["runtime"] not in WEB_RUNTIMES:
            fail(problems, f"service {name}: runtime {svc['runtime']!r} unknown")
        if svc.get("region") and svc["region"] not in REGIONS:
            fail(problems, f"service {name}: region {svc['region']!r} not in {sorted(REGIONS)}")
        if stype == "keyvalue":
            # Required by Render, and the allowlist is the only access control on
            # an instance that carries no password.
            allow = svc.get("ipAllowList")
            if not isinstance(allow, list):
                fail(problems, f"service {name}: keyvalue requires an ipAllowList")
            for entry in allow if isinstance(allow, list) else []:
                if not isinstance(entry, dict) or "source" not in entry:
                    fail(problems, f"service {name}: ipAllowList entry needs a 'source'")
            # YAML 1.1 turns a bare `off` into the boolean False, and Render
            # expects one of three strings. Assert the type, not the spelling.
            mode = svc.get("persistenceMode")
            if mode is not None and not isinstance(mode, str):
                fail(problems, f"service {name}: persistenceMode must be a quoted string, got {mode!r}")

        hcp = svc.get("healthCheckPath")
        if hcp is not None and not str(hcp).startswith("/"):
            fail(problems, f"service {name}: healthCheckPath must start with '/', got {hcp!r}")

    for db in databases:
        if not isinstance(db, dict):
            continue
        name = db.get("name", "<unnamed>")
        if db.get("plan") and db["plan"] not in DATABASE_PLANS:
            fail(problems, f"database {name}: plan {db['plan']!r} unknown")
        if db.get("region") and db["region"] not in REGIONS:
            fail(problems, f"database {name}: region {db['region']!r} unknown")

    # ------------------------------------------------- referential integrity
    db_refs = 0
    svc_refs = 0
    for svc in services:
        if not isinstance(svc, dict):
            continue
        name = svc.get("name", "<unnamed>")
        for var in svc.get("envVars") or []:
            if not isinstance(var, dict):
                continue
            key = var.get("key", "<unnamed var>")

            ref = var.get("fromDatabase")
            if isinstance(ref, dict):
                db_refs += 1
                target = ref.get("name")
                if target not in database_names:
                    fail(problems, f"service {name}: {key} references database "
                                   f"{target!r}, which this blueprint does not declare")

            ref = var.get("fromService")
            if isinstance(ref, dict):
                svc_refs += 1
                target = ref.get("name")
                if target not in service_names:
                    fail(problems, f"service {name}: {key} references service "
                                   f"{target!r}, which this blueprint does not declare")
                if "property" in ref and "envVarKey" in ref:
                    fail(problems, f"service {name}: {key} sets both 'property' and "
                                   f"'envVarKey'; Render accepts one or the other")

            if ("fromDatabase" in var or "fromService" in var or "fromGroup" in var) and "value" in var:
                fail(problems, f"service {name}: {key} sets both a reference and a literal value")

    # --------------------------------------------- backend environment contract
    backend = next(
        (s for s in services if isinstance(s, dict) and s.get("name") == "swiftdeliver-backend"),
        None,
    )
    if backend is None:
        fail(problems, "no service named swiftdeliver-backend")
    else:
        present = {
            v.get("key") for v in backend.get("envVars") or [] if isinstance(v, dict)
        }
        missing = REQUIRED_BACKEND_ENV - present
        if missing:
            fail(problems, f"swiftdeliver-backend is missing env vars: {sorted(missing)}")

    # ---------------------------------------------------- private-network regions
    # Render's internal URLs (a Key Value `host`, a Postgres `host`) are only
    # reachable from a service in the same region. A resource that omits
    # `region` defaults to oregon, so a backend in frankfurt silently loses its
    # cache and its database: the deploy still succeeds, login still works, and
    # only the paths that touch the cache fail at runtime. That is how the live
    # deployment 500'd on every delivery list containing an IN_TRANSIT order.
    # The JSON Schema validates each resource in isolation and cannot see this.
    region_checked = 0
    if backend is not None:
        backend_region = backend.get("region") or "oregon"
        for svc in services:
            if isinstance(svc, dict) and svc.get("type") in ("keyvalue", "redis"):
                region_checked += 1
                region = svc.get("region") or "oregon"
                if region != backend_region:
                    fail(problems, f"service {svc.get('name', '<unnamed>')}: region "
                                   f"{region!r} differs from swiftdeliver-backend "
                                   f"region {backend_region!r}; internal URLs are "
                                   f"not reachable across regions")
        for db in databases:
            if isinstance(db, dict):
                region_checked += 1
                region = db.get("region") or "oregon"
                if region != backend_region:
                    fail(problems, f"database {db.get('name', '<unnamed>')}: region "
                                   f"{region!r} differs from swiftdeliver-backend "
                                   f"region {backend_region!r}; internal URLs are "
                                   f"not reachable across regions")

    # -------------------------------------------------------- anti-vacuity
    # A referential check that finds no references has checked nothing. These
    # assertions exist so this script cannot report success while silently
    # passing because the file stopped containing the thing it inspects -- which
    # is the same failure mode as the empty-poll bug this project already hit.
    if db_refs < 1:
        fail(problems, "no fromDatabase references found -- the referential check "
                       "would be vacuous, so the blueprint is probably not the "
                       "file you think it is")
    if svc_refs < 1:
        fail(problems, "no fromService references found -- vacuous check")
    if len(databases) < 1:
        fail(problems, "no databases declared")
    if len(services) < 3:
        fail(problems, f"expected at least 3 services (cache, backend, frontend), found {len(services)}")
    if region_checked < 2:
        fail(problems, f"private-network region check inspected only {region_checked} "
                       f"resource(s); expected the keyvalue cache and the database")

    # ------------------------------------------------------------ report
    if problems:
        print(f"{path}: {len(problems)} problem(s)")
        for p in problems:
            print(f"  - {p}")
        return 1

    print(f"{path}: OK  "
          f"({len(services)} services, {len(databases)} database(s), "
          f"{db_refs} database refs, {svc_refs} service refs all resolve)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))