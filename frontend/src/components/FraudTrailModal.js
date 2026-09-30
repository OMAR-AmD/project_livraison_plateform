'use client';

import Modal from '@/components/Modal';

const W = 600;
const H = 140;

/**
 * The per-fix score curve for one delivery.
 *
 * Everything drawn here comes from the run that actually happened: one point
 * per position broadcast, the threshold that decided each point, and the peak.
 * There is no smoothing and no aggregation, because the shape of the curve is
 * the evidence — a model that averaged its output into a single verdict would
 * hide the difference between a one-off spike and a sustained pattern, which
 * is the difference a supervisor has to act on.
 */
function ScoreCurve({ points }) {
  if (!points || points.length === 0) return null;

  // x spans the elapsed time, not the index, so a gap between two fixes shows
  // up as a gap. A courier whose phone slept for three minutes should not look
  // like one who moved smoothly through it.
  const t0 = points[0].at;
  const span = Math.max(1, points[points.length - 1].at - t0);
  const x = (p) => ((p.at - t0) / span) * W;
  const y = (score) => H - score * H;

  const line = points.map((p) => `${x(p).toFixed(1)},${y(p.score).toFixed(1)}`).join(' ');

  // The threshold can in principle differ between fixes if the model was
  // redeployed mid-run, so it is read off the newest point and the caveat is
  // surfaced rather than assumed away.
  const threshold = points[points.length - 1].threshold;

  return (
    <div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className="h-32 w-full"
        role="img"
        aria-label={`Fraud score over ${points.length} position updates, threshold ${Math.round(threshold * 100)} percent, peak ${Math.round(Math.max(...points.map((p) => p.score)) * 100)} percent`}
      >
        {/* Threshold. Drawn under the data so it never hides a spike. */}
        <line
          x1="0"
          x2={W}
          y1={y(threshold)}
          y2={y(threshold)}
          stroke="currentColor"
          strokeWidth="1"
          strokeDasharray="5 4"
          className="text-warn-400/70"
          vectorEffect="non-scaling-stroke"
        />
        <polyline
          points={line}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.75"
          strokeLinejoin="round"
          strokeLinecap="round"
          className="text-info-400"
          vectorEffect="non-scaling-stroke"
        />
        {/* Vertical ticks rather than dots: a dot would be stretched into an
            ellipse by the non-uniform scaling that lets the curve fill its
            container at any width. */}
        {points.filter((p) => p.fraud).map((p) => (
          <line
            key={p.at}
            x1={x(p)}
            x2={x(p)}
            y1={y(p.score) - 6}
            y2={y(p.score) + 6}
            stroke="currentColor"
            strokeWidth="2.5"
            className="text-danger-400"
            vectorEffect="non-scaling-stroke"
          />
        ))}
      </svg>
      <div className="mt-1 flex justify-between text-[0.7rem] text-content-faint tabular">
        <span>0%</span>
        <span className="text-warn-400/90">
          threshold {Math.round(threshold * 100)}%
        </span>
        <span>100%</span>
      </div>
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div className="rounded-md border border-line bg-surface-raised px-3 py-2">
      <p className="text-[0.7rem] uppercase tracking-wide text-content-faint">{label}</p>
      <p className="mt-0.5 font-semibold text-content tabular">{value}</p>
    </div>
  );
}

/**
 * Opens the trajectory behind a risk tag.
 *
 * @param {object|null} trail     summary from /fraud-trails, used for the title
 * @param {object|null} fullTrail the per-fix series from /{id}/fraud-trail
 */
export default function FraudTrailModal({ isOpen, onClose, delivery, trail, fullTrail, loading, error }) {
  if (!isOpen) return null;

  const points = fullTrail?.points ?? [];
  const peak = trail ? Math.round(trail.peakScore * 100) : null;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Trajectory risk">
      <div className="space-y-5">
        {delivery && (
          <div>
            <p className="font-semibold text-content">{delivery.description}</p>
            <p className="mt-0.5 font-mono text-xs text-content-faint">
              {delivery.id.slice(0, 8)}
              {delivery.courierEmail ? ` · ${delivery.courierEmail}` : ' · unassigned'}
            </p>
          </div>
        )}

        {loading && <p className="text-sm text-content-muted">Loading the score trail…</p>}

        {!loading && error && (
          <p className="rounded-md border border-danger-500/25 bg-danger-500/10 px-3 py-2 text-sm text-danger-300">
            The score trail could not be read: {error}
          </p>
        )}

        {!loading && !error && (
          <>
            {points.length === 0 ? (
              <p className="text-sm text-content-muted">
                No position updates were scored for this delivery. The model sees
                one point per broadcast, so a delivery that was never driven has
                no curve to draw.
              </p>
            ) : (
              <>
                <ScoreCurve points={points} />

                <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                  <Stat
                    label="Peak risk"
                    value={peak != null ? `${peak}%` : '—'}
                  />
                  <Stat
                    label="Fixes scored"
                    value={points.length}
                  />
                  <Stat
                    label="Above threshold"
                    value={
                      trail
                        ? `${trail.flaggedFixes} (${trail.flaggedPercent}%)`
                        : '—'
                    }
                  />
                  <Stat
                    label="Top speed"
                    value={`${Math.max(...points.map((p) => p.speedMps ?? 0)).toFixed(1)} m/s`}
                  />
                </div>

                {trail?.flagged && (
                  <p className="rounded-md border border-danger-500/25 bg-danger-500/10 px-3 py-2 text-sm text-danger-300">
                    The model rated {trail.flaggedFixes} of {trail.totalFixes}{' '}
                    position updates above its {Math.round((fullTrail?.threshold ?? 0) * 100)}%
                    threshold. The client has been told their delivery proof is
                    under review.
                  </p>
                )}

                <p className="text-xs leading-relaxed text-content-faint">
                  The score is what a random forest trained offline on a
                  simulated city returns for this single position update, as
                  evaluated live by the service on every broadcast. It is a
                  reason to look, not proof of wrongdoing: a courier circling
                  a block for lighting looks a lot like a courier stalling, and
                  the model is deliberately reluctant to call the first one
                  fraud. Training data is synthetic — no real courier has driven
                  this platform long enough to have produced history.
                </p>
              </>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}