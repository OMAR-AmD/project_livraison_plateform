'use client';

/**
 * Read-only star display for a 1-5 rating.
 *
 * Shared by the courier's own stats card and the admin user list so a rating
 * looks identical wherever it is shown.
 *
 * The important distinction this makes explicit: `value === null` means "nothing
 * has been rated yet" and is labelled as such. Rendering that as 0.0 or as five
 * grey stars would read as a bad score rather than as no data — which matters
 * because a brand-new courier legitimately has none.
 */
const SIZES = {
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-5 w-5',
};

export default function StarRating({ value, count, size = 'md', className = '' }) {
  const starClass = SIZES[size] || SIZES.md;

  if (value == null || Number.isNaN(value)) {
    return (
      <span className={`text-content-faint ${className}`}>
        {count > 0 ? 'No ratings yet' : 'Not rated'}
      </span>
    );
  }

  const rounded = Math.round(value);
  const star = (filled) => (
    <svg
      className={`${starClass} shrink-0 ${filled ? 'text-warn-400' : 'text-line-strong'}`}
      fill="currentColor"
      viewBox="0 0 20 20"
      aria-hidden="true"
    >
      <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
    </svg>
  );

  return (
    <span
      className={`inline-flex items-center gap-1 ${className}`}
      role="img"
      aria-label={`Rated ${value.toFixed(1)} out of 5${
        count > 0 ? `, from ${count} rating${count > 1 ? 's' : ''}` : ''
      }`}
    >
      <span className="inline-flex items-center gap-0.5">
        {[1, 2, 3, 4, 5].map((i) => star(i <= rounded))}
      </span>
      <span className="font-semibold text-content tabular">{value.toFixed(1)}</span>
      {count > 0 && (
        <span className="text-content-faint tabular">
          ({count} review{count > 1 ? 's' : ''})
        </span>
      )}
    </span>
  );
}
