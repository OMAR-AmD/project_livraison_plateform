import localFont from 'next/font/local';
import './globals.css';
import { AuthProvider } from '@/context/AuthContext';
import { ToastProvider } from '@/components/Toast';
import ServiceWorkerRegister from '@/components/ServiceWorkerRegister';

/**
 * Fonts are self-hosted from src/app/fonts. Using next/font/google or an
 * @import of a remote stylesheet would make the running application depend on a
 * third-party service, which contradicts the platform's self-hosted premise.
 */
const geist = localFont({
  src: [{ path: './fonts/GeistVF.woff', weight: '100 900', style: 'normal' }],
  variable: '--font-geist',
  display: 'swap',
});

const geistMono = localFont({
  src: [{ path: './fonts/GeistMonoVF.woff', weight: '100 900', style: 'normal' }],
  variable: '--font-geist-mono',
  display: 'swap',
});

export const metadata = {
  title: 'SwiftDeliver — Autonomous Last-Mile Delivery',
  description:
    'Self-hosted delivery platform: AI dispatch, real-time tracking and a local RAG assistant.',
  manifest: '/manifest.webmanifest',
  appleWebApp: {
    capable: true,
    title: 'SwiftDeliver',
    statusBarStyle: 'black-translucent',
  },
};

export const viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
  themeColor: '#f97316',
};

export default function RootLayout({ children }) {
  return (
    <html lang="en" className={`${geist.variable} ${geistMono.variable}`}>
      <body className="bg-canvas text-content antialiased">
        <AuthProvider>
          <ToastProvider>{children}</ToastProvider>
        </AuthProvider>
        <ServiceWorkerRegister />
      </body>
    </html>
  );
}
