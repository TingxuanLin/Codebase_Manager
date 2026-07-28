import { ChevronRight } from 'lucide-react';

type BreadcrumbsProps = {
  repositoryName?: string;
  currentPage?: string;
  onHome: () => void;
};

export function Breadcrumbs({
  repositoryName,
  currentPage = 'Branch Comparison',
  onHome,
}: BreadcrumbsProps) {
  if (!repositoryName) {
    return null;
  }

  return (
    <nav className="breadcrumbs" aria-label="Breadcrumbs">
      <button className="breadcrumbs__link" type="button" onClick={onHome}>
        Repositories
      </button>
      <ChevronRight aria-hidden="true" size={15} />
      <span className="breadcrumbs__repo">{repositoryName}</span>
      <ChevronRight aria-hidden="true" size={15} />
      <span aria-current="page">{currentPage}</span>
    </nav>
  );
}
