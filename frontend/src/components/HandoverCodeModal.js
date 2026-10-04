'use client';

import { useEffect, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import Modal from './Modal';
import { clientGetHandoverCode } from '@/lib/api';

/**
 * The recipient's side of the handover: shows the six-digit code as a QR and
 * as big digits the courier can type.
 *
 * The code is HMAC-derived server-side per order, issued only to the owning
 * client, and refused once the order closes — single-use by construction.
 * Honest limit, stated on screen: a forwarded photo of this code defeats it.
 * The code proves code-presence, GPS proves place; the sealed proof records
 * which factors it had.
 */
export default function HandoverCodeModal({ deliveryId, description, onClose }) {
  const [code, setCode] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    clientGetHandoverCode(deliveryId)
      .then((data) => {
        if (!cancelled) setCode(data.code);
      })
      .catch((e) => {
        if (!cancelled) setError(e.message || 'Could not load the handover code.');
      });
    return () => {
      cancelled = true;
    };
  }, [deliveryId]);

  const payload = code ? JSON.stringify({ v: 1, id: deliveryId, code }) : '';

  return (
    <Modal isOpen onClose={onClose} title="Handover code" description={description}>
      {error && <p className="text-sm text-danger-400">{error}</p>}
      {!error && !code && <p className="text-sm text-content-muted">Loading the code…</p>}
      {code && (
        <div className="flex flex-col items-center gap-4">
          <div className="rounded-xl bg-white p-4">
            <QRCodeSVG value={payload} size={200} level="M" />
          </div>
          <p className="font-mono text-4xl font-bold tracking-[0.3em] text-content" aria-label={`Handover code ${code}`}>
            {code}
          </p>
          <p className="text-center text-xs text-content-faint">
            Show this to your courier at the door. Single-use: it stops working once the
            order is delivered. A forwarded photo of this code would defeat it — hand
            it over in person.
          </p>
        </div>
      )}
    </Modal>
  );
}
