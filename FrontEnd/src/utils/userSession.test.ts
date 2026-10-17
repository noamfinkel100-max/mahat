import { describe, expect, it } from 'vitest';
import { parseUserSession } from './userSession';

describe('parseUserSession', () => {
  it('accepts a server-authenticated identity', () => {
    expect(parseUserSession({ userId: 'user-1', name: 'alice' })).toEqual({
      userId: 'user-1',
      name: 'alice',
    });
  });
  it('never copies encryption codes or file state into the session', () => {
    expect(
      parseUserSession({
        userId: 'user-1',
        name: 'alice',
        myFiles: { 'report.pdf': 'secret-code' },
      })
    ).toEqual({ userId: 'user-1', name: 'alice' });
  });
  it('rejects missing or malformed identity fields', () => {
    for (const input of [
      null,
      {},
      { userId: '1' },
      { userId: 12, name: 'alice' },
      { userId: '', name: 'alice' },
    ])
      expect(parseUserSession(input)).toBeNull();
  });
});
