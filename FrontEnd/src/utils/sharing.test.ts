import { describe, expect, it } from 'vitest';
import { expirationLabel, shareState, Share } from './api';
import { resolveExpiration } from './sharing';

const now = Date.parse('2026-01-01T12:00:00Z');
const share: Share = {
  recipientId: 'recipient',
  recipientName: 'Bob',
  maxDownloads: 1,
  downloadCount: 0,
  expiresAt: null,
  revoked: false,
  remainingDownloads: 1,
};
describe('sharing policy presentation', () => {
  it('distinguishes expired and exhausted grants, including an exact expiration boundary', () => {
    expect(shareState(share, now)).toBe('Active');
    expect(shareState({ ...share, downloadCount: 1 }, now)).toBe('Limit reached');
    expect(shareState({ ...share, expiresAt: new Date(now).toISOString() }, now)).toBe('Expired');
    expect(shareState({ ...share, revoked: true }, now)).toBe('Revoked');
    expect(shareState({ ...share, maxDownloads: null, downloadCount: 100 }, now)).toBe('Active');
  });
  it('converts relative expirations to UTC without changing an existing timestamp when editing', () => {
    expect(resolveExpiration('1h', '', null, now)).toBe('2026-01-01T13:00:00.000Z');
    expect(resolveExpiration('30d', '', null, now)).toBe('2026-01-31T12:00:00.000Z');
    expect(resolveExpiration('never', '', null, now)).toBeNull();
    expect(resolveExpiration('keep', '', '2025-01-01T00:00:00Z', now)).toBe('2025-01-01T00:00:00Z');
  });
  it('rejects malformed or past custom expiration times', () => {
    expect(() => resolveExpiration('custom', '', null, now)).toThrow(/future/);
    expect(() => resolveExpiration('custom', '2025-01-01T00:00:00', null, now)).toThrow(/future/);
  });
  it('describes never-expiring and expired access', () => {
    expect(expirationLabel(null, now)).toBe('Never expires');
    expect(expirationLabel('2026-01-01T13:00:00Z', now)).toContain('Expires in 1h');
    expect(expirationLabel('2025-01-01T00:00:00Z', now)).toContain('Expired');
  });
});
