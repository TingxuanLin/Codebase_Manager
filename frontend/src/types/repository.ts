export type RepositorySummary = {
  id: number;
  name: string;
  url: string;
  branchCount?: number | null;
  defaultBranch?: string | null;
  latestBranch?: string | null;
  latestCommitSha?: string | null;
  latestScanRunId?: number | null;
  latestScanStatus?: string | null;
  lastScannedAt?: string | null;
  fileCount?: number | null;
  classCount?: number | null;
  methodCount?: number | null;
};

export type ParseRepositoryResponse = {
  repositoryId: number;
  branchId: number;
  scanRunId: number;
  name: string;
  branch: string;
  headCommitSha: string;
  fileCount: number;
  classCount: number;
  methodCount: number;
  dependencyCount: number;
  externalDependencyCount: number;
  apiRouteCount: number;
};

export type ImportRepositoryPayload =
  | {
      source: 'github';
      url: string;
      branch?: string;
      name?: string;
    }
  | {
      source: 'local';
      path: string;
      name?: string;
      url?: string;
    };

export type PullRequestSummary = {
  number: number;
  title: string;
  state: string;
  baseBranch: string;
  headBranch: string;
  baseSha?: string | null;
  headSha?: string | null;
  authorLogin?: string | null;
  htmlUrl: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  newlySeen: boolean;
};

export type PullRequestCheckResponse = {
  repositoryId: number;
  repositoryUrl: string;
  defaultBranch: string;
  openPullRequestCount: number;
  newPullRequestCount: number;
  pullRequests: PullRequestSummary[];
};

export type PullRequestFileChange = {
  path: string;
  oldPath?: string | null;
  changeType: string;
  additions: number;
  deletions: number;
  changes: number;
  patch?: string | null;
  blobUrl?: string | null;
  rawUrl?: string | null;
};

export type PullRequestDiff = {
  repositoryId: number;
  number: number;
  title: string;
  state: string;
  baseBranch: string;
  headBranch: string;
  baseSha?: string | null;
  headSha?: string | null;
  diffFetchedAt?: string | null;
  changedFileCount: number;
  additions: number;
  deletions: number;
  changes: PullRequestFileChange[];
};
