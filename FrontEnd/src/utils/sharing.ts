export const downloadLimits = [
  { value: '1', label: '1 download' },
  { value: '3', label: '3 downloads' },
  { value: '5', label: '5 downloads' },
  { value: 'unlimited', label: 'Unlimited' },
];
export const expirations = [
  { value: '1h', label: '1 hour' },
  { value: '24h', label: '24 hours' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: 'custom', label: 'Custom date and time' },
  { value: 'never', label: 'Never expires' },
];
export function resolveExpiration(
  option: string,
  custom: string,
  current: string | null,
  now = Date.now()
): string | null {
  if (option === 'keep') return current;
  if (option === 'never') return null;
  const hours: Record<string, number> = { '1h': 1, '24h': 24, '7d': 168, '30d': 720 };
  const date = option === 'custom' ? new Date(custom) : new Date(now + hours[option] * 3600000);
  if (!Number.isFinite(date.getTime()) || date.getTime() <= now)
    throw new Error('Choose an expiration date and time in the future.');
  return date.toISOString();
}
