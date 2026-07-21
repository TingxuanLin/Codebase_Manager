import { CheckCircle2, LoaderCircle, XCircle, HelpCircle } from 'lucide-react';
import type { ScanStatus } from '../types/scan';

type StatusBadgeProps = {
  status: ScanStatus;
};

const statusConfig = {
  Success: {
    label: 'Success',
    className: 'status-badge status-badge--success',
    icon: CheckCircle2,
  },
  Parsing: {
    label: 'Parsing',
    className: 'status-badge status-badge--parsing',
    icon: LoaderCircle,
  },
  Failed: {
    label: 'Failed',
    className: 'status-badge status-badge--failed',
    icon: XCircle,
  },
  Unknown: {
    label: 'Unknown',
    className: 'status-badge status-badge--unknown',
    icon: HelpCircle,
  },
} satisfies Record<
  ScanStatus,
  { label: string; className: string; icon: typeof CheckCircle2 }
>;

export function StatusBadge({ status }: StatusBadgeProps) {
  const config = statusConfig[status];
  const Icon = config.icon;

  return (
    <span className={config.className}>
      <Icon aria-hidden="true" size={15} />
      {config.label}
    </span>
  );
}
