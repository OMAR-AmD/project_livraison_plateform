'use client';

import { useEffect, useRef, useState } from 'react';
import Modal from './Modal';

/**
 * The courier's side of the handover: scan the recipient's QR or type the six
 * digits. Camera scanning uses the built-in BarcodeDetector where the browser
 * has it (Chrome/Edge); manual entry always works, including on desktop and
 * on browsers without camera support — so the demo never depends on hardware.
 *
 * A decoded QR must name THIS delivery id, otherwise it is rejected on screen
 * without a server round trip. The server re-verifies everything anyway: the
 * client-side id check is convenience, not security.
 */
export default function ScanHandoverModal({ delivery, onConfirm, onClose }) {
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const [cameraError, setCameraError] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [digits, setDigits] = useState('');
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const canDetect = typeof window !== 'undefined' && 'BarcodeDetector' in window;

  useEffect(() => {
    let cancelled = false;
    let raf = 0;
    let detector = null;

    async function start() {
      if (!canDetect) {
        setCameraError('Camera scanning is not supported in this browser — type the 6 digits below.');
        return;
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          await videoRef.current.play();
        }
        detector = new window.BarcodeDetector({ formats: ['qr_code'] });
        setScanning(true);

        const tick = async () => {
          if (cancelled || !videoRef.current) return;
          try {
            const codes = await detector.detect(videoRef.current);
            if (codes && codes.length > 0) {
              handleScanned(codes[0].rawValue);
              return;
            }
          } catch {
            // A single failed frame must not kill the loop.
          }
          raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
      } catch {
        if (!cancelled) {
          setCameraError('Camera unavailable (permission denied or no camera) — type the 6 digits below.');
        }
      }
    }

    start();
    return () => {
      cancelled = true;
      cancelAnimationFrame(raf);
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((t) => t.stop());
        streamRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleScanned(raw) {
    let code = null;
    try {
      const parsed = JSON.parse(raw);
      if (parsed && parsed.v === 1 && parsed.id === delivery.id && typeof parsed.code === 'string') {
        code = parsed.code;
      } else {
        setError('This QR is for a different delivery.');
        return;
      }
    } catch {
      if (/^\d{6}$/.test((raw || '').trim())) {
        code = raw.trim();
      } else {
        setError('Could not read a handover code from this QR.');
        return;
      }
    }
    submit(code);
  }

  async function submit(code) {
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6 digits shown on the recipient screen.');
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await onConfirm(code);
    } catch (e) {
      // Server refusal (wrong code, not assigned, already closed) stays on
      // screen so the courier can retry the digits instead of losing the modal.
      setError(e.message || 'Could not confirm with this code.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      isOpen
      onClose={onClose}
      title="Scan handover code"
      description={delivery.description}
    >
      {canDetect && !cameraError && (
        <div className="overflow-hidden rounded-lg border border-line">
          {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
          <video ref={videoRef} className="h-56 w-full bg-black object-cover" muted playsInline />
        </div>
      )}
      {cameraError && <p className="text-xs text-content-faint">{cameraError}</p>}
      {scanning && (
        <p className="text-xs text-content-muted">Point the camera at the recipient&apos;s QR…</p>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit(digits.trim());
        }}
        className="mt-3 flex gap-2"
      >
        <input
          value={digits}
          onChange={(e) => setDigits(e.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="6 digits"
          inputMode="numeric"
          aria-label="Handover code digits"
          className="input flex-1 text-center font-mono text-xl tracking-[0.3em]"
        />
        <button type="submit" disabled={submitting || digits.length !== 6} className="btn-primary">
          Confirm
        </button>
      </form>
      {error && <p className="mt-2 text-sm text-danger-400">{error}</p>}
      <p className="mt-2 text-xs text-content-faint">
        Confirming with a valid code seals the delivery even where GPS cannot
        place you within 500 m. A wrong code is refused — it never silently
        falls back to GPS.
      </p>
    </Modal>
  );
}
