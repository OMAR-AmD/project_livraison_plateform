"""
Tune a run-level alert rule offline: k consecutive flagged fixes.

WHY
    The shipped threshold decides per fix, but the UI badge fires on any
    single flagged fix, which flags 26 of 60 simulated honest runs (43%).
    A run-level rule (k fixes in a row) is strictly more specific and costs
    nothing to evaluate: tune k on one seed set, report on another, so the
    number is measured, not fitted.

RUN
    python ml/tune_run_rule.py
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


def collect(seed, n_fraud_each=25, n_honest=40):
    """(flags_list, labels_list, kinds_list) with shipped scores."""
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


def run_flagged(flags, k):
    """True when k flagged fixes occur consecutively."""
    run = 0
    for f in flags:
        run = run + 1 if f else 0
        if run >= k:
            return True
    return False


def evaluate(runs, k):
    fraud = [r for kind, r in runs if kind != "normal"]
    honest = [r for kind, r in runs if kind == "normal"]
    det = sum(1 for r in fraud if run_flagged(r, k))
    fp = sum(1 for r in honest if run_flagged(r, k))
    return det, len(fraud), fp, len(honest)


def main():
    print("=" * 76)
    print("Run-level rule tuning: k consecutive flagged fixes")
    print("=" * 76)
    tune = collect(111, 25, 40)
    print("\n  tune set: %d fraud runs, %d honest runs"
          % (sum(1 for k, _ in tune if k != "normal"),
             sum(1 for k, _ in tune if k == "normal")))
    for k in (1, 2, 3, 4, 5):
        det, nf, fp, nh = evaluate(tune, k)
        print(f"    k={k}: fraud caught {det}/{nf}, honest flagged {fp}/{nh}")
    # Smallest k holding honest flags near 5% on tune.
    chosen = next(k for k in (1, 2, 3, 4, 5)
                  if evaluate(tune, k)[2] / max(evaluate(tune, k)[3], 1) <= 0.05)
    print(f"\n  chosen k={chosen} (first with honest-flagged <= 5% on tune)")

    test = collect(222, 25, 40)
    det, nf, fp, nh = evaluate(test, chosen)
    print(f"  TEST SET: fraud caught {det}/{nf} ({det / nf:.0%}), "
          f"honest flagged {fp}/{nh} ({fp / nh:.0%})")
    print("\ndone.")


if __name__ == "__main__":
    main()
