"""
Greedy-vs-optimised dispatch benchmark (supports the canvas Problem claim).

WHAT THIS MEASURES
    Part A (exact, one-shot): N scenarios, each courier holding open stops
    plus 1 new paid order. Every candidate round is solved exactly
    (brute-force TSP with pickup-before-dropoff), and the paired gap
    cheapest-vs-nearest is reported with a 95% confidence interval. The
    optimizer minimizes exactly the measured metric, so this sizes the gap,
    not the metric's validity.
    Part B (sequential, heuristic): 12 orders arrive one by one; each policy
    picks a courier by cheapest-insertion cost (same heuristic for all, so it
    measures balance, not optimality). Reports stop-count spread and makespan.
    Part B enforces the backend's 45-minute max-ride rule per candidate
    (infeasible couriers are skipped, as the solver drops infeasible stops).
    All policies face the same rounds, so directions hold while
    absolute seconds stay illustrative.

HONEST CONSTRAINTS
    Simulated city, off-peak, fixed seeds. Small-sample claims stay inside
    confidence intervals; a non-significant win rate is reported as such.

RUN
    python ml/benchmark_dispatch.py   (needs local OSRM on :5000)
"""

import itertools
import json
import math
import random
import urllib.request
from pathlib import Path

OSRM = "http://localhost:5000"
REPO = Path(__file__).resolve().parent.parent
REPORT_PATH = REPO / "ml/dispatch_benchmark.json"

CITY_LAT = (33.50, 33.62)
CITY_LNG = (-7.70, -7.52)


def haversine_m(lat1, lng1, lat2, lng2):
    r = 6_371_000.0
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def time_matrix(points):
    """OSRM table durations between all points [(lat, lng)]."""
    coords = ";".join(f"{lng},{lat}" for lat, lng in points)
    url = f"{OSRM}/table/v1/driving/{coords}?annotations=duration"
    with urllib.request.urlopen(url, timeout=30) as res:
        data = json.load(res)
    return data["durations"]


def best_time(sub, n_old):
    """Exact shortest round: node 0 depot, existing pairs (1,2),(3,4)...,
    new pair last. Returns inf when no feasible order exists."""
    nodes = list(range(1, n_old + 3))
    best = float("inf")
    for perm in itertools.permutations(nodes):
        order = (0,) + perm
        pos = {node: k for k, node in enumerate(order)}
        ok = all(pos[1 + i] < pos[1 + i + 1] for i in range(0, n_old, 2))
        ok = ok and pos[n_old + 1] < pos[n_old + 2]
        if not ok:
            continue
        t = sum(sub[order[k]][order[k + 1]] for k in range(len(order) - 1))
        best = min(best, t)
    return best


def clean(m, i, j):
    v = m[i][j]
    return float("inf") if v is None else v


def part_a(n_scenarios, n_couriers, n_pairs, seed):
    """One-shot exact comparison. Returns list of dicts."""
    rng = random.Random(seed)
    rows = []
    for _ in range(n_scenarios):
        couriers = []
        for _ in range(n_couriers):
            depot = (rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG))
            stops = []
            for _ in range(n_pairs):
                stops.append((rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG)))
                stops.append((rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG)))
            couriers.append((depot, stops))
        npick = (rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG))
        ndrop = (rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG))
        pts, slices = [], []
        for depot, stops in couriers:
            s = len(pts)
            pts += [depot] + stops + [npick, ndrop]
            slices.append((s, len(stops)))
        try:
            m = time_matrix(pts)
        except Exception:
            continue
        times = []
        for s, n_old in slices:
            sub = [[clean(m, s + i, s + j) for j in range(n_old + 3)]
                   for i in range(n_old + 3)]
            times.append(best_time(sub, n_old))
        if any(t == float("inf") for t in times):
            continue
        dists = [haversine_m(depot[0], depot[1], npick[0], npick[1])
                 for depot, _ in couriers]
        nearest_idx = min(range(len(dists)), key=lambda i: dists[i])
        rows.append({"times": times, "nearest_idx": nearest_idx})
    return rows


def paired_stats(diffs):
    """Mean paired gap with a 95% normal CI. diffs must be >= 0 by
    construction here (cheapest never loses its own objective)."""
    n = len(diffs)
    mean = sum(diffs) / n
    var = sum((d - mean) ** 2 for d in diffs) / max(n - 1, 1)
    half = 1.96 * math.sqrt(var / n) if n > 1 else 0.0
    return mean, max(0.0, mean - half), mean + half


def binom_ge(k, n, p):
    """P(X >= k) for Binomial(n, p): is the win count significant? p defaults
    to a coin flip but callers pass 1/n_couriers: with 5 candidates a random
    pick already wins 20% of the time, so 50% would flatter the baseline."""
    return sum(math.comb(n, i) * p ** i * (1 - p) ** (n - i) for i in range(k, n + 1))


def insertion_cost(route, pickup, drop, matrix, max_ride_s=2700.0):
    """Cheapest cost of inserting a pickup/dropoff pair into a route order.

    Mirrors the backend's 45-minute max-ride rule: a candidate whose new
    order would ride longer than max_ride_s inside the resulting route is
    skipped (inf), the same way the solver drops infeasible stops instead
    of piling everything onto one courier. Same heuristic for every policy:
    it measures balance between policies, not optimality against the solver.
    Returns (added_seconds_or_inf, new_route_or_None, ride_seconds).
    """
    best, best_route, best_ride = float("inf"), None, 0.0
    for i in range(1, len(route) + 1):
        for j in range(i + 1, len(route) + 2):
            cand = route[:i] + [pickup] + route[i:j] + [drop] + route[j:]
            ride = sum(matrix[cand[k]][cand[k + 1]]
                       for k in range(cand.index(pickup), cand.index(drop)))
            if ride > max_ride_s:
                continue
            cost = sum(matrix[cand[k]][cand[k + 1]] for k in range(len(cand) - 1))
            if cost < best:
                best, best_route, best_ride = cost, cand, ride
    if best_route is None:
        return float("inf"), None, 0.0
    base = sum(matrix[route[k]][route[k + 1]] for k in range(len(route) - 1))
    return best - base, best_route, best_ride


def part_b(n_orders, n_couriers, seed):
    """Sequential arrival: each policy picks a courier per order; report the
    stop-count spread and the makespan (longest round) at the end."""
    rng = random.Random(seed)
    depots = [(rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG)) for _ in range(n_couriers)]
    orders = [((rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG)),
               (rng.uniform(*CITY_LAT), rng.uniform(*CITY_LNG))) for _ in range(n_orders)]
    pts = depots + [p for o in orders for p in o]
    try:
        m = time_matrix(pts)
    except Exception as exc:
        print(f"  part B: OSRM miss ({exc}), skipped")
        return None
    n = len(pts)
    mat = [[clean(m, i, j) for j in range(n)] for i in range(n)]
    base = n_couriers
    out = {}
    for name in ("cheapest", "nearest", "first_fit"):
        routes = {ci: [ci] for ci in range(n_couriers)}
        unassigned = 0
        for oi in range(n_orders):
            pick, drop = base + 2 * oi, base + 2 * oi + 1
            if name == "nearest":
                # depot distance only: the "nearest-looking" rule
                ci = min(range(n_couriers),
                         key=lambda c: mat[c][pick] + mat[pick][drop])
                _, new_route, _ = insertion_cost(routes[ci], pick, drop, mat)
                if new_route is None:
                    unassigned += 1
                else:
                    routes[ci] = new_route
            elif name == "first_fit":
                ci = 0
                _, new_route, _ = insertion_cost(routes[ci], pick, drop, mat)
                if new_route is None:
                    unassigned += 1
                else:
                    routes[ci] = new_route
            else:
                options = [(insertion_cost(routes[c], pick, drop, mat)[0], c)
                           for c in range(n_couriers)]
                added, ci = min(options, key=lambda t: t[0])
                if added == float("inf"):
                    unassigned += 1
                else:
                    _, routes[ci], _ = insertion_cost(routes[ci], pick, drop, mat)
        counts = [len(routes[c]) - 1 for c in range(n_couriers)]
        totals = [sum(mat[routes[c][k]][routes[c][k + 1]] for k in range(len(routes[c]) - 1))
                  for c in range(n_couriers)]
        out[name] = {"stops": counts, "spread": max(counts) - min(counts),
                     "makespan": max(totals), "unassigned": unassigned}
    return out


def main():
    print("=" * 76)
    print("Dispatch benchmark: cheapest round vs greedy (real OSRM times)")
    print("=" * 76)

    small = part_a(200, 2, 2, seed=20261004)
    big = part_a(60, 5, 3, seed=70707)

    report = {"note": "Greedy vs cheapest-round dispatch on real OSRM times. "
                       "Part A is exact TSP; part B sequential insertion with the "
                       "backend's 45-min ride cap (balance only). "
                       "Absolute seconds illustrative."}
    for label, rows, n_cand in (("small_2x2", small, 2), ("big_5x3", big, 5)):
        diffs = [r["times"][r["nearest_idx"]] - min(r["times"]) for r in rows]
        mean, lo, hi = paired_stats(diffs)
        wins = sum(1 for r in rows if r["times"][r["nearest_idx"]] <= min(r["times"]) * 1.02)
        n = len(rows)
        p_null = 1.0 / n_cand
        print(f"\n  Part A {label}: {n} scenarios ({n_cand} candidates, null p={p_null:.2f})")
        print(f"    nearest optimal: {wins}/{n} (p={binom_ge(wins, n, p_null):.3f} vs random pick)")
        print(f"    paired gap nearest-minus-cheapest: mean {mean:.0f}s, 95% CI [{lo:.0f}, {hi:.0f}]")
        report[label] = {"scenarios": n, "candidates": n_cand,
                         "nearest_optimal": wins,
                         "binom_p_vs_random_pick": binom_ge(wins, n, p_null),
                         "paired_gap_mean_s": mean, "paired_gap_ci95": [lo, hi]}

    bal = part_b(12, 3, seed=90909)
    if bal:
        print("\n  Part B sequential (12 orders, 3 couriers, 45-min ride cap):")
        for name, r in bal.items():
            print(f"    {name:<10} stops={r['stops']} spread={r['spread']} "
                  f"makespan={r['makespan']:.0f}s unassigned={r['unassigned']}")
        report["sequential_balance"] = bal

    REPORT_PATH.write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(f"\n  report: {REPORT_PATH.relative_to(REPO)}")
    print("\ndone.")


if __name__ == "__main__":
    main()
