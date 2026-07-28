import { Search } from 'lucide-react';

type RepositoryToolbarProps = {
  searchTerm: string;
  statusFilter: string;
  onSearchTermChange: (value: string) => void;
  onStatusFilterChange: (value: string) => void;
};

export function RepositoryToolbar({
  searchTerm,
  statusFilter,
  onSearchTermChange,
  onStatusFilterChange,
}: RepositoryToolbarProps) {
  return (
    <div className="repository-toolbar">
      <label className="repository-toolbar__search">
        <Search aria-hidden="true" size={16} />
        <span className="sr-only">Search repositories</span>
        <input
          value={searchTerm}
          onChange={(event) => onSearchTermChange(event.target.value)}
          placeholder="Search repositories..."
          type="search"
        />
      </label>

      <label className="repository-toolbar__filter">
        <span className="sr-only">Filter by status</span>
        <select
          value={statusFilter}
          onChange={(event) => onStatusFilterChange(event.target.value)}
        >
          <option value="all">All Status</option>
          <option value="Success">Success</option>
          <option value="Parsing">Parsing</option>
          <option value="Failed">Failed</option>
          <option value="Unknown">Unknown</option>
        </select>
      </label>
    </div>
  );
}
