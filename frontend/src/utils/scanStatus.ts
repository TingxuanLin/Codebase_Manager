import type { ScanStatus } from '../types/scan';

export function normalizeScanStatus(status: string | null): ScanStatus {
  const normalized = status?.toLowerCase();

  if (normalized === 'completed' || normalized === 'success') {
    return 'Success';
  }

  if (normalized === 'running' || normalized === 'parsing') {
    return 'Parsing';
  }

  if (normalized === 'failed' || normalized === 'error') {
    return 'Failed';
  }

  return 'Unknown';
}
