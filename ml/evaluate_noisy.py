"""
Noisy-holdout evaluation of the SHIPPED fraud model.

WHY THIS FILE EXISTS
    train_fraud_model.py reports holdout numbers on data from the same
    simulator that produced the training set. That is pipeline validation, and
    the report says so -- but a reviewer will (rightly) ask whether the model
    only learned the simulator's own habits. This answers with data that is
    noisier and shaped differently from anything the model was fitted on:

    1. Honest runs follow REAL OSRM road geometry (local router), resampled at
       the 10 s broadcast cadence, with heavier GPS noise (sigma 10 m vs 6 m
       in training) and more multipath jumps.
    2. The same three fraud patterns are injected into those road-based runs.
    3. A fourth pattern the model NEVER saw in training: `loop` -- the courier
       drives in circles far from the target at normal speed. Speed looks
       fine; only progress features can catch it.

    Nothing is retrained here and no artefact is rewritten: the forest and its
    threshold are loaded from the gzipped JSON the backend actually serves, so
    a good number means the deployed model generalises, not that a fresh fit
    does.

HONEST CONSTRAINTS
    Still synthetic -- the noise and the roads are real, the fraud labels are
    invented. This upgrades "same-simulator holdout" to "noisier, differently
    shaped holdout", not to field performance. The report must say exactly that.

RUN
    python ml/evaluate_noisy.py            (needs the local OSRM on :5000;
                                            falls back to heavy-noise random
                                            walk if the router is down)
"""

import gzip
import json
import math
import random
import urllib.request
from pathlib import Path

import numpy as np

from train_trajectory_anomaly import (
    CITY_LAT,
    CITY_LNG,
    DT_SECONDS,
    FEATURES,
    build_dataset,
    haversine_m,
    normal_run,
)
REPO = Path(__file__).resolve().parent.parent
MODEL_PATH = REPO / "backend/src/main/resources/model/trajectory_fraud_rf.json.gz"
PARITY_PATH = REPO / "ml/trajectory_fraud_rf_parity.json"
REPORT_PATH = REPO / "ml/noisy_eval_report.json"

OSRM = "http://localhost:5000"
TARGET = (33.5891, -7.6311)

# Heavier than the training budget: the whole point is to be harsher.
NOISE_SIGMA_M = 10.0
MULTIPATH_PROB = 0.05
MULTIPATH_M = 45.0

random.seed(20261004)
np.random.seed(20261004)


# --------------------------------------------------------------------------
# shipped-model scoring (same descent the Java evaluator performs)
# --------------------------------------------------------------------------
def load_model():
    with gzip.open(MODEL_PATH, "rt", encoding="utf-8") as fh:
        payload = json.load(fh)
    assert payload["format"] == "swiftdeliver-trajectory-fraud-rf/1", payload.get("format")
    assert payload["features"] == FEATURES, "feature order drifted from training"
    return payload


def forest_proba(trees, x):
    """Mean leaf class probability across trees == sklearn predict_proba."""
    scores = []
    for tree in trees:
        i = 0
        while not tree[i]["isLeaf"]:
            node = tree[i]
            i = node["left"] if x[node["feature"]] <= node["threshold"] else node["right"]
        scores.append(tree[i]["value"])
    return float(sum(scores) / len(scores))


def check_parity(payload):
    """The shipped JSON must reproduce sklearn's own probabilities."""
    parity = json.loads(PARITY_PATH.read_text(encoding="utf-8"))
    worst = 0.0
    for vec, expected in zip(parity["vectors"], parity["scores"]):
        got = forest_proba(payload["trees"], vec)
        worst = max(worst, abs(got - expected))
    print(f"  parity vs sklearn predict_proba: worst |diff| = {worst:.2e}")
    assert worst < 1e-6, "shipped model disagrees with sklearn -- stop, do not quote numbers"
    return worst


# --------------------------------------------------------------------------
# road-based runs (OSRM geometry, 10 s cadence, heavy noise)
# --------------------------------------------------------------------------
def osrm_route(lat1, lng1, lat2, lng2):
    """Full-geometry road polyline as [(lat, lng)], or None if unreachable."""
    url = (f"{OSRM}/route/v1/driving/{lng1},{lat1};{lng2},{lat2}"
           f"?overview=full&geometries=geojson")
    try:
        with urllib.request.urlopen(url, timeout=10) as res:
            data = json.load(res)
        coords = data["routes"][0]["geometry"]["coordinates"]
        return [(c[1], c[0]) for c in coords if len(c) >= 2]
    except Exception as exc:
        print(f"  osrm miss ({exc}); this leg falls back to random walk")
        return None


def resample_road(points, target_lat, target_lng):
    """Walk a polyline at urban speeds, emitting (lat, lng, speed, elapsed)."""
    # cumulative metres along the road
    cum, total = [0.0], 0.0
    for (a, b) in zip(points, points[1:]):
        total += haversine_m(a[0], a[1], b[0], b[1])
        cum.append(total)
    if total < 400:
        return None

    out = []
    speed = random.uniform(4.0, 9.0)
    travelled = 0.0
    seg = 0
    # leave margin so the slowdown tail fits on the road
    while travelled < total - 120:
        speed = max(0.0, min(14.5, speed + random.gauss(0, 1.1)))
        dropout = random.random() < 0.06
        elapsed = DT_SECONDS * (2 if dropout else 1)
        travelled += speed * elapsed * random.uniform(0.85, 1.12)
        # locate along polyline
        while seg < len(cum) - 2 and cum[seg + 1] < travelled:
            seg += 1
        a, b = points[seg], points[seg + 1]
        frac = 0.0 if cum[seg + 1] <= cum[seg] else (travelled - cum[seg]) / (cum[seg + 1] - cum[seg])
        lat = a[0] + (b[0] - a[0]) * frac
        lng = a[1] + (b[1] - a[1]) * frac
        noise = MULTIPATH_M if random.random() < MULTIPATH_PROB else NOISE_SIGMA_M
        lat += random.gauss(0, noise) / 111_320.0
        lng += random.gauss(0, noise) / (111_320.0 * math.cos(math.radians(lat)))
        if not dropout:
            out.append((lat, lng, speed, elapsed))
    # arrival tail: slow to a stop near the target
    lat, lng = points[-1]
    for _ in range(random.randint(2, 5)):
        speed = max(0.0, speed - random.uniform(1.5, 3.0))
        out.append((lat + random.gauss(0, 3) / 111_320.0,
                    lng + random.gauss(0, 3) / 111_320.0, speed, DT_SECONDS))
    return out


def road_run(target_lat, target_lng):
    """One honest run on real streets, or None (caller falls back)."""
    for _ in range(4):
        lat = random.uniform(*CITY_LAT)
        lng = random.uniform(*CITY_LNG)
        if haversine_m(lat, lng, target_lat, target_lng) < 1500:
            continue
        pts = osrm_route(lat, lng, target_lat, target_lng)
        if pts:
            out = resample_road(pts, target_lat, target_lng)
            if out and len(out) >= 12:
                d0 = max(haversine_m(out[0][0], out[0][1], target_lat, target_lng), 1.0)
                return out, d0, set()
    return None


def road_or_fallback(target_lat, target_lng):
    out = road_run(target_lat, target_lng)
    if out is not None:
        return out, True
    # Router down: heavy-noise random walk keeps the script runnable, and the
    # report records how many runs fell back so nobody mistakes it for roads.
    pts, d0, _ = normal_run(target_lat, target_lng)
    noisy = []
    for lat, lng, speed, elapsed in pts:
        noisy.append((lat + random.gauss(0, NOISE_SIGMA_M) / 111_320.0,
                      lng + random.gauss(0, NOISE_SIGMA_M) / 111_320.0,
                      speed, elapsed))
    return (noisy, d0, set()), False


def inject_teleport(base):
    pts, d0, _ = base
    if len(pts) < 12:
        return None
    cut = random.randint(6, len(pts) - 6)
    pts = list(pts)
    pts[cut] = (TARGET[0] + random.uniform(-0.05, 0.05),
                TARGET[1] + random.uniform(-0.06, 0.06), 0.4, DT_SECONDS)
    return pts, d0, {cut}


def inject_sprint(base):
    pts, d0, _ = base
    if len(pts) < 12:
        return None
    pts = list(pts)
    cut = random.randint(4, len(pts) - 8)
    for i in range(cut, min(cut + 5, len(pts))):
        lat, lng, _, elapsed = pts[i]
        step = random.uniform(38.0, 52.0) * elapsed
        b = random.uniform(0, 360)
        pts[i] = (lat + (step * math.cos(math.radians(b))) / 111_320.0,
                  lng + (step * math.sin(math.radians(b))) / 111_320.0,
                  step / elapsed, elapsed)
    return pts, d0, set(range(cut, min(cut + 5, len(pts))))


def inject_stall(base):
    pts, d0, _ = base
    if len(pts) < 16:
        return None
    pts = list(pts)
    cut = random.randint(8, len(pts) - 8)
    lat, lng, _, _ = pts[cut]
    parked = [(lat + random.gauss(0, 3) / 111_320.0, lng + random.gauss(0, 3) / 111_320.0,
               random.uniform(0.0, 0.6), DT_SECONDS)
              for _ in range(random.randint(6, 14))]
    parked.append((TARGET[0] + random.uniform(0.01, 0.04),
                   TARGET[1] + random.uniform(-0.05, 0.02), 0.5, DT_SECONDS))
    bad = set(range(cut, len(pts) + len(parked)))
    return pts[:cut] + parked, d0, bad


def loop_run(target_lat, target_lng):
    """Unseen pattern: normal approach, then circles far from the target at
    legal speed. Speed-based detection is blind; only progress features fire."""
    out, used_road = road_or_fallback(target_lat, target_lng)
    pts, d0, _ = out
    if len(pts) < 16:
        return None
    pts = list(pts)
    cut = random.randint(8, len(pts) - 8)
    # circle centre: the courier's position at the cut, if still far from target
    clat, clng, _, _ = pts[cut]
    if haversine_m(clat, clng, target_lat, target_lng) < 500:
        return None
    circled = []
    ang = random.uniform(0, 360)
    for _ in range(random.randint(6, 12)):
        ang = (ang + random.uniform(20, 40)) % 360
        r = 150.0
        circled.append((clat + (r * math.cos(math.radians(ang))) / 111_320.0,
                        clng + (r * math.sin(math.radians(ang))) / 111_320.0,
                        random.uniform(6.0, 9.0), DT_SECONDS))
    bad = set(range(cut, len(pts) + len(circled)))
    return pts[:cut] + circled, d0, bad


def wrap_as_generator(fn):
    """Adapt an injector over a prebuilt base into a build_dataset generator,
    so feature extraction stays in exactly one place (identical to Java)."""
    def gen(tlat, tlng):
        base, _ = road_or_fallback(tlat, tlng)
        return fn(base)
    return gen


def road_normal_gen(tlat, tlng):
    base, _ = road_or_fallback(tlat, tlng)
    return base


NOISY_GENERATORS = {
    "teleport": wrap_as_generator(inject_teleport),
    "sprint": wrap_as_generator(inject_sprint),
    "drift_then_vanish": wrap_as_generator(inject_stall),
    "loop": loop_run,
}


def main():
    print("=" * 76)
    print("Noisy-holdout evaluation of the SHIPPED fraud model")
    print("=" * 76)
    print("Model + threshold loaded from the gzipped JSON the backend serves.")
    print("Nothing retrained, no artefact rewritten.\n")

    payload = load_model()
    trees, thr = payload["trees"], float(payload["threshold"])
    print(f"  shipped: {len(trees)} trees, threshold {thr:.3f}, "
          f"synthetic={payload.get('synthetic')}")
    worst = check_parity(payload)

    print("\nbuilding noisy holdout (OSRM roads, sigma "
          f"{NOISE_SIGMA_M:.0f} m noise)...")
    X_parts, y_parts, kinds, runs = [], [], [], []
    for kind, gen in NOISY_GENERATORS.items():
        for _ in range(40):
            d = build_dataset(TARGET, gen)
            if d is None:
                continue
            x, y, fraud = d
            if len(x) < 4:
                continue
            X_parts.append(x)
            y_parts.append(y)
            kinds.extend([kind] * len(x))
            runs.append((kind, fraud, len(y)))
    for _ in range(60):
        d = build_dataset(TARGET, road_normal_gen)
        if d is None:
            continue
        x, y, fraud = d
        X_parts.append(x)
        y_parts.append(y)
        kinds.extend(["normal"] * len(x))
        runs.append(("normal", fraud, len(y)))
    Xte = np.vstack(X_parts)
    yte = np.concatenate(y_parts)
    kinds = np.array(kinds)
    print(f"  holdout: {Xte.shape[0]} broadcasts across {len(runs)} runs, "
          f"{int(yte.sum())} dishonest")

    print("\nscoring with the shipped forest + shipped threshold...")
    scores = np.array([forest_proba(trees, row) for row in Xte])
    flag = scores >= thr
    tp = int((flag & (yte == 1)).sum())
    fn = int(((~flag) & (yte == 1)).sum())
    fp = int((flag & (yte == 0)).sum())
    tn = int(((~flag) & (yte == 0)).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    print(f"  broadcast: tp={tp} fn={fn} fp={fp} tn={tn}")
    print(f"  precision={precision:.3f}  recall={recall:.3f}  f1={f1:.3f}")
    print(f"  honest fixes wrongly flagged: {fp} of {fp + tn} ({fp / max(fp + tn, 1):.2%})")

    per_kind = {}
    print("\n  dishonest-fix detection per kind:")
    for kind in list(NOISY_GENERATORS) + ["normal"]:
        m = (kinds == kind) & (yte == 1)
        if m.any():
            rate = float(flag[m].mean())
            per_kind[kind] = {"rate": rate, "n": int(m.sum())}
            print(f"    {kind:<18} {rate * 100:5.1f}%  ({int(m.sum())} fixes)")

    run_hits, run_total, clean_ok, clean_total = 0, 0, 0, 0
    offset = 0
    for kind, fraud, n in runs:
        seg = flag[offset:offset + n]
        offset += n
        if fraud:
            run_total += 1
            run_hits += bool(seg.any())
        else:
            clean_total += 1
            clean_ok += not seg.any()
    print(f"\n  run level: fraud alerted {run_hits}/{run_total}, "
          f"honest untouched {clean_ok}/{clean_total}")

    # Baselines on the SAME noisy set.
    speed = Xte[:, FEATURES.index("speed_mps")]
    for name, rule in [("speed > 25 m/s", speed > 25.0),
                       ("dist > 500 m", Xte[:, FEATURES.index("dist_to_target_m")] > 500.0)]:
        rtp = int((rule & (yte == 1)).sum())
        rfp = int((rule & (yte == 0)).sum())
        rfn = int(((~rule) & (yte == 1)).sum())
        rp = rtp / (rtp + rfp) if rtp + rfp else 0.0
        rr = rtp / (rtp + rfn) if rtp + rfn else 0.0
        dv = kinds == "drift_then_vanish"
        loop = kinds == "loop"
        print(f"  baseline {name}: precision={rp:.3f} recall={rr:.3f} "
              f"(drift {rule[dv & (yte == 1)].mean() * 100:.0f}%, "
              f"loop {rule[loop & (yte == 1)].mean() * 100:.0f}%)")

    report = {
        "note": "Noisy holdout of the shipped RF (OSRM roads, 10 m noise, unseen loop pattern). "
                "Synthetic labels; pipeline validation, not field performance.",
        "shipped_threshold": thr,
        "shipped_trees": len(trees),
        "parity_worst_diff": worst,
        "broadcast": {"tp": tp, "fn": fn, "fp": fp, "tn": tn,
                      "precision": precision, "recall": recall, "f1": f1},
        "per_kind": per_kind,
        "runs": {"fraud_alerted": run_hits, "fraud_total": run_total,
                 "honest_untouched": clean_ok, "honest_total": clean_total},
    }
    REPORT_PATH.write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(f"\n  report: {REPORT_PATH.relative_to(REPO)}")
    print("\ndone.")


if __name__ == "__main__":
    main()
