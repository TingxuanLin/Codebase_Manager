import type { RepositorySummary } from '../types/repository';

type RepositoryStatsProps = {
  repository: RepositorySummary;
};

const formatCount = (value?: number | null) =>
  typeof value === 'number' ? value.toLocaleString() : '0';

export function RepositoryStats({ repository }: RepositoryStatsProps) {
  const stats = [
    {
      label: 'Default Branch',
      value: repository.defaultBranch || 'main',
      variant: 'code',
    },
    {
      label: 'Files Indexed',
      value: formatCount(repository.fileCount),
    },
    {
      label: 'Classes',
      value: formatCount(repository.classCount),
    },
    {
      label: 'Methods',
      value: formatCount(repository.methodCount),
    },
  ];

  return (
    <div className="repo-stats" aria-label="Repository overview metrics">
      {stats.map((stat) => (
        <div className="repo-stats__item" key={stat.label}>
          <span>{stat.label}</span>
          <strong className={stat.variant === 'code' ? 'is-code' : undefined}>
            {stat.value}
          </strong>
        </div>
      ))}
    </div>
  );
}
