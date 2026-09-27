import { useState, useEffect } from 'react';
import Modal from './Modal';

/**
 * Simulated card checkout.
 *
 * `amount` is authoritative and comes from the server's quote (see
 * `clientQuoteDelivery`) — it is never computed in the browser. `quote` carries the
 * distance and duration breakdown so the customer can see what they are paying for.
 */
export default function PaymentModal({ isOpen, onClose, onSuccess, amount, quote }) {
  const [cardNumber, setCardNumber] = useState('');
  const [expiry, setExpiry] = useState('');
  const [cvc, setCvc] = useState('');
  const [name, setName] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [error, setError] = useState('');

  // Reset form when opened
  useEffect(() => {
    if (isOpen) {
      setCardNumber('');
      setExpiry('');
      setCvc('');
      setName('');
      setError('');
      setIsProcessing(false);
    }
  }, [isOpen]);

  const handleCardFormat = (e) => {
    let val = e.target.value.replace(/\D/g, '');
    let formatted = val.match(/.{1,4}/g)?.join(' ') || val;
    setCardNumber(formatted.substring(0, 19));
  };

  const handleExpiryFormat = (e) => {
    let val = e.target.value.replace(/\D/g, '');
    if (val.length >= 3) {
      val = val.substring(0, 2) + '/' + val.substring(2, 4);
    }
    setExpiry(val.substring(0, 5));
  };

  const handlePay = async (e) => {
    e.preventDefault();
    const validateLuhn = (num) => {
      let arr = (num + '').split('').reverse().map(x => parseInt(x, 10));
      let lastDigit = arr.splice(0, 1)[0];
      let sum = arr.reduce((acc, val, i) => (i % 2 !== 0 ? acc + val : acc + ((val * 2) % 9) || 9), 0);
      sum += lastDigit;
      return sum % 10 === 0;
    };

    const isFutureExpiry = (exp) => {
      const [month, year] = exp.split('/');
      if (parseInt(month, 10) < 1 || parseInt(month, 10) > 12) return false;
      const now = new Date();
      const currentYear = parseInt(now.getFullYear().toString().substr(-2), 10);
      const currentMonth = now.getMonth() + 1;
      return parseInt(year, 10) > currentYear || (parseInt(year, 10) === currentYear && parseInt(month, 10) >= currentMonth);
    };

    const cleanCard = cardNumber.replace(/\D/g, '');

    if (name.trim().length < 3) {
      setError('Please enter a valid name.');
      return;
    }
    if (cleanCard.length < 16 || cleanCard === '0000000000000000' || !validateLuhn(cleanCard)) {
      setError('Please enter a valid card number.');
      return;
    }
    if (expiry.length < 5 || !isFutureExpiry(expiry)) {
      setError('Please enter a valid expiry date (MM/YY) in the future.');
      return;
    }
    if (cvc.length < 3) {
      setError('Please enter a valid 3 or 4 digit CVC.');
      return;
    }

    setError('');
    setIsProcessing(true);

    // Simulate network delay and bank verification
    setTimeout(() => {
      setIsProcessing(false);
      onSuccess();
    }, 2500);
  };

  return (
    <Modal isOpen={isOpen} onClose={!isProcessing ? onClose : undefined} title="Secure Checkout">
      <div className="space-y-6">
        <div className="bg-info-500/10 border border-info-500/25 rounded-xl p-4 flex justify-between items-center">
          <div>
            <p className="text-sm text-info-300">Total to pay</p>
            <p className="text-2xl font-bold text-content">{amount.toFixed(2)} MAD</p>
            {quote && (
              <p className="text-xs text-info-300/70 mt-1">
                {quote.distanceKm} km · ~{Math.round(quote.durationSeconds / 60)} min
                {quote.congestionFactor > 1 && ' (rush hour)'}
              </p>
            )}
          </div>
          <div className="bg-info-500/20 p-3 rounded-full">
            <svg xmlns="http://www.w3.org/2000/svg" className="h-6 w-6 text-info-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
            </svg>
          </div>
        </div>

        <form onSubmit={handlePay} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-content-soft mb-1">Name on Card</label>
            <input 
              type="text" 
              required 
              className="input" 
              placeholder="John Doe"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isProcessing}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-content-soft mb-1">Card Number</label>
            <div className="relative">
              <input 
                type="text" 
                required 
                className="input pl-10 tracking-widest" 
                placeholder="0000 0000 0000 0000"
                value={cardNumber}
                onChange={handleCardFormat}
                disabled={isProcessing}
              />
              <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5 absolute left-3 top-2.5 text-content-muted" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
              </svg>
            </div>
          </div>

          <div className="flex gap-4">
            <div className="flex-1">
              <label className="block text-sm font-medium text-content-soft mb-1">Expiry Date</label>
              <input 
                type="text" 
                required 
                className="input" 
                placeholder="MM/YY"
                value={expiry}
                onChange={handleExpiryFormat}
                disabled={isProcessing}
              />
            </div>
            <div className="flex-1">
              <label className="block text-sm font-medium text-content-soft mb-1">CVC</label>
              <input 
                type="password" 
                required 
                maxLength="4"
                className="input" 
                placeholder="123"
                value={cvc}
                onChange={(e) => setCvc(e.target.value.replace(/\D/g, ''))}
                disabled={isProcessing}
              />
            </div>
          </div>

          {error && <p className="text-sm text-danger-400 mt-2">{error}</p>}

          <div className="pt-6 border-t border-line flex gap-3">
            <button 
              type="button" 
              onClick={onClose} 
              className="btn-secondary w-full"
              disabled={isProcessing}
            >
              Cancel
            </button>
            <button 
              type="submit" 
              className="btn-primary w-full flex justify-center items-center gap-2"
              disabled={isProcessing}
            >
              {isProcessing ? (
                <>
                  <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-content" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                  Processing...
                </>
              ) : (
                `Pay ${amount.toFixed(2)} MAD`
              )}
            </button>
          </div>
        </form>
        
        <div className="flex items-center justify-center gap-2 opacity-50">
          <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
            <path fillRule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clipRule="evenodd" />
          </svg>
          <span className="text-xs">Payments are secure and encrypted</span>
        </div>
      </div>
    </Modal>
  );
}
