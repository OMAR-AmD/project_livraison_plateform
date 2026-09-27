#!/usr/bin/env bash
#
# One-shot preparation of the OSRM routing data.
#
# The compiled extract is ~1.7 GB and is deliberately NOT committed to the
# repository, so a fresh clone has no routing engine. This turns "read the
# README and remember six commands" into a single step.
#
#   ./scripts/prepare-osrm.sh              # Morocco (default)
#   ./scripts/prepare-osrm.sh portugal     # any Geofabrik region name
#   ./scripts/prepare-osrm.sh morocco -f   # rebuild from scratch
#
# It is idempotent: if osrm-data/map.osrm already exists it does nothing unless
# --force is passed.
#
# Every output is named map.* because docker-compose.yml starts the engine with
#   osrm-routed --algorithm mld /data/map.osrm
# and mounts ./osrm-data as /data. Producing <region>-latest.osrm instead would
# leave the container unable to find its data.
#
# Time: roughly 20-60 minutes depending on hardware, and several GB of disk.
# Everything runs in Docker; nothing is installed on the host.

set -euo pipefail

REGION="${1:-morocco}"
FORCE="${2:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="$ROOT/osrm-data"
PBF="$DATA_DIR/map.osm.pbf"

mkdir -p "$DATA_DIR"

if [[ "$FORCE" != "-f" && "$FORCE" != "--force" ]]; then
  if [[ -f "$DATA_DIR/map.osrm" ]]; then
    echo "osrm-data/map.osrm already exists. Nothing to do."
    echo "Re-run with './scripts/prepare-osrm.sh $REGION --force' to rebuild."
    exit 0
  fi
else
  echo "==> --force given, discarding any previous extract"
  rm -f "$DATA_DIR"/map.osrm*
fi

command -v docker >/dev/null 2>&1 || {
  echo "error: docker is required but was not found on PATH." >&2
  exit 1
}

# Git Bash on Windows needs a Windows-style path for the bind mount; native
# Linux/macOS uses the path as-is.
mount_arg() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$DATA_DIR"
  else
    echo "$DATA_DIR"
  fi
}

# Geofabrik keeps Morocco under /africa and most other regions under /europe.
# Try both rather than hard-coding one, so an arbitrary region name still works.
download() {
  for area in africa europe; do
    if curl -L --fail --progress-bar -o "$PBF" \
        "https://download.geofabrik.de/${area}/${REGION}-latest.osm.pbf"; then
      return 0
    fi
  done
  return 1
}

echo "==> Downloading the $REGION extract from Geofabrik"
if ! download; then
  echo "error: could not download '$REGION'. Check the name at" >&2
  echo "       https://download.geofabrik.de/africa/ or /europe/ and retry." >&2
  rm -f "$PBF"
  exit 1
fi

# The engine is started with --algorithm mld, so the data must be preprocessed
# with partition + customize. Skipping those and only running osrm-extract
# produces an MLD-less extract that osrm-routed will refuse to serve.
echo "==> osrm-extract (the slow part)"
docker run --rm -t \
  -v "$(mount_arg):/data" \
  osrm/osrm-backend \
  osrm-extract -p /opt/car.lua /data/map.osm.pbf

echo "==> osrm-partition (required for the multi-level Dijkstra algorithm)"
docker run --rm -t \
  -v "$(mount_arg):/data" \
  osrm/osrm-backend \
  osrm-partition /data/map.osrm

echo "==> osrm-customize"
docker run --rm -t \
  -v "$(mount_arg):/data" \
  osrm/osrm-backend \
  osrm-customize /data/map.osrm

# The raw extract is 232 MB and is already git-ignored; drop it to save space.
# The compiled .osrm files stay because the engine needs them.
rm -f "$PBF"

echo
echo "Done. Next:  docker compose up -d   (OSRM listens on http://localhost:5000)"
echo
echo "Verify with this (note the order is LONGITUDE,LATITUDE, and the distance"
echo "must be a few km - a distance of 0 means OSRM is answering but has no road"
echo "network loaded, which is the failure this check is here to catch):"
echo
echo "  curl 'http://localhost:5000/route/v1/driving/-7.5898,33.5731;-7.6311,33.5891?overview=false'"
echo
echo "  -> {\"code\":\"Ok\", ... \"distance\": 5293.2, ...}"

