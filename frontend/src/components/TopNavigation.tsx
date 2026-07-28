import { FolderGit2, Settings } from 'lucide-react';
import { useRepositories } from '../hooks/useRepositories';

export function TopNavigation() {
  const {
    backendStatus,
    selectedRepository,
    navigateToDashboard,
  } = useRepositories();

  return (
    <header className="top-nav">
      <button
        className="brand-section"
        type="button"
        onClick={navigateToDashboard}
        aria-label="Codebase Manager home"
      >
        <span className="brand-section__mark" aria-hidden="true">
          <FolderGit2 size={18} />
        </span>
        <span className="brand-section__name">
          CODEBASE Manager
        </span>
      </button>

      <nav className="nav-links" aria-label="Global navigation">
        <button
          className={`nav-link ${!selectedRepository ? 'is-active' : ''}`}
          type="button"
          onClick={navigateToDashboard}
        >
          Repos
        </button>
      </nav>

      <div className="nav-utilities">
        <button className="settings-button" type="button" aria-label="Settings profile">
          <Settings aria-hidden="true" size={19} />
        </button>
        <button className="profile-button" type="button" aria-label="Profile settings">
          K
        </button>
        <span
          className={`backend-indicator backend-indicator--${backendStatus}`}
          aria-label={`Backend ${backendStatus}`}
        />
      </div>
    </header>
  );
}
