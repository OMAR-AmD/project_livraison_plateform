'use client';

import { useState, useRef, useEffect } from 'react';
import { apiFetch } from '@/lib/api';

export default function ChatWidget() {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState([
    { role: 'assistant', text: 'Hello! I am the SwiftDeliver assistant. How can I help?' }
  ]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // Escape closes the panel. Without this the only way out is the small cross,
  // which is easy to miss — and on touch there is no keyboard at all.
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e) => {
      if (e.key === 'Escape') setIsOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen]);

  const handleSend = async (e) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;

    const userMessage = input.trim();
    setInput('');
    setMessages(prev => [...prev, { role: 'user', text: userMessage }]);
    setIsLoading(true);

    try {
      const response = await apiFetch('/client/chat', {
        method: 'POST',
        body: JSON.stringify({ message: userMessage })
      });
      
      setMessages(prev => [...prev, { role: 'assistant', text: response.response }]);
    } catch (error) {
      setMessages(prev => [...prev, { role: 'assistant', text: 'Sorry, something went wrong while contacting the assistant.' }]);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <>
      {/* Launcher */}
      <button
        onClick={() => setIsOpen(true)}
        aria-label="Open the delivery assistant"
        className={`fixed bottom-6 right-6 z-40 flex h-12 w-12 items-center justify-center rounded-full bg-signal-500 p-4 text-white shadow-overlay transition-all hover:bg-signal-600 ${isOpen ? 'pointer-events-none scale-90 opacity-0' : 'scale-100 opacity-100'}`}
      >
        <svg className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={1.8} viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M8.5 10h.01M12 10h.01M15.5 10h.01M9 16H5a2 2 0 01-2-2V7a2 2 0 012-2h14a2 2 0 012 2v7a2 2 0 01-2 2h-5l-4 4v-4z"
          />
        </svg>
      </button>

      {/* Panel. `pointer-events` MUST be toggled with visibility: leaving
          `pointer-events-none` outside the conditional makes the open panel
          fully visible while every click passes straight through it. */}
      <div
        className={`fixed bottom-24 right-4 z-50 flex h-[32rem] max-h-[70vh] w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-overlay transition-all duration-200 sm:right-6 sm:w-96 ${
          isOpen ? 'pointer-events-auto translate-y-0 opacity-100' : 'pointer-events-none translate-y-3 opacity-0'
        }`}
        role="dialog"
        aria-label="Delivery assistant"
      >
        <div className="flex shrink-0 items-center justify-between border-b border-line bg-surface-raised px-4 py-3">
          <div className="flex items-center gap-2.5">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-ok-400 opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-ok-500" />
            </span>
            <div>
              <h3 className="text-sm font-bold text-content">Delivery assistant</h3>
              <p className="text-[11px] text-content-faint">Runs locally on this machine</p>
            </div>
          </div>
          <button
            onClick={() => setIsOpen(false)}
            aria-label="Close the assistant"
            className="rounded-md p-1.5 text-content-faint transition-colors hover:bg-surface-hover hover:text-content"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={1.5} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div ref={messagesEndRef} className="flex-1 space-y-3 overflow-y-auto bg-surface-sunken px-4 py-4">
          {messages.map((msg, idx) => (
            <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div
                className={`max-w-[85%] whitespace-pre-wrap break-words rounded-lg px-3.5 py-2.5 text-sm leading-relaxed ${
                  msg.role === 'user'
                    ? 'rounded-br-sm bg-signal-500 text-white'
                    : 'rounded-bl-sm border border-line bg-surface text-content-soft'
                }`}
              >
                {msg.text}
              </div>
            </div>
          ))}

          {isLoading && (
            <div className="flex justify-start">
              <div className="flex items-center gap-1.5 rounded-lg rounded-bl-sm border border-line bg-surface px-3.5 py-3">
                {[0, 0.15, 0.3].map((delay) => (
                  <span
                    key={delay}
                    className="h-1.5 w-1.5 animate-bounce rounded-full bg-content-faint"
                    style={{ animationDelay: `${delay}s` }}
                  />
                ))}
              </div>
            </div>
          )}
        </div>

        {messages.length === 1 && !isLoading && (
          <div className="flex shrink-0 flex-wrap gap-2 border-t border-line bg-surface px-4 py-3">
            {[
              'Where is my order?',
              'What is the delay policy?',
              'Cancel my latest order.',
            ].map((prompt) => (
              <button
                key={prompt}
                onClick={() => setInput(prompt)}
                className={`rounded-md border px-2.5 py-1.5 text-xs font-medium transition-colors ${
                  prompt.startsWith('Cancel')
                    ? 'border-danger-500/25 bg-danger-500/10 text-danger-400 hover:bg-danger-500/20'
                    : 'border-line bg-surface-raised text-content-muted hover:border-line-strong hover:text-content'
                }`}
              >
                {prompt}
              </button>
            ))}
          </div>
        )}

        <form onSubmit={handleSend} className="shrink-0 border-t border-line bg-surface p-3">
          <div className="flex items-center gap-2">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Ask about your delivery…"
              aria-label="Message the assistant"
              className="input flex-1"
              disabled={isLoading}
            />
            <button
              type="submit"
              disabled={isLoading || !input.trim()}
              aria-label="Send message"
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-signal-500 text-white transition-colors hover:bg-signal-600 disabled:pointer-events-none disabled:opacity-40"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12h15m0 0l-6-6m6 6l-6 6" />
              </svg>
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
