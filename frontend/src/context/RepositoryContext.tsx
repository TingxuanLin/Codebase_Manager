import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import {
  fetchBackendHealth,
  fetchRepositories,
} from '../api/repositories';
import type { BackendStatus } from '../types/backend';
import type { RepositorySummary } from '../types/repository';
import {
  RepositoryContext,
  type RepositoryContextValue,
} from './repositoryContextValue';

type RepositoryProviderProps = {
  children: ReactNode;
};

export function RepositoryProvider({ children }: RepositoryProviderProps) {
  const [backendStatus, setBackendStatus] =
    useState<BackendStatus>('checking');
  const [repositories, setRepositories] =
    useState<RepositorySummary[]>([]);
  const [selectedRepositoryId, setSelectedRepositoryId] =
    useState<number | null>(null);
  const [isImportOpen, setIsImportOpen] = useState(false);
  const [isLoadingRepositories, setIsLoadingRepositories] = useState(false);
  const [repositoryError, setRepositoryError] = useState('');
  const [importError, setImportError] = useState('');

  const refreshRepositories = useCallback(async () => {
    setIsLoadingRepositories(true);
    setRepositoryError('');

    try {
      const nextRepositories = await fetchRepositories();
      setRepositories(nextRepositories);
    } catch (error) {
      setRepositoryError(
        error instanceof Error
          ? error.message
          : 'Unable to load repositories.',
      );
    } finally {
      setIsLoadingRepositories(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();

    fetchBackendHealth(controller.signal)
      .then((isOnline) => {
        setBackendStatus(isOnline ? 'online' : 'offline');
        if (isOnline) {
          void refreshRepositories();
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setBackendStatus('offline');
        }
      });

    return () => controller.abort();
  }, [refreshRepositories]);

  const selectRepository = useCallback((repository: RepositorySummary) => {
    setSelectedRepositoryId(repository.id);
  }, []);

  const navigateToDashboard = useCallback(() => {
    setSelectedRepositoryId(null);
  }, []);

  const selectedRepository = useMemo(
    () =>
      repositories.find((repository) => repository.id === selectedRepositoryId) ??
      null,
    [repositories, selectedRepositoryId],
  );

  const value = useMemo<RepositoryContextValue>(
    () => ({
      backendStatus,
      repositories,
      selectedRepository,
      isImportOpen,
      isLoadingRepositories,
      repositoryError,
      importError,
      setIsImportOpen,
      selectRepository,
      navigateToDashboard,
      refreshRepositories,
      setRepositoryError,
      setImportError,
    }),
    [
      backendStatus,
      repositories,
      selectedRepository,
      isImportOpen,
      isLoadingRepositories,
      repositoryError,
      importError,
      selectRepository,
      navigateToDashboard,
      refreshRepositories,
    ],
  );

  return (
    <RepositoryContext.Provider value={value}>
      {children}
    </RepositoryContext.Provider>
  );
}
