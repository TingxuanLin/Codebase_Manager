import { type FormEvent, useEffect, useState } from 'react';
import './App.css';

type BackendStatus = 'checking' | 'online' | 'offline';

type ScanResult = {
  repositoryId: number;
  branchId: number;
  scanRunId: number;
  name: string;
  branch: string;
  headCommitSha: string;
  fileCount: number;
  classCount: number;
  methodCount: number;
};

type ParseSource = 'local' | 'github';

// Renders the repository parser UI and coordinates requests to the backend.
function App() {
  const [backendStatus, setBackendStatus] =
    useState<BackendStatus>('checking');
  const [repoPath, setRepoPath] = useState('');
  const [parseSource, setParseSource] = useState<ParseSource>('local');
  const [githubUrl, setGithubUrl] = useState('');
  const [githubBranch, setGithubBranch] = useState('');
  const [repoName, setRepoName] = useState('');
  const [repoUrl, setRepoUrl] = useState('');
  const [isParsing, setIsParsing] = useState(false);
  const [error, setError] = useState('');
  const [scanResult, setScanResult] = useState<ScanResult | null>(null);

  useEffect(() => {
    // Checks backend health once so the submit button only enables when API is reachable.
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

  // Builds the right local or GitHub parse request and displays the stored scan result.
  async function parseRepository(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setIsParsing(true);
    setError('');
    setScanResult(null);

    try {
      const endpoint =
        parseSource === 'local'
          ? '/api/repositories/parse-local'
          : '/api/repositories/parse-github';
      const body =
        parseSource === 'local'
          ? {
              path: repoPath,
              name: repoName || undefined,
              url: repoUrl || undefined,
            }
          : {
              url: githubUrl,
              branch: githubBranch || undefined,
              name: repoName || undefined,
            };

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!response.ok) {
        const message = await response.text();
        throw new Error(message || `Request failed with ${response.status}`);
      }

      const data = (await response.json()) as ScanResult;
      setScanResult(data);
    } catch (parseError) {
      setError(
        parseError instanceof Error
          ? parseError.message
          : 'Unable to parse repository',
      );
    } finally {
      setIsParsing(false);
    }
  }

  return (
    <main className="app-shell">
      <section className="workspace" aria-labelledby="page-title">
        <div className="workspace-header">
          <div>
            <div className="eyebrow">Codebase Manager</div>
            <h1 id="page-title">Repository parser</h1>
          </div>

          <div className="status-row" aria-live="polite">
            <span className={`status-dot status-dot--${backendStatus}`} />
            <span>
              Backend:{' '}
              <strong>
                {backendStatus === 'checking'
                  ? 'Checking'
                  : backendStatus === 'online'
                    ? 'Online'
                    : 'Offline'}
              </strong>
            </span>
          </div>
        </div>

        <form className="parse-form" onSubmit={parseRepository}>
          <div className="segmented-control" aria-label="Parse source">
            <button
              className={parseSource === 'local' ? 'is-active' : ''}
              type="button"
              onClick={() => setParseSource('local')}
            >
              Local
            </button>
            <button
              className={parseSource === 'github' ? 'is-active' : ''}
              type="button"
              onClick={() => setParseSource('github')}
            >
              GitHub
            </button>
          </div>

          {parseSource === 'local' ? (
            <label>
              <span>Repository path</span>
              <input
                value={repoPath}
                onChange={(event) => setRepoPath(event.target.value)}
                placeholder="/Users/name/project"
                required
              />
            </label>
          ) : (
            <div className="field-grid field-grid--wide">
              <label>
                <span>GitHub URL</span>
                <input
                  value={githubUrl}
                  onChange={(event) => setGithubUrl(event.target.value)}
                  placeholder="https://github.com/org/repo"
                  required
                />
              </label>

              <label>
                <span>Branch</span>
                <input
                  value={githubBranch}
                  onChange={(event) => setGithubBranch(event.target.value)}
                  placeholder="Default branch"
                />
              </label>
            </div>
          )}

          <div className="field-grid">
            <label>
              <span>Name</span>
              <input
                value={repoName}
                onChange={(event) => setRepoName(event.target.value)}
                placeholder="Derived from folder"
              />
            </label>

            {parseSource === 'local' && (
              <label>
                <span>URL</span>
                <input
                  value={repoUrl}
                  onChange={(event) => setRepoUrl(event.target.value)}
                  placeholder="Derived from origin"
                />
              </label>
            )}
          </div>

          <button type="submit" disabled={isParsing || backendStatus !== 'online'}>
            {isParsing ? 'Parsing...' : 'Parse and store'}
          </button>
        </form>

        {error && <pre className="message message--error">{error}</pre>}

        {scanResult && (
          <section className="results" aria-label="Scan result">
            <div>
              <span>Repository</span>
              <strong>{scanResult.name}</strong>
            </div>
            <div>
              <span>Branch</span>
              <strong>{scanResult.branch}</strong>
            </div>
            <div>
              <span>Head commit</span>
              <strong>{scanResult.headCommitSha.slice(0, 12)}</strong>
            </div>
            <div>
              <span>Repository ID</span>
              <strong>{scanResult.repositoryId}</strong>
            </div>
            <div>
              <span>Branch ID</span>
              <strong>{scanResult.branchId}</strong>
            </div>
            <div>
              <span>Scan run ID</span>
              <strong>{scanResult.scanRunId}</strong>
            </div>
            <div>
              <span>Files</span>
              <strong>{scanResult.fileCount}</strong>
            </div>
            <div>
              <span>Classes</span>
              <strong>{scanResult.classCount}</strong>
            </div>
            <div>
              <span>Methods</span>
              <strong>{scanResult.methodCount}</strong>
            </div>
          </section>
        )}

      </section>
    </main>
  );
}

export default App;
