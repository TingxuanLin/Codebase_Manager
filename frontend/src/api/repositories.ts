import type {
  ImportRepositoryPayload,
  ParseRepositoryResponse,
  PullRequestCheckResponse,
  PullRequestDiff,
  PullRequestSummary,
  RepositorySummary,
} from '../types/repository';
import type { GitHubBranch } from '../types/github';

async function readError(response: Response) {
  const message = await response.text();
  return message || `Request failed with ${response.status}`;
}

export async function fetchBackendHealth(signal?: AbortSignal) {
  const response = await fetch('/api/actuator/health', { signal });
  return response.ok;
}

export async function fetchRepositories(signal?: AbortSignal) {
  const response = await fetch('/api/repositories', { signal });

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as RepositorySummary[];
}

export async function fetchGitHubBranches(url: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ url });
  const response = await fetch(`/api/repositories/github-branches?${params}`, {
    signal,
  });

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as GitHubBranch[];
}

export async function importRepository(payload: ImportRepositoryPayload) {
  if (payload.source === 'local') {
    throw new Error('Local path import is not supported by the backend yet.');
  }

  const body = {
    url: payload.url,
    branch: payload.branch || undefined,
    name: payload.name || undefined,
  };

  const response = await fetch('/api/repositories/parse-github', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as ParseRepositoryResponse;
}

export async function rescanRepository(repository: RepositorySummary) {
  if (!repository.url.startsWith('http')) {
    throw new Error('Local repositories need to be imported again with a path.');
  }

  return importRepository({
    source: 'github',
    url: repository.url,
    branch: repository.latestBranch || repository.defaultBranch || undefined,
    name: repository.name,
  });
}

export async function deleteRepository(repositoryId: number) {
  const response = await fetch(`/api/repositories/${repositoryId}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    throw new Error(await readError(response));
  }
}

export async function fetchPullRequests(
  repositoryId: number,
  signal?: AbortSignal,
) {
  const response = await fetch(
    `/api/repositories/${repositoryId}/pull-requests`,
    { signal },
  );

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as PullRequestSummary[];
}

export async function checkPullRequests(repositoryId: number) {
  const response = await fetch(
    `/api/repositories/${repositoryId}/pull-requests/check`,
    { method: 'POST' },
  );

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as PullRequestCheckResponse;
}

export async function fetchPullRequestDiff(
  repositoryId: number,
  pullRequestNumber: number,
  signal?: AbortSignal,
) {
  const response = await fetch(
    `/api/repositories/${repositoryId}/pull-requests/${pullRequestNumber}/diff`,
    { signal },
  );

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return (await response.json()) as PullRequestDiff;
}
