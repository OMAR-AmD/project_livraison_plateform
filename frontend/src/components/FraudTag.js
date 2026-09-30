'use client';

/**
 * The risk tag the dispatcher sees next to a delivery's status.
 *
 * Three states, and the distinction between them is the whole point:
 *
 *   no trail   → nothing rendered. The model has not seen this delivery, so
 *                there is nothing to claim about it. Showing a neutral "OK"
 *                here would assert a measurement that was never made.
 *   clean      → "AI ok · peak 12%". Visible on purpose. If only alerts were
 *                shown, an admin would have no way to tell a fleet the model
 *                watches from one it ignores.
 *   flagged    → "AI alert · peak 97%". Peak rather than latest, because a
 *                courier who resumes driving normally must not erase the fact
 *                that the model fired.
 *
 * The percentage shown is the model's own output. It is not a confidence
 * interval, not a probability that this courier is guilty, and the copy in
 * the modal says so — a dispatcher acting on this needs to know what it means.
 */
export default function FraudTag({ trail, onOpen }) {
  if (!trail) return null;

  const peak = Math.round(trail.peakScore * 100);

  if (trail.flagged) {
    return (
      <button
        type="button"
        onClick={onOpen}
        title={`Trajectory model flagged ${trail.flaggedFixes} of ${trail.totalFixes} position updates. Open the score trail.`}
        aria-label={`Trajectory model flagged this delivery, peak risk ${peak} percent. Open the score trail.`}
        className="status status-cancelled cursor-pointer whitespace-nowrap hover:brightness-125"
      >
        AI alert · {peak}%
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      title={`Trajectory model scored ${trail.totalFixes} position updates, none above threshold. Open the score trail.`}
      aria-label={`Trajectory model scored this delivery, peak risk ${peak} percent, no alert. Open the score trail.`}
      className="whitespace-nowrap text-xs text-content-faint tabular hover:text-content-soft"
    >
      AI ok · peak {peak}%
    </button>
  );
}