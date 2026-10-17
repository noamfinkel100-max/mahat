import type { User } from './api';

// Deliberately whitelist identity fields. File codes and file lists never belong in a session.
export function parseUserSession(data: unknown): User | null {
  if (!data || typeof data !== 'object') return null;
  const candidate = data as Partial<User>;
  return typeof candidate.userId === 'string' &&
    candidate.userId.length > 0 &&
    typeof candidate.name === 'string' &&
    candidate.name.length > 0
    ? { userId: candidate.userId, name: candidate.name }
    : null;
}
