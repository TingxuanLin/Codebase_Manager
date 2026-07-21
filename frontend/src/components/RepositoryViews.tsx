import { useMemo, useState } from 'react';
import {
  Activity,
  ArrowRightLeft,
  Bot,
  Clock,
  FileCode2,
  FolderGit2,
  GitBranch,
  GitPullRequest,
  LoaderCircle,
  Play,
  Search,
  Send,
  ShieldAlert,
  Sparkles,
  Trash2,
} from 'lucide-react';
import {
  deleteRepository,
  rescanRepository,
} from '../api/repositories';
import { normalizeScanStatus } from '../utils/scanStatus';
import { useRepositories } from '../hooks/useRepositories';
import type { RepositorySummary } from '../types/repository';
import { Breadcrumbs } from './Breadcrumbs';
import { DismissibleAlert } from './DismissibleAlert';
import { PullRequestsTab } from './PullRequestsTab';
import { RepositoryStats } from './RepositoryStats';
import { RepositoryToolbar } from './RepositoryToolbar';
import { StatusBadge } from './StatusBadge';

const formatDate = (value?: string | null) => {
  if (!value) {
    return 'Not scanned';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
};

const formatCount = (value?: number | null) =>
  typeof value === 'number' ? value.toLocaleString() : '0';

function RepositoryDashboard() {
  const {
    repositories,
    isLoadingRepositories,
    repositoryError,
    setRepositoryError,
    selectRepository,
    setIsImportOpen,
    refreshRepositories,
  } = useRepositories();
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [scanningRepositoryId, setScanningRepositoryId] = useState<number | null>(
    null,
  );
  const [actionError, setActionError] = useState('');

  async function handleScan(repository: RepositorySummary) {
    setScanningRepositoryId(repository.id);
    setActionError('');

    try {
      await rescanRepository(repository);
      await refreshRepositories();
    } catch (error) {
      setActionError(
        error instanceof Error ? error.message : 'Unable to scan repository.',
      );
    } finally {
      setScanningRepositoryId(null);
    }
  }

  const filteredRepositories = useMemo(() => {
    const normalizedSearch = searchTerm.trim().toLowerCase();

    return repositories.filter((repository) => {
      const status = normalizeScanStatus(repository.latestScanStatus ?? null);
      const matchesSearch =
        !normalizedSearch ||
        repository.name.toLowerCase().includes(normalizedSearch) ||
        repository.url.toLowerCase().includes(normalizedSearch);
      const matchesStatus = statusFilter === 'all' || status === statusFilter;

      return matchesSearch && matchesStatus;
    });
  }, [repositories, searchTerm, statusFilter]);

  return (
    <section className="view-stack" aria-labelledby="repositories-title">
      <div className="page-heading">
        <div>
          <h1 id="repositories-title">Repo Dashboard</h1>
          <p>
            Manage imported repositories, scan state, and branch-ready codebase
            context.
          </p>
        </div>
        <button
          className="primary-button"
          type="button"
          onClick={() => setIsImportOpen(true)}
        >
          <FolderGit2 aria-hidden="true" size={17} />
          Import Repo
        </button>
      </div>

      <section className="repository-panel" aria-label="Repository inventory">
        <RepositoryToolbar
          searchTerm={searchTerm}
          statusFilter={statusFilter}
          onSearchTermChange={setSearchTerm}
          onStatusFilterChange={setStatusFilter}
        />

        {repositoryError ? (
          <DismissibleAlert
            title="Repositories could not load"
            onDismiss={() => setRepositoryError('')}
          >
            {repositoryError}
          </DismissibleAlert>
        ) : null}
        {actionError ? (
          <DismissibleAlert
            title="Repository action failed"
            onDismiss={() => setActionError('')}
          >
            {actionError}
          </DismissibleAlert>
        ) : null}

        <div className="repository-table-wrap">
          <table className="repository-table">
            <thead>
              <tr>
                <th>Repository</th>
                <th>Status</th>
                <th>Active Branch</th>
                <th>Metrics</th>
                <th>Last Scan</th>
                <th className="is-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {isLoadingRepositories ? (
                <tr>
                  <td colSpan={6}>
                    <div className="empty-state">
                      <LoaderCircle aria-hidden="true" className="spin" size={22} />
                      Loading repositories...
                    </div>
                  </td>
                </tr>
              ) : null}

              {!isLoadingRepositories &&
                filteredRepositories.map((repository) => {
                  const status = normalizeScanStatus(
                    repository.latestScanStatus ?? null,
                  );

                  return (
                    <tr
                      key={repository.id}
                      className="repository-row"
                      onClick={() => selectRepository(repository)}
                    >
                      <td>
                        <div className="repo-cell">
                          <strong>{repository.name}</strong>
                          <span>{repository.url}</span>
                        </div>
                      </td>
                      <td>
                        <StatusBadge status={status} />
                      </td>
                      <td className="mono-cell">
                        {repository.latestBranch ||
                          repository.defaultBranch ||
                          'main'}
                      </td>
                      <td>
                        <span className="metric-inline">
                          {formatCount(repository.fileCount)}
                        </span>{' '}
                        files
                      </td>
                      <td>{formatDate(repository.lastScannedAt)}</td>
                      <td
                        className="is-right"
                        onClick={(event) => event.stopPropagation()}
                      >
                        <button
                          className="secondary-button compact"
                          type="button"
                          disabled={scanningRepositoryId === repository.id}
                          onClick={() => void handleScan(repository)}
                        >
                          {scanningRepositoryId === repository.id ? (
                            <LoaderCircle
                              aria-hidden="true"
                              className="spin"
                              size={14}
                            />
                          ) : (
                            <Play aria-hidden="true" size={14} />
                          )}
                          Scan
                        </button>
                      </td>
                    </tr>
                  );
                })}

              {!isLoadingRepositories && filteredRepositories.length === 0 ? (
                <tr>
                  <td colSpan={6}>
                    <div className="empty-state">
                      <Search aria-hidden="true" size={22} />
                      {repositories.length === 0
                        ? 'No repositories imported yet.'
                        : 'No repositories match the current filters.'}
                    </div>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  );
}

type RepositoryDetailProps = {
  repository: RepositorySummary;
};

const tabs = [
  { id: 'pull-requests', label: 'Pull Requests', icon: GitPullRequest, isAi: false },
  { id: 'history', label: 'History & Branches', icon: Clock, isAi: false },
  { id: 'diff', label: 'Branch Comparison', icon: GitBranch, isAi: false },
  { id: 'explorer', label: 'Code Explorer', icon: FolderGit2, isAi: false },
  { id: 'risk', label: 'Risk Report', icon: ShieldAlert, isAi: true },
  { id: 'ask', label: 'Ask AI', icon: Sparkles, isAi: true },
] as const;

type TabId = (typeof tabs)[number]['id'];

function RepositoryDetail({ repository }: RepositoryDetailProps) {
  const { navigateToDashboard, refreshRepositories } = useRepositories();
  const [activeTab, setActiveTab] = useState<TabId>('diff');
  const [isRescanning, setIsRescanning] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [actionError, setActionError] = useState('');
  const currentTab = tabs.find((tab) => tab.id === activeTab) ?? tabs[2];
  const status = normalizeScanStatus(repository.latestScanStatus ?? null);

  async function handleRescan() {
    setIsRescanning(true);
    setActionError('');

    try {
      await rescanRepository(repository);
      await refreshRepositories();
    } catch (error) {
      setActionError(
        error instanceof Error ? error.message : 'Unable to scan repository.',
      );
    } finally {
      setIsRescanning(false);
    }
  }

  async function handleDelete() {
    setIsDeleting(true);
    setActionError('');

    try {
      await deleteRepository(repository.id);
      await refreshRepositories();
      navigateToDashboard();
    } catch (error) {
      setActionError(
        error instanceof Error ? error.message : 'Unable to delete repository.',
      );
    } finally {
      setIsDeleting(false);
    }
  }

  return (
    <section className="view-stack" aria-labelledby="repository-detail-title">
      <Breadcrumbs
        repositoryName={repository.name}
        currentPage={currentTab.label}
        onHome={navigateToDashboard}
      />

      <section className="repo-overview" aria-label="Repository overview">
        <div className="repo-overview__header">
          <div>
            <div className="repo-title-row">
              <h1 id="repository-detail-title">{repository.name}</h1>
              <StatusBadge status={status} />
            </div>
            <p>
              <GitBranch aria-hidden="true" size={16} />
              {repository.url}
            </p>
          </div>
          <div className="overview-actions">
            <button
              className="secondary-button"
              type="button"
              disabled={isRescanning || isDeleting}
              onClick={() => void handleRescan()}
            >
              {isRescanning ? (
                <LoaderCircle aria-hidden="true" className="spin" size={16} />
              ) : (
                <Play aria-hidden="true" size={16} />
              )}
              Re-scan
            </button>
            <button
              className="icon-button danger"
              type="button"
              disabled={isDeleting || isRescanning}
              aria-label="Delete repository"
              onClick={() => void handleDelete()}
            >
              {isDeleting ? (
                <LoaderCircle aria-hidden="true" className="spin" size={18} />
              ) : (
                <Trash2 aria-hidden="true" size={18} />
              )}
            </button>
          </div>
        </div>

        {actionError ? (
          <DismissibleAlert
            title="Repository action failed"
            onDismiss={() => setActionError('')}
          >
            {actionError}
          </DismissibleAlert>
        ) : null}

        <RepositoryStats repository={repository} />
      </section>

      <div className="tabs" role="tablist" aria-label="Repository modules">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;

          return (
            <button
              key={tab.id}
              className={`${isActive ? 'is-active' : ''} ${tab.isAi ? 'is-ai' : ''
                }`}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => setActiveTab(tab.id)}
            >
              <Icon aria-hidden="true" size={16} />
              {tab.label}
            </button>
          );
        })}
      </div>

      <section className="tab-panel" role="tabpanel">
        {activeTab === 'diff' ? <BranchComparisonTab repository={repository} /> : null}
        {activeTab === 'pull-requests'
          ? <PullRequestsTab key={repository.id} repository={repository} /> : null}
        {activeTab === 'risk' ? <RiskReportTab /> : null}
        {activeTab === 'ask' ? <AskAITab /> : null}
        {activeTab === 'history' ? (
          <PlaceholderTab
            icon={Clock}
            title="History & Branches"
            body="Scan history and branch timelines will live here."
          />
        ) : null}
        {activeTab === 'explorer' ? (
          <PlaceholderTab
            icon={FolderGit2}
            title="Code Explorer"
            body="Indexed packages, classes, and method navigation will live here."
          />
        ) : null}
      </section>
    </section>
  );
}

function BranchComparisonTab({ repository }: RepositoryDetailProps) {
  return (
    <div className="module-stack">
      <div className="comparison-control">
        <label>
          <span>Base</span>
          <select defaultValue={repository.defaultBranch || 'main'}>
            <option>{repository.defaultBranch || 'main'}</option>
          </select>
        </label>
        <ArrowRightLeft aria-hidden="true" size={20} />
        <label>
          <span>Compare</span>
          <select defaultValue={repository.latestBranch || 'feature/current'}>
            <option>{repository.latestBranch || 'feature/current'}</option>
          </select>
        </label>
        <button className="primary-button" type="button">
          Run Diff Analysis
        </button>
      </div>

      <div className="diff-metrics">
        <MetricCard label="Changed Files" value="12" />
        <MetricCard label="Additions" value="+450" tone="positive" />
        <MetricCard label="Deletions" value="-120" tone="negative" />
      </div>

      <div className="changed-files">
        <div className="changed-files__header">Changed Files List</div>
        <ChangedFile path="src/controllers/PaymentGateway.ts" additions="+120" deletions="-15" />
        <ChangedFile path="package.json" additions="+2" deletions="-0" />
      </div>
    </div>
  );
}

function RiskReportTab() {
  return (
    <div className="risk-panel">
      <div className="risk-panel__header">
        <div className="ai-mark">
          <Sparkles aria-hidden="true" size={22} />
        </div>
        <div>
          <h2>AI Risk Analysis</h2>
          <p>Evaluated against the selected base branch.</p>
        </div>
        <div className="risk-score">
          <strong>72</strong>
          <span>/100</span>
        </div>
      </div>

      <div className="risk-grid">
        <article>
          <ShieldAlert aria-hidden="true" size={19} />
          <h3>Payment Logic Modified</h3>
          <p>
            Changes were detected in a high-risk controller. Confirm idempotency
            and fallback behavior before merge.
          </p>
        </article>
        <article>
          <Activity aria-hidden="true" size={19} />
          <h3>Coverage Gap</h3>
          <p>
            Added production logic outpaces test edits. Generate a test plan
            before approving this branch.
          </p>
        </article>
      </div>
    </div>
  );
}

function AskAITab() {
  return (
    <div className="ask-panel">
      <div className="chat-stream">
        <div className="chat-message">
          <span>
            <Bot aria-hidden="true" size={16} />
          </span>
          <p>
            I am initialized with this repository context. Ask about
            architecture, branch changes, or risky files.
          </p>
        </div>
      </div>
      <label className="chat-input">
        <span className="sr-only">Ask AI about this repository</span>
        <input placeholder="Ask about this repo..." />
        <button type="button" aria-label="Send question">
          <Send aria-hidden="true" size={17} />
        </button>
      </label>
    </div>
  );
}

type PlaceholderTabProps = {
  icon: typeof FolderGit2;
  title: string;
  body: string;
};

function PlaceholderTab({ icon: Icon, title, body }: PlaceholderTabProps) {
  return (
    <div className="placeholder-tab">
      <Icon aria-hidden="true" size={30} />
      <h2>{title}</h2>
      <p>{body}</p>
    </div>
  );
}

type MetricCardProps = {
  label: string;
  value: string;
  tone?: 'positive' | 'negative';
};

function MetricCard({ label, value, tone }: MetricCardProps) {
  return (
    <article className={`metric-card ${tone ? `metric-card--${tone}` : ''}`}>
      <strong>{value}</strong>
      <span>{label}</span>
    </article>
  );
}

type ChangedFileProps = {
  path: string;
  additions: string;
  deletions: string;
};

function ChangedFile({ path, additions, deletions }: ChangedFileProps) {
  return (
    <div className="changed-file">
      <span>
        <FileCode2 aria-hidden="true" size={16} />
        {path}
      </span>
      <strong>
        <span className="positive">{additions}</span>
        <span className="negative">{deletions}</span>
      </strong>
    </div>
  );
}

export function RepositoryViews() {
  const { selectedRepository } = useRepositories();

  return selectedRepository ? (
    <RepositoryDetail repository={selectedRepository} />
  ) : (
    <RepositoryDashboard />
  );
}
