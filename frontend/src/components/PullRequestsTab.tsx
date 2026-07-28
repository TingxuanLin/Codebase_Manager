import { useEffect, useState } from 'react';
import {
  ExternalLink,
  FileCode2,
  GitPullRequest,
  LoaderCircle,
  RefreshCw,
} from 'lucide-react';
import {
  checkPullRequests,
  fetchPullRequestDiff,
  fetchPullRequests,
} from '../api/repositories';
import type {
  PullRequestDiff,
  PullRequestFileChange,
  PullRequestSummary,
  RepositorySummary,
} from '../types/repository';
import { DismissibleAlert } from './DismissibleAlert';

type PullRequestsTabProps = {
  repository: RepositorySummary;
};

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

const formatSignedCount = (value: number, sign: '+' | '-') =>
  `${sign}${value.toLocaleString()}`;

export function PullRequestsTab({ repository }: PullRequestsTabProps) {
  const [pullRequests, setPullRequests] = useState<PullRequestSummary[]>([]);
  const [selectedPullRequestNumber, setSelectedPullRequestNumber] = useState<
    number | null
  >(null);
  const [isLoadingPullRequests, setIsLoadingPullRequests] = useState(true);
  const [isCheckingPullRequests, setIsCheckingPullRequests] = useState(false);
  const [pullRequestError, setPullRequestError] = useState('');

  useEffect(() => {
    const controller = new AbortController();

    fetchPullRequests(repository.id, controller.signal)
      .then((nextPullRequests) => {
        setPullRequests(nextPullRequests);
        setSelectedPullRequestNumber(nextPullRequests[0]?.number ?? null);
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return;
        }
        setPullRequestError(
          error instanceof Error
            ? error.message
            : 'Unable to load pull requests.',
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsLoadingPullRequests(false);
        }
      });

    return () => controller.abort();
  }, [repository.id]);

  async function handleCheckPullRequests() {
    setIsCheckingPullRequests(true);
    setPullRequestError('');

    try {
      const response = await checkPullRequests(repository.id);
      setPullRequests(response.pullRequests);
      setSelectedPullRequestNumber((currentNumber) => {
        if (
          currentNumber !== null &&
          response.pullRequests.some(
            (pullRequest) => pullRequest.number === currentNumber,
          )
        ) {
          return currentNumber;
        }

        return response.pullRequests[0]?.number ?? null;
      });
    } catch (error) {
      setPullRequestError(
        error instanceof Error
          ? error.message
          : 'Unable to check pull requests.',
      );
    } finally {
      setIsCheckingPullRequests(false);
    }
  }

  const selectedPullRequest = pullRequests.find(
    (pullRequest) => pullRequest.number === selectedPullRequestNumber,
  );

  return (
    <div className="pull-request-layout">
      <section className="pull-request-list" aria-label="Pull requests">
        <div className="pull-request-list__header">
          <div>
            <h2>Pull Requests</h2>
            <p>{pullRequests.length.toLocaleString()} open PRs stored</p>
          </div>
          <button
            className="secondary-button compact"
            type="button"
            disabled={isCheckingPullRequests || isLoadingPullRequests}
            onClick={() => void handleCheckPullRequests()}
          >
            {isCheckingPullRequests ? (
              <LoaderCircle aria-hidden="true" className="spin" size={14} />
            ) : (
              <RefreshCw aria-hidden="true" size={14} />
            )}
            Check PRs
          </button>
        </div>

        {pullRequestError ? (
          <DismissibleAlert
            title="Pull request action failed"
            onDismiss={() => setPullRequestError('')}
          >
            {pullRequestError}
          </DismissibleAlert>
        ) : null}

        <div className="pull-request-list__body">
          {isLoadingPullRequests ? (
            <div className="empty-state">
              <LoaderCircle aria-hidden="true" className="spin" size={22} />
              Loading pull requests...
            </div>
          ) : null}

          {!isLoadingPullRequests && pullRequests.length === 0 ? (
            <div className="empty-state">
              <GitPullRequest aria-hidden="true" size={24} />
              No stored pull requests yet. Check GitHub for open PRs.
            </div>
          ) : null}

          {!isLoadingPullRequests &&
            pullRequests.map((pullRequest) => (
              <button
                key={pullRequest.number}
                className={`pull-request-card ${
                  selectedPullRequestNumber === pullRequest.number
                    ? 'is-active'
                    : ''
                }`}
                type="button"
                onClick={() => setSelectedPullRequestNumber(pullRequest.number)}
              >
                <span className="pull-request-card__topline">
                  <strong>#{pullRequest.number}</strong>
                  <span>{pullRequest.state}</span>
                  {pullRequest.newlySeen ? <em>New</em> : null}
                </span>
                <span className="pull-request-card__title">
                  {pullRequest.title}
                </span>
                <span className="pull-request-card__meta">
                  {pullRequest.authorLogin || 'Unknown author'} -{' '}
                  {formatDate(pullRequest.updatedAt || pullRequest.createdAt)}
                </span>
                <span className="pull-request-card__branches">
                  {pullRequest.baseBranch} &lt;- {pullRequest.headBranch}
                </span>
              </button>
            ))}
        </div>
      </section>

      <section className="pull-request-diff" aria-label="Pull request diff">
        {!selectedPullRequest ? (
          <div className="placeholder-tab">
            <GitPullRequest aria-hidden="true" size={30} />
            <h2>Select a Pull Request</h2>
            <p>Stored file changes will appear here.</p>
          </div>
        ) : null}

        {selectedPullRequest ? (
          <PullRequestDiffPanel
            key={`${repository.id}-${selectedPullRequest.number}`}
            repositoryId={repository.id}
            pullRequest={selectedPullRequest}
          />
        ) : null}
      </section>
    </div>
  );
}

type PullRequestDiffPanelProps = {
  repositoryId: number;
  pullRequest: PullRequestSummary;
};

function PullRequestDiffPanel({
  repositoryId,
  pullRequest,
}: PullRequestDiffPanelProps) {
  const [pullRequestDiff, setPullRequestDiff] = useState<PullRequestDiff | null>(
    null,
  );
  const [isLoadingDiff, setIsLoadingDiff] = useState(true);
  const [diffError, setDiffError] = useState('');

  useEffect(() => {
    const controller = new AbortController();

    fetchPullRequestDiff(repositoryId, pullRequest.number, controller.signal)
      .then(setPullRequestDiff)
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return;
        }
        setDiffError(
          error instanceof Error ? error.message : 'Unable to load PR diff.',
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsLoadingDiff(false);
        }
      });

    return () => controller.abort();
  }, [repositoryId, pullRequest.number]);

  return (
    <>
      <div className="pull-request-diff__header">
        <div>
          <span className="pull-request-kicker">PR #{pullRequest.number}</span>
          <h2>{pullRequest.title}</h2>
          <p>
            {pullRequest.baseBranch} &lt;- {pullRequest.headBranch}
          </p>
        </div>
        <a
          className="secondary-button compact"
          href={pullRequest.htmlUrl}
          target="_blank"
          rel="noreferrer"
        >
          <ExternalLink aria-hidden="true" size={14} />
          GitHub
        </a>
      </div>

      {diffError ? (
        <DismissibleAlert
          title="Diff could not load"
          onDismiss={() => setDiffError('')}
        >
          {diffError}
        </DismissibleAlert>
      ) : null}

      {isLoadingDiff ? (
        <div className="empty-state">
          <LoaderCircle aria-hidden="true" className="spin" size={22} />
          Loading PR diff...
        </div>
      ) : null}

      {!isLoadingDiff && pullRequestDiff ? (
        <PullRequestDiffView
          key={`${pullRequestDiff.number}-${pullRequestDiff.headSha || ''}`}
          diff={pullRequestDiff}
        />
      ) : null}
    </>
  );
}

function PullRequestDiffView({ diff }: { diff: PullRequestDiff }) {
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(() =>
    new Set(diff.changes.slice(0, 1).map((change) => change.path)),
  );

  function togglePath(path: string) {
    setExpandedPaths((currentPaths) => {
      const nextPaths = new Set(currentPaths);
      if (nextPaths.has(path)) {
        nextPaths.delete(path);
      } else {
        nextPaths.add(path);
      }
      return nextPaths;
    });
  }

  return (
    <div className="module-stack">
      <div className="diff-metrics">
        <MetricCard
          label="Changed Files"
          value={diff.changedFileCount.toLocaleString()}
        />
        <MetricCard
          label="Additions"
          value={formatSignedCount(diff.additions, '+')}
          tone="positive"
        />
        <MetricCard
          label="Deletions"
          value={formatSignedCount(diff.deletions, '-')}
          tone="negative"
        />
      </div>

      <div className="pull-request-diff__meta">
        <span>Diff fetched {formatDate(diff.diffFetchedAt)}</span>
        <span className="is-code">
          {diff.baseSha || 'base'} -&gt; {diff.headSha || 'head'}
        </span>
      </div>

      <div className="changed-files">
        <div className="changed-files__header">Changed Files List</div>
        {diff.changes.length === 0 ? (
          <div className="empty-state">
            <FileCode2 aria-hidden="true" size={22} />
            No stored file changes for this PR.
          </div>
        ) : null}
        {diff.changes.map((change) => (
          <PullRequestFileChangeRow
            key={change.path}
            change={change}
            isExpanded={expandedPaths.has(change.path)}
            onToggle={() => togglePath(change.path)}
          />
        ))}
      </div>
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

type PullRequestFileChangeRowProps = {
  change: PullRequestFileChange;
  isExpanded: boolean;
  onToggle: () => void;
};

function PullRequestFileChangeRow({
  change,
  isExpanded,
  onToggle,
}: PullRequestFileChangeRowProps) {
  return (
    <article className="pull-request-file">
      <button
        className="pull-request-file__summary"
        type="button"
        onClick={onToggle}
      >
        <span>
          <FileCode2 aria-hidden="true" size={16} />
          <span>
            <strong>{change.path}</strong>
            {change.oldPath ? <em>from {change.oldPath}</em> : null}
          </span>
        </span>
        <strong>
          <em>{change.changeType}</em>
          <span className="positive">
            {formatSignedCount(change.additions, '+')}
          </span>
          <span className="negative">
            {formatSignedCount(change.deletions, '-')}
          </span>
        </strong>
      </button>

      {isExpanded ? (
        <div className="pull-request-file__details">
          <div className="pull-request-file__links">
            {change.blobUrl ? (
              <a href={change.blobUrl} target="_blank" rel="noreferrer">
                Blob
              </a>
            ) : null}
            {change.rawUrl ? (
              <a href={change.rawUrl} target="_blank" rel="noreferrer">
                Raw
              </a>
            ) : null}
          </div>
          {change.patch ? (
            <pre>{change.patch}</pre>
          ) : (
            <div className="empty-state compact">
              Patch text is not available for this file.
            </div>
          )}
        </div>
      ) : null}
    </article>
  );
}
