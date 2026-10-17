import axios from 'axios';

export type User = { userId: string; name: string };
export type Share = {
  recipientId: string;
  recipientName: string;
  maxDownloads: 1 | 3 | 5 | null;
  downloadCount: number;
  expiresAt: string | null;
  revoked: boolean;
  remainingDownloads: number | null;
};
export type StoredFile = {
  fileId: string;
  ownerId: string;
  originalFileName: string;
  contentType: string;
  fileSize: number;
  createdAt: string;
  status: string;
  share?: Share;
};
export type FileList = { owned: StoredFile[]; shared: StoredFile[] };

// Authentication is carried by an HttpOnly cookie, never by a user ID from React.
export const api = axios.create({ baseURL: '/api', withCredentials: true });

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError<{ error?: string }>(error)) {
    return error.response?.data?.error || 'The request could not be completed. Please try again.';
  }
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}

export function formatSize(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 ** 2) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 ** 2).toFixed(1)} MB`;
}

export function shareState(
  share: Share,
  now = Date.now()
): 'Revoked' | 'Expired' | 'Limit reached' | 'Active' {
  if (share.revoked) return 'Revoked';
  if (share.expiresAt && new Date(share.expiresAt).getTime() <= now) return 'Expired';
  if (share.maxDownloads !== null && share.downloadCount >= share.maxDownloads)
    return 'Limit reached';
  return 'Active';
}

export function expirationLabel(expiresAt: string | null, now = Date.now()): string {
  if (!expiresAt) return 'Never expires';
  const minutes = Math.ceil((new Date(expiresAt).getTime() - now) / 60000);
  if (minutes <= 0) return `Expired ${new Date(expiresAt).toLocaleString()}`;
  const remaining =
    minutes < 60
      ? `${minutes}m`
      : minutes < 1440
        ? `${Math.ceil(minutes / 60)}h`
        : `${Math.ceil(minutes / 1440)}d`;
  return `Expires in ${remaining} · ${new Date(expiresAt).toLocaleString()}`;
}
