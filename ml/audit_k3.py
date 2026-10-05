"""
k=3 audit: per-pattern recall vs any-flag, plus a large honest set.

WHY
    The k=3 paragraph mixed numbers from two rules (0/40 honest with 51%
    recall under k=3, but 3.7% precision computed under any-flag). This
    reports, on one held-out seed set: per-pattern run recall for k=3 and
    for any-flag side by side, then sizes a large honest-only set for the
    false-alarm bound the v2 target needs.

RUN
    python ml/audit_k3.py
"""

import numpy as np

from evaluate_noisy import (
    NOISY_GENERATORS,
    TARGET,
    forest_proba,
    load_model,
    road_normal_gen,
)
from train_trajectory_anomaly import build_dataset

import random


def run_flagged(flags, k):
    run = 0
    for f in flags:
        run = run + 1 if f else 0
        if run >= k:
            return True
    return False


def collect(seed, n_fraud_each, n_honest):
    random.seed(seed)
    np.random.seed(seed)
    payload = load_model()
    trees, thr = payload["trees"], float(payload["threshold"])
    runs = []
    for kind, gen in NOISY_GENERATORS.items():
        for _ in range(n_fraud_each):
            d = build_dataset(TARGET, gen)
            if d is None:
                continue
            x, y, _ = d
            if len(x) < 4:
                continue
            runs.append((kind, (np.array([forest_proba(trees, r) for r in x]) >= thr).tolist()))
    for _ in range(n_honest):
        d = build_dataset(TARGET, road_normal_gen)
        if d is None:
            continue
        x, y, _ = d
        runs.append(("normal", (np.array([forest_proba(trees, r) for r in x]) >= thr).tolist()))
    return runs


def main():
    print("=" * 76)
    print("k=3 audit: per-pattern recall and a large honest set")
    print("=" * 76)
    test = collect(222, 25, 40)
    print("\n  per-pattern run recall (test seeds, k=3 vs any-flag):")
    for kind in list(NOISY_GENERATORS):
        segs = [r for k, r in test if k == kind]
        k3 = sum(1 for r in segs if run_flagged(r, 3))
        af = sum(1 for r in segs if any(r))
        print(f"    {kind:<18} k=3 {k3}/{len(segs)} ({k3 / len(segs):.0%})"
              f"   any-flag {af}/{len(segs)} ({af / len(segs):.0%})")
    fraud = [r for k, r in test if k != "normal"]
    print(f"    {'ALL FRAUD':<18} k=3 {sum(1 for r in fraud if run_flagged(r, 3))}/{len(fraud)}"
          f"   any-flag {sum(1 for r in fraud if any(r))}/{len(fraud)}")

    print("\n  large honest set (new seeds, road-based, k=3):")
    random.seed(777)
    np.random.seed(777)
    payload = load_model()
    trees, thr = payload["trees"], float(payload["threshold"])
    fp = n = 0
    for i in range(400):
        d = build_dataset(TARGET, road_normal_gen)
        if d is None:
            continue
        x, y, _ = d
        flags = (np.array([forest_proba(trees, r) for r in x]) >= thr).tolist()
        n += 1
        fp += 1 if run_flagged(flags, 3) else 0
        if (i + 1) % 100 == 0:
            print(f"    ...{i + 1} runs, flagged so far: {fp}")
    print(f"  honest flagged under k=3: {fp}/{n} ({fp / n:.2%})")
    print("\ndone.")


if __name__ == "__main__":
    main()
