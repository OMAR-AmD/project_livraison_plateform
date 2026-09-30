"""
Offline training for the courier trajectory anomaly detector.

WHAT THIS IS
    An Isolation Forest over *movement features* of courier GPS broadcasts. It
    learns the shape of a normal urban delivery run from historical positions
    and flags broadcasts that do not fit. Runs here, offline. The exported model
    is evaluated live inside the Spring backend (see TrajectoryAnomalyDetector),
    so the AI is part of the running product rather than a notebook artefact.

HONEST CONSTRAINT
    We have no real courier history: nobody has driven this platform long enough
    to produce one. The training set is therefore SYNTHETIC, produced by the city
    simulator below, and this script says so in its output. Every number quoted
    in the report comes from this file and is reproducible by re-running it.

WHAT THE FEATURES ARE AND WHY
    Raw latitude/longitude are useless to a tree model: the interesting signal is
    physical, so each broadcast is reduced to movement features before training.

        speed_mps          distance since the last fix / dt
        accel_mps2         change in speed / dt
        dist_to_target_m   Haversine to the stop being worked
        progress_ratio     1 - dist_now / dist_at_assignment (should climb)
        heading_change_deg turn since the previous fix
        target_bearing_err angle between heading and the bearing to the target

    progress_ratio and target_bearing_err are what separate "driving normally"
    from "parked in a cafe while the clock runs" and from "walking the wrong way
    the whole time" -- cases a fixed distance-to-destination rule cannot see.

THE ANOMALIES INJECTED ARE DELIBERATE, NOT RANDOM
    Three kinds, each a real dispute between a courier and a client:
        teleport      the position jumps kilometres between two broadcasts
        sprint        sustained speed no vehicle in the city can hold
        drift_then_vanish  a normal-looking approach that stalls, then the
                           delivery is suddenly completed from far away

RUN
    python ml/train_trajectory_anomaly.py
"""

import json
import math
import os
import random
from pathlib import Path

import numpy as np
from sklearn.ensemble import IsolationForest

# Casablanca, roughly. Coordinates are in the same WGS-84 range the backend uses.
CITY_LAT = (33.50, 33.62)
CITY_LNG = (-7.70, -7.52)

FEATURES = [
    "speed_mps",
    "accel_mps2",
    "dist_to_target_m",
    "progress_ratio",
    "heading_change_deg",
    "target_bearing_err",
    "stall_seconds",
    "progress_rate",
]

# How far the courier must close on the target before a fix counts as progress.
# Below this, GPS jitter alone would reset the stall timer and the feature would
# never fire on a courier genuinely parked short of the destination.
PROGRESS_EPS_M = 25.0

# A courier who has made no headway for this long, while still short of the
# target, is the signature of drift_then_vanish. It has to be long enough that
# legitimate traffic lights and parked vans do not trip it.
STALL_SUSPECT_S = 90.0

# Broadcast cadence in the app: the courier's phone reports every time it moves.
DT_SECONDS = 10.0

# Where the exported artefacts go. The model is read at runtime by the backend,
# so it has to live on the Java resource path.
REPO = Path(__file__).resolve().parent.parent
MODEL_PATH = REPO / "backend/src/main/resources/model/trajectory_anomaly_iforest.json"

random.seed(20260930)
np.random.seed(20260930)


# --------------------------------------------------------------------------
# geometry
# --------------------------------------------------------------------------
def haversine_m(lat1, lng1, lat2, lng2):
    r = 6_371_000.0
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def bearing_deg(lat1, lng1, lat2, lng2):
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lng2 - lng1)
    y = math.sin(dlam) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dlam)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def angle_diff(a, b):
    return abs((a - b + 180.0) % 360.0 - 180.0)


# --------------------------------------------------------------------------
# the simulator
# --------------------------------------------------------------------------
def normal_run(target_lat, target_lng):
    """
    One honest delivery run: a courier driving from somewhere in the city to
    the target, slowing down as they arrive, with GPS noise and signal dropouts.

    Returns (points, dist_at_start, anomalous_indices). Each point carries the
    time actually elapsed since the previous fix, because a dropped signal means
    the next fix covers two intervals, not one. Computing speed with the nominal
    cadence anyway inflates honest speeds by the number of skipped intervals and
    teaches the model that noise is normal.

    The third value is the set of indices that are actually dishonest. It
    matters: a fraud run still contains honest driving for most of its length,
    and labelling the whole run anomalous would demand the model cry wolf on
    every normal fix.
    """
    lat = random.uniform(*CITY_LAT)
    lng = random.uniform(*CITY_LNG)
    dist_at_start = max(haversine_m(lat, lng, target_lat, target_lng), 1.0)

    points = []
    speed = random.uniform(4.0, 9.0)
    prev_bearing = None

    # Walk towards the target. Each step turns a little towards it, the way a
    # courier following streets does, rather than beelining.
    for _ in range(random.randint(28, 70)):
        dist = haversine_m(lat, lng, target_lat, target_lng)
        if dist <= 60.0:
            break

        want = bearing_deg(lat, lng, target_lat, target_lng)
        if prev_bearing is None:
            turn = random.uniform(-25.0, 25.0)
        else:
            turn = max(-35.0, min(35.0, angle_diff(want, prev_bearing) + random.gauss(0, 18)))
        prev_bearing = (prev_bearing or want) + turn if prev_bearing is not None else want + turn
        prev_bearing %= 360.0

        speed = max(0.0, min(14.5, speed + random.gauss(0, 0.9)))

        # signal dropout: the phone goes quiet, so the fix that follows covers
        # two intervals of elapsed time.
        dropout = random.random() < 0.06
        elapsed = DT_SECONDS * (2 if dropout else 1)
        step = speed * elapsed * random.uniform(0.82, 1.15)

        new_lat = lat + (step * math.cos(math.radians(prev_bearing))) / 111_320.0
        new_lng = lng + (step * math.sin(math.radians(prev_bearing))) / (111_320.0 * math.cos(math.radians(lat)))

        # GPS noise, a few metres, plus a rare larger jump from multipath
        noise = 6.0 if random.random() > 0.03 else 45.0
        new_lat += random.gauss(0, noise) / 111_320.0
        new_lng += random.gauss(0, noise) / (111_320.0 * math.cos(math.radians(lat)))

        if not dropout:
            points.append((new_lat, new_lng, speed, elapsed))
        lat, lng = new_lat, new_lng

    # Arrival: slow to a stop over the last few fixes.
    for _ in range(random.randint(2, 5)):
        speed = max(0.0, speed - random.uniform(1.5, 3.0))
        step = speed * DT_SECONDS
        bearing = bearing_deg(lat, lng, target_lat, target_lng)
        lat += (step * math.cos(math.radians(bearing))) / 111_320.0
        lng += (step * math.sin(math.radians(bearing))) / (111_320.0 * math.cos(math.radians(lat)))
        points.append((lat, lng, speed, DT_SECONDS))

    return points, dist_at_start, set()


def teleport_run(target_lat, target_lng):
    """A courier who marks a broadcast from across the city."""
    pts, d0, _ = normal_run(target_lat, target_lng)
    if len(pts) < 12:
        return None
    cut = random.randint(6, len(pts) - 6)
    far_lat = target_lat + random.uniform(-0.05, 0.05)
    far_lng = target_lng + random.uniform(-0.06, 0.06)
    pts = pts[:cut] + [(far_lat, far_lng, 0.4, DT_SECONDS)] + pts[cut + 1:]
    return pts, d0, {cut}


def sprint_run(target_lat, target_lng):
    """A courier whose reported speed no vehicle in the city can hold."""
    pts, d0, _ = normal_run(target_lat, target_lng)
    if len(pts) < 12:
        return None
    cut = random.randint(4, len(pts) - 8)
    n = min(cut + 5, len(pts))
    for i in range(cut, n):
        lat, lng, _, elapsed = pts[i]
        # 40 m/s is 144 km/h through a Casablanca street grid.
        step = random.uniform(38.0, 52.0) * elapsed
        b = random.uniform(0, 360)
        lat += (step * math.cos(math.radians(b))) / 111_320.0
        lng += (step * math.sin(math.radians(b))) / (111_320.0 * math.cos(math.radians(lat)))
        pts[i] = (lat, lng, step / elapsed, elapsed)
    return pts, d0, set(range(cut, n))


def drift_then_vanish_run(target_lat, target_lng):
    """
    The subtle one: a plausible approach that never arrives, then the delivery is
    completed from somewhere else entirely. A distance rule sees nothing wrong
    until the final jump; the model sees the stall building up.
    """
    pts, d0, _ = normal_run(target_lat, target_lng)
    if len(pts) < 16:
        return None
    cut = random.randint(8, len(pts) - 8)
    # Park a couple of km short and barely move.
    lat, lng, _, _ = pts[cut]
    parked = []
    for _ in range(random.randint(6, 14)):
        parked.append((lat + random.gauss(0, 3) / 111_320.0,
                       lng + random.gauss(0, 3) / (111_320.0 * math.cos(math.radians(lat))),
                       random.uniform(0.0, 0.6), DT_SECONDS))
    # then claim arrival from the far side
    parked.append((target_lat + random.uniform(0.01, 0.04), target_lng + random.uniform(-0.05, 0.02),
                   0.5, DT_SECONDS))
    bad = set(range(cut, len(pts) + len(parked)))
    return pts[:cut] + parked, d0, bad


GENERATORS = {
    "teleport": teleport_run,
    "sprint": sprint_run,
    "drift_then_vanish": drift_then_vanish_run,
}


# --------------------------------------------------------------------------
# feature extraction -- must stay identical to the Java implementation
# --------------------------------------------------------------------------
def build_dataset(target, generator):
    """
    Turn one simulated run into (rows, per_broadcast_label, is_anomalous_run).

    The label comes from the indices the generator actually falsified, NOT from
    "this run contained a fraud somewhere". Marking the honest prefix of a fraud
    run as anomalous inflates the false-positive count and hides the model
    behind a number no system would ship.
    """
    out = generator(*target)
    if out is None:
        return None

    pts, dist_at_start, bad_idx = out
    if len(pts) < 4:
        return None

    rows, labels = [], []
    prev_lat = prev_lng = None
    prev_speed = 0.0
    prev_bearing = None
    dist_start = max(dist_at_start, 1.0)

    # Stall tracking. `best_dist` is the closest the courier has ever been to
    # the target on this run; `stall_s` counts up while that does not improve
    # and resets the moment it does. A courier parked two kilometres short for
    # three minutes is invisible in speed, acceleration and heading -- every one
    # of those reads like a courier sitting at a traffic light. The elapsed time
    # without headway is the only thing that separates the two.
    best_dist = float("inf")
    stall_s = 0.0

    for i, (lat, lng, _, elapsed) in enumerate(pts):
        d_target = haversine_m(lat, lng, target[0], target[1])
        progress = 1.0 - min(d_target / dist_start, 1.0)

        if prev_lat is None:
            speed = 0.0
            accel = 0.0
            heading = bearing_deg(lat, lng, target[0], target[1])
            heading_change = 0.0
            bearing_err = 0.0
        else:
            dt = max(elapsed, 1.0)
            step = haversine_m(prev_lat, prev_lng, lat, lng)
            speed = step / dt
            accel = (speed - prev_speed) / dt
            heading = bearing_deg(prev_lat, prev_lng, lat, lng)
            heading_change = 0.0 if prev_bearing is None else angle_diff(heading, prev_bearing)
            want = bearing_deg(lat, lng, target[0], target[1])
            bearing_err = min(angle_diff(heading, want), 180.0)

        dt = max(elapsed, 1.0)
        if d_target < best_dist - PROGRESS_EPS_M:
            best_dist = min(best_dist, d_target)
            stall_s = 0.0
        else:
            stall_s += dt

        # Instantaneous rate of closing on the target, in m/s. Positive means
        # getting nearer. Distinguishes "driving somewhere" from "driving
        # towards the delivery", which raw speed cannot: a courier circling the
        # block at 30 km/h is fast and going nowhere.
        if prev_lat is None:
            progress_rate = 0.0
        else:
            prev_dist = haversine_m(prev_lat, prev_lng, target[0], target[1])
            progress_rate = (prev_dist - d_target) / dt

        rows.append([speed, accel, d_target, progress, heading_change, bearing_err,
                     stall_s, progress_rate])
        labels.append(1 if i in bad_idx else 0)

        prev_lat, prev_lng, prev_speed, prev_bearing = lat, lng, speed, heading

    return np.array(rows, dtype=float), np.array(labels), bool(bad_idx)


# --------------------------------------------------------------------------
# export
# --------------------------------------------------------------------------
def export_tree(tree):
    t = tree.tree_
    return {
        "children_left": t.children_left.tolist(),
        "children_right": t.children_right.tolist(),
        "feature": t.feature.tolist(),
        "threshold": [float(x) for x in t.threshold.tolist()],
        "n_node_samples": t.n_node_samples.tolist(),
    }


def c_factor(n):
    """sklearn's c(n): the average path length of a BST built from n points."""
    if n <= 1:
        return 0.0
    return 2.0 * (math.log(n - 1) + np.euler_gamma) - 2.0 * (n - 1) / n


def main():
    print("=" * 74)
    print("Courier trajectory anomaly detector -- offline training")
    print("=" * 74)
    print("Training data is SYNTHETIC (no real courier history exists yet).")
    print("Reported metrics are reproducible by re-running this script.\n")

    target = (33.5891, -7.6311)

    print("building training set...")
    # Trained on honest traffic ONLY. The model is unsupervised and has to spot
    # fraud it was never shown; feeding the anomalies in would be cheating.
    X_parts = []
    for _ in range(140):
        d = build_dataset(target, normal_run)
        if d is not None:
            X_parts.append(d[0])
    Xtr = np.vstack(X_parts)
    print(f"  training (honest broadcasts only): {Xtr.shape[0]}")

    Xte_parts, yte_parts, kinds, runs = [], [], [], []
    for kind, gen in GENERATORS.items():
        for _ in range(40):
            d = build_dataset(target, gen)
            if d is None:
                continue
            x, y, fraud = d
            Xte_parts.append(x)
            yte_parts.append(y)
            kinds.extend([kind] * len(x))
            runs.append((kind, fraud, len(yte_parts[-1])))
    for _ in range(60):
        d = build_dataset(target, normal_run)
        if d is None:
            continue
        x, y, fraud = d
        Xte_parts.append(x)
        yte_parts.append(y)
        kinds.extend(["normal"] * len(x))
        runs.append(("normal", fraud, len(y)))

    Xte = np.vstack(Xte_parts)
    yte = np.concatenate(yte_parts)
    kinds = np.array(kinds)
    print(f"  holdout: {Xte.shape[0]} broadcasts across {len(runs)} runs, "
          f"{int(yte.sum())} of them dishonest")

    print("\ntraining Isolation Forest...")
    model = IsolationForest(
        n_estimators=100,
        max_samples="auto",
        contamination="auto",
        max_features=len(FEATURES),
        random_state=20260930,
    )
    model.fit(Xtr)

    pred = model.predict(Xte)  # -1 == anomaly
    flagged = pred == -1

    # --- broadcast level: did the right fixes get flagged, and at what noise? ---
    tp = int((flagged & (yte == 1)).sum())
    fn = int((~flagged & (yte == 1)).sum())
    fp = int((flagged & (yte == 0)).sum())
    tn = int((~flagged & (yte == 0)).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0

    print("\nbroadcast level")
    print(f"  tp={tp} fn={fn} fp={fp} tn={tn}")
    print(f"  precision={precision:.3f}  recall={recall:.3f}  f1={f1:.3f}")
    print(f"  honest broadcasts wrongly flagged: {fp} of {fp + tn} "
          f"({fp / max(fp + tn, 1) * 100:.2f}%)")

    print("\n  detection rate per anomaly kind (share of DISHONEST fixes caught):")
    for kind in GENERATORS:
        m = (kinds == kind) & (yte == 1)
        if m.any():
            print(f"    {kind:<22} {flagged[m].mean() * 100:5.1f}%  "
                  f"({int(m.sum())} dishonest fixes)")

    # --- run level: the number an operations team actually feels ---
    print("\nrun level (did the alert fire for this run at all?)")
    offset = 0
    fraud_runs = 0
    fraud_caught = 0
    clean_runs = 0
    clean_silenced = 0
    for kind, fraud, n in runs:
        seg = flagged[offset:offset + n]
        offset += n
        if fraud:
            fraud_runs += 1
            if seg.any():
                fraud_caught += 1
        else:
            clean_runs += 1
            if not seg.any():
                clean_silenced += 1
    print(f"  fraud runs alerted:        {fraud_caught}/{fraud_runs} "
          f"({fraud_caught / max(fraud_runs, 1) * 100:.0f}%)")
    print(f"  honest runs left unflagged: {clean_silenced}/{clean_runs} "
          f"({clean_silenced / max(clean_runs, 1) * 100:.0f}%)")

    print("\nbaseline for comparison -- the fixed 500 m distance rule:")
    rule_flag = Xte[:, FEATURES.index("dist_to_target_m")] > 500.0
    r_tp = int((rule_flag & (yte == 1)).sum())
    r_fp = int((rule_flag & (yte == 0)).sum())
    r_prec = r_tp / (r_tp + r_fp) if r_tp + r_fp else 0.0
    r_rec = r_tp / (tp + fn) if tp + fn else 0.0
    print(f"  broadcast precision={r_prec:.3f} recall={r_rec:.3f}")
    print(f"  but {r_fp} of {fp + tn} honest fixes sit over 500 m from the target --")
    print("  a courier on the far side of a 12 km run trips it constantly.")

    r_offset = 0
    r_fraud_runs = r_fraud_caught = r_clean_runs = r_clean_hit = 0
    for kind, fraud, n in runs:
        seg = rule_flag[r_offset:r_offset + n]
        r_offset += n
        if fraud:
            r_fraud_runs += 1
            if seg.any():
                r_fraud_caught += 1
        else:
            r_clean_runs += 1
            if seg.any():
                r_clean_hit += 1
    print(f"  run level: fraud alerted {r_fraud_caught}/{r_fraud_runs}, "
          f"honest runs noisily flagged {r_clean_hit}/{r_clean_runs}")

    print("\nwriting model...")
    psi = int(getattr(model, "max_samples_", Xtr.shape[0]))
    payload = {
        "format": "swiftdeliver-trajectory-iforest/1",
        "trainedAt": "2026-09-30",
        "features": FEATURES,
        "dtSeconds": DT_SECONDS,
        "contamination": "auto",
        "psi": psi,
        "cFactor": c_factor(psi),
        "offset": float(model.offset_),
        "trees": [export_tree(e) for e in model.estimators_],
        "trainingBroadcasts": int(Xtr.shape[0]),
        "synthetic": True,
        "note": "Trained on synthetic city-traffic data: no real courier history exists yet.",
    }
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_text(json.dumps(payload), encoding="utf-8")
    size_kb = MODEL_PATH.stat().st_size / 1024
    print(f"  {MODEL_PATH.relative_to(REPO)}  ({size_kb:.0f} KB, {len(payload['trees'])} trees)")

    # Parity fixture: sklearn's own score for a spread of vectors, so the Java
    # implementation can be proved to agree with Python rather than assumed to.
    sample_idx = np.random.RandomState(3).choice(Xte.shape[0], 120, replace=False)
    parity = {
        "note": "score_samples from sklearn; Java TrajectoryAnomalyDetector must match within 1e-6",
        "vectors": [[float(v) for v in Xte[i]] for i in sample_idx],
        "scores": [float(model.score_samples(Xte[i:i + 1])[0]) for i in sample_idx],
    }
    parity_path = REPO / "ml/trajectory_iforest_parity.json"
    parity_path.write_text(json.dumps(parity, indent=1), encoding="utf-8")
    print(f"  {parity_path.relative_to(REPO)}  ({len(parity['vectors'])} vectors)")

    print("\ndone.")


if __name__ == "__main__":
    main()
