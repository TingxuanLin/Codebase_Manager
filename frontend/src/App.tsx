import { useEffect, useState } from 'react';
import './App.css';

type BackendStatus = 'checking' | 'online' | 'offline';

function App() {
  const [backendStatus, setBackendStatus] =
    useState<BackendStatus>('checking');

  useEffect(() => {
    const controller = new AbortController();

    fetch('/api/actuator/health', { signal: controller.signal })
      .then((response) => {
        setBackendStatus(response.ok ? 'online' : 'offline');
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setBackendStatus('offline');
        }
      });

    return () => controller.abort();
  }, []);

  return (
    <main className="app-shell">
      <section className="workspace">
        <div className="eyebrow">Codebase Manager</div>
        <h1>Frontend workspace is ready.</h1>
        <p>
          React, TypeScript, and Vite are configured for local development
          against the Spring Boot backend.
        </p>

        <div className="status-row" aria-live="polite">
          <span className={`status-dot status-dot--${backendStatus}`} />
          <span>
            Backend status:{' '}
            <strong>
              {backendStatus === 'checking'
                ? 'Checking'
                : backendStatus === 'online'
                  ? 'Online'
                  : 'Offline'}
            </strong>
          </span>
        </div>
      </section>
    </main>
  );
}

export default App;
