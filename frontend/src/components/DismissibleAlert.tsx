import { AlertCircle, X } from 'lucide-react';

type DismissibleAlertProps = {
  children: string;
  onDismiss: () => void;
  title?: string;
  tone?: 'error';
};

export function DismissibleAlert({
  children,
  onDismiss,
  title = 'Something needs attention',
  tone = 'error',
}: DismissibleAlertProps) {
  return (
    <div className={`alert-banner alert-banner--${tone}`} role="alert">
      <AlertCircle className="alert-banner__icon" aria-hidden="true" size={20} />
      <div className="alert-banner__content">
        <strong>{title}</strong>
        <p>{children}</p>
      </div>
      <button
        className="alert-banner__dismiss"
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss alert"
      >
        <X aria-hidden="true" size={17} />
      </button>
    </div>
  );
}
