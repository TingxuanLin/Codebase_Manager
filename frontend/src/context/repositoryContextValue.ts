import { createContext } from 'react';
import type { BackendStatus } from '../types/backend';
import type { RepositorySummary } from '../types/repository';

export type RepositoryContextValue = {
  backendStatus: BackendStatus;
  repositories: RepositorySummary[];
  selectedRepository: RepositorySummary | null;
  isImportOpen: boolean;
  isLoadingRepositories: boolean;
  repositoryError: string;
  importError: string;
  setIsImportOpen: (isOpen: boolean) => void;
  selectRepository: (repository: RepositorySummary) => void;
  navigateToDashboard: () => void;
  refreshRepositories: () => Promise<void>;
  setRepositoryError: (message: string) => void;
  setImportError: (message: string) => void;
};

export const RepositoryContext =
  createContext<RepositoryContextValue | null>(null);
