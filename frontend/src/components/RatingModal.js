'use client';

import { useState, useEffect } from 'react';
import Modal from '@/components/Modal';

const RATINGS = [
  { value: 1, label: 'Poor' },
  { value: 2, label: 'Fair' },
  { value: 3, label: 'Good' },
  { value: 4, label: 'Great' },
  { value: 5, label: 'Excellent' },
];

export default function RatingModal({ isOpen, onClose, onSubmit, deliveryId }) {
  const [rating, setRating] = useState(0);
  const [hoverRating, setHoverRating] = useState(0);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Reset whenever the dialog is re-opened for a different delivery.
  useEffect(() => {
    if (isOpen) {
      setRating(0);
      setHoverRating(0);
      setComment('');
    }
  }, [isOpen]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (rating === 0) return;
    setSubmitting(true);
    try {
      await onSubmit(deliveryId, rating, comment);
    } finally {
      setSubmitting(false);
    }
  };

  const shown = hoverRating || rating;

  return (
    <Modal isOpen={isOpen} onClose={onClose} title="Rate your delivery">
      <form onSubmit={handleSubmit} className="space-y-5">
        <div className="flex flex-col items-center py-2">
          <div
            className="flex gap-1.5"
            onMouseLeave={() => setHoverRating(0)}
            role="radiogroup"
            aria-label="Delivery rating"
          >
            {RATINGS.map(({ value }) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={rating === value}
                aria-label={`${value} star${value > 1 ? 's' : ''}`}
                onMouseEnter={() => setHoverRating(value)}
                onFocus={() => setHoverRating(value)}
                onBlur={() => setHoverRating(0)}
                onClick={() => setRating(value)}
                className="rounded p-1 transition-transform duration-150 hover:scale-110 focus-visible:scale-110"
              >
                <svg
                  className={`h-9 w-9 transition-colors ${
                    value <= shown ? 'text-warn-400' : 'text-line-strong'
                  }`}
                  fill="currentColor"
                  viewBox="0 0 20 20"
                >
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
              </button>
            ))}
          </div>
          <p className="mt-3 h-5 text-sm font-semibold text-content-soft">
            {shown > 0 ? RATINGS[shown - 1].label : 'Tap to rate'}
          </p>
        </div>

        <div>
          <label htmlFor="rating-comment" className="label">
            Comment <span className="normal-case text-content-faint">(optional)</span>
          </label>
          <textarea
            id="rating-comment"
            maxLength={1000}
            className="input h-24 resize-none"
            placeholder="What went well, and what could be better?"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          <p className="mt-1 text-right text-xs text-content-faint tabular">
            {comment.length}/1000
          </p>
        </div>

        <div className="flex flex-col-reverse gap-2 border-t border-line pt-5 sm:flex-row sm:justify-end">
          <button type="button" onClick={onClose} className="btn-secondary">
            Skip
          </button>
          <button type="submit" disabled={submitting || rating === 0} className="btn-primary">
            {submitting ? 'Submitting…' : 'Submit rating'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
