import { type FormEvent, useState } from 'react';
import { GitBranch, LoaderCircle, X } from 'lucide-react';
import { importRepository } from '../api/repositories';
import { useRepositories } from '../hooks/useRepositories';
import { DismissibleAlert } from './DismissibleAlert';

export function ImportPanel() {
  const {
    isImportOpen,
    setIsImportOpen,
    refreshRepositories,
    importError,
    setImportError,
  } = useRepositories();
  const [url, setUrl] = useState('');
  const [branch, setBranch] = useState('');
  const [name, setName] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (!isImportOpen) {
    return null;
  }

  const closeModal = () => {
    if (!isSubmitting) {
      setIsImportOpen(false);
      setImportError('');
    }
  };

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setIsSubmitting(true);
    setImportError('');

    try {
      await importRepository({
        source: 'github',
        url,
        branch: branch || undefined,
        name: name || undefined,
      });
      await refreshRepositories();
      setIsImportOpen(false);
      setUrl('');
      setBranch('');
      setName('');
    } catch (error) {
      setImportError(
        error instanceof Error
          ? error.message
          : 'Unable to import repository.',
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={closeModal}>
      <section
        className="import-modal"
        aria-labelledby="import-modal-title"
        role="dialog"
        aria-modal="true"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="import-modal__header">
          <div>
            <h2 id="import-modal-title">Import Repository</h2>
          </div>
          <button
            className="icon-button"
            type="button"
            onClick={closeModal}
            aria-label="Close import modal"
          >
            <X aria-hidden="true" size={19} />
          </button>
        </div>

        <div className="segmented-control" aria-label="Import source">
          <button className="is-active" type="button">
            <GitBranch aria-hidden="true" size={16} />
            GitHub URL
          </button>
        </div>

        <form className="import-form" onSubmit={handleSubmit}>
          <label>
            <span>Repository URL</span>
            <input
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://github.com/org/repo"
              required
            />
          </label>

          <div className="field-grid">
            <label>
              <span>Name</span>
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Derived from source"
              />
            </label>
            <label>
              <span>Branch</span>
              <input
                value={branch}
                onChange={(event) => setBranch(event.target.value)}
                placeholder="Default branch"
              />
            </label>
          </div>

          {importError ? (
            <DismissibleAlert
              title="Import failed"
              onDismiss={() => setImportError('')}
            >
              {importError}
            </DismissibleAlert>
          ) : null}

          <div className="modal-actions">
            <button className="secondary-button" type="button" onClick={closeModal}>
              Cancel
            </button>
            <button className="primary-button" type="submit" disabled={isSubmitting}>
              {isSubmitting ? (
                <LoaderCircle aria-hidden="true" className="spin" size={16} />
              ) : null}
              Start Parsing
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
