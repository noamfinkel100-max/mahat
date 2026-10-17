import { describe, expect, it } from 'vitest';
import { parseUserSession } from './userSession';

describe('parseUserSession', () => {
  it('parses the current API response shape', () => {
    expect(
      parseUserSession(
        {
          userId: 'user-1',
          myFiles: { 'report.pdf': 'secret-code' },
          sharedWithMe: ['notes.txt'],
        },
        'alice'
      )
    ).toEqual({
      userId: 'user-1',
      user: 'alice',
      myFiles: { 'report.pdf': 'secret-code' },
      sharedWithMe: ['notes.txt'],
    });
  });

  it('accepts file lists encoded as JSON strings', () => {
    expect(
      parseUserSession(
        {
          userId: 'user-2',
          myFiles: '{"report.pdf":"secret-code"}',
          sharedWithMe: '["notes.txt"]',
        },
        'bob'
      )
    ).toEqual({
      userId: 'user-2',
      user: 'bob',
      myFiles: { 'report.pdf': 'secret-code' },
      sharedWithMe: ['notes.txt'],
    });
  });

  it('rejects a successful response that has no session identifier', () => {
    expect(parseUserSession({ myFiles: {}, sharedWithMe: [] }, 'carol')).toBeNull();
  });
});
