import type { filesStateType } from '@/App';

type UserSessionResponse = {
  userId?: string;
  myFiles?: Record<string, string> | string;
  sharedWithMe?: string[] | string;
};

const parseValue = <T>(value: T | string | undefined, fallback: T): T => {
  if (typeof value !== 'string') {
    return value ?? fallback;
  }

  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
};

export function parseUserSession(data: unknown, name: string): filesStateType | null {
  let response: UserSessionResponse | undefined;

  try {
    response =
      typeof data === 'string'
        ? (JSON.parse(data) as UserSessionResponse)
        : (data as UserSessionResponse);
  } catch {
    return null;
  }

  if (!response?.userId) {
    return null;
  }

  const myFiles = parseValue<Record<string, string>>(response.myFiles, {});
  const sharedWithMe = parseValue<string[]>(response.sharedWithMe, []);

  if (
    !myFiles ||
    typeof myFiles !== 'object' ||
    Array.isArray(myFiles) ||
    !Array.isArray(sharedWithMe)
  ) {
    return null;
  }

  return { userId: response.userId, user: name, myFiles, sharedWithMe };
}
