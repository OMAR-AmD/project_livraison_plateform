"""
Measure the OR clause: k=3 consecutive OR one fix above a high threshold.

WHY
    k=3 alone drops teleport recall to 28% (teleport fires a single fix).
    The clause recovers it, but every honest run with one hot fix becomes an
    alert too -- so the threshold is tuned on one seed set and everything is
    reported on disjoint sets, like the k=3 rule itself.

RUN
    python ml/measure_or_clause.py
"""

import numpy as np

from audit_k3 import run_flagged
from evaluate_noisy import NOISY_GENERATORS


def or_flagged(flags, k, t, scores):
    """k consecutive flagged fixes, or any single fix scoring >= t."""
    run = 0
    for f, s in zip(flags, scores):
        run = run + 1 if f else 0
        if run >= k or s >= t:
            return True
    return False


def main():
    print("=" * 76)
    print("OR-clause audit: k=3 OR single fix >= T")
    print("=" * 76)
    # Honest-fix score ceiling on the TUNE set decides T, nothing else.
    # Rescored below (collect() keeps flags only); imports live at module top.
    from evaluate_noisy import TARGET, forest_proba, load_model, road_normal_gen
    from train_trajectory_anomaly import build_dataset
    import random

    payload = load_model()
    trees, thr = payload["trees"], float(payload["threshold"])

    def scored_runs(seed, n_fraud_each, n_honest):
        random.seed(seed)
        np.random.seed(seed)
        out = []
        for kind, gen in NOISY_GENERATORS.items():
            for _ in range(n_fraud_each):
                d = build_dataset(TARGET, gen)
                if d is None:
                    continue
                x, y, _ = d
                if len(x) < 4:
                    continue
                sc = [forest_proba(trees, r) for r in x]
                out.append((kind, [s >= thr for s in sc], sc))
        for _ in range(n_honest):
            d = build_dataset(TARGET, road_normal_gen)
            if d is None:
                continue
            x, y, _ = d
            sc = [forest_proba(trees, r) for r in x]
            out.append(("normal", [s >= thr for s in sc], sc))
        return out

    tune2 = scored_runs(111, 25, 40)
    honest_top = max((s for kind, f, sc in tune2 if kind == "normal" for s in sc), default=0.0)
    print(f"\n  tune: hottest honest fix scores {honest_top:.4f}")
    for t in (0.95, 0.97, 0.99):
        n = sum(1 for kind, f, sc in tune2 if kind == "normal" and any(s >= t for s in sc))
        print(f"    T={t}: tune honest runs with a fix >= T: {n}")

    T = 0.97
    print(f"\n  reporting with T={T} (test seeds, then 400 fresh honest runs):")
    test = scored_runs(222, 25, 40)
    print("  test seeds, per pattern (combined vs k=3-only):")
    for kind in list(NOISY_GENERATORS):
        items = [(f, sc) for k, f, sc in test if k == kind]
        hit = sum(1 for f, sc in items if or_flagged(f, 3, T, sc))
        k3 = sum(1 for f, sc in items if run_flagged(f, 3))
        print(f"    {kind:<18} combined {hit}/{len(items)} ({hit / len(items):.0%})"
              f"   k=3-only {k3}/{len(items)}")
    fraud = [(f, sc) for k, f, sc in test if k != "normal"]
    print(f"    {'ALL FRAUD':<18} combined "
          f"{sum(1 for f, sc in fraud if or_flagged(f, 3, T, sc))}/{len(fraud)}")
    print("  big honest set, new seeds (400 road runs):")
    random.seed(777)
    np.random.seed(777)
    fp = n = 0
    for _ in range(400):
        d = build_dataset(TARGET, road_normal_gen)
        if d is None:
            continue
        x, y, _ = d
        sc = [forest_proba(trees, r) for r in x]
        n += 1
        fp += 1 if or_flagged([s >= thr for s in sc], 3, T, sc) else 0
    print(f"    honest flagged under combined rule: {fp}/{n} ({fp / n:.2%})")
    print("\ndone.")


if __name__ == "__main__":
    main()
