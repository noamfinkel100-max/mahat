import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { api, Share, StoredFile } from '@/utils/api';
import { SessionContext } from '@/utils/session';
import { render } from '@test-utils';
import { DownloadFileModal } from './DownloadFileModal';
import { ShareFileModal } from './ShareFileModal';

const file: StoredFile = {
  fileId: 'file-1',
  ownerId: 'owner-1',
  originalFileName: 'report.txt',
  contentType: 'text/plain',
  fileSize: 6,
  createdAt: '2026-01-01T00:00:00.000Z',
  status: 'ACTIVE',
};

const activeShare: Share = {
  recipientId: 'recipient-1',
  recipientName: 'Recipient',
  maxDownloads: 1,
  downloadCount: 0,
  expiresAt: null,
  revoked: false,
  remainingDownloads: 1,
};

describe('share and download modals', () => {
  it('updates the access table immediately after a recipient is revoked', async () => {
    const user = userEvent.setup();
    let shares = [activeShare];
    const revokedShare = { ...activeShare, revoked: true };
    const getMock = vi.spyOn(api, 'get').mockImplementation(
      (url) =>
        Promise.resolve({
          data: url === '/users' ? [{ userId: 'recipient-1', name: 'Recipient' }] : shares,
        }) as ReturnType<typeof api.get>
    );
    const deleteMock = vi.spyOn(api, 'delete').mockImplementation(async () => {
      shares = [revokedShare];
      return { data: revokedShare } as Awaited<ReturnType<typeof api.delete>>;
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <SessionContext.Provider
          value={{ user: { userId: 'owner-1', name: 'Owner' }, loading: false }}
        >
          <ShareFileModal file={file} close={() => {}} />
        </SessionContext.Provider>
      </QueryClientProvider>
    );

    await screen.findByText('1 of 1 downloads remaining');
    await user.click(screen.getByRole('button', { name: 'Revoke access for Recipient' }));
    await user.click(await screen.findByRole('button', { name: 'Revoke access' }));

    expect(await screen.findByText('Revoked')).toBeInTheDocument();
    expect(screen.getByText('0 recipients')).toBeInTheDocument();
    getMock.mockRestore();
    deleteMock.mockRestore();
    queryClient.clear();
  });

  it('refreshes the access table after a recipient downloads the file', async () => {
    let shares = [activeShare];
    const getMock = vi.spyOn(api, 'get').mockImplementation(
      (url) =>
        Promise.resolve({
          data: url === '/users' ? [{ userId: 'recipient-1', name: 'Recipient' }] : shares,
        }) as ReturnType<typeof api.get>
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <QueryClientProvider client={queryClient}>
        <SessionContext.Provider
          value={{ user: { userId: 'owner-1', name: 'Owner' }, loading: false }}
        >
          <ShareFileModal file={file} close={() => {}} />
        </SessionContext.Provider>
      </QueryClientProvider>
    );

    await screen.findByText('1 of 1 downloads remaining');
    shares = [{ ...activeShare, downloadCount: 1, remainingDownloads: 0 }];
    expect(
      await screen.findByText('0 of 1 downloads remaining', {}, { timeout: 7000 })
    ).toBeInTheDocument();
    getMock.mockRestore();
    queryClient.clear();
  }, 10000);

  it('prevents Chrome from autofilling the encryption code as a saved password', () => {
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <DownloadFileModal file={file} close={() => {}} />
      </QueryClientProvider>
    );

    const codeInput = screen.getByPlaceholderText('Enter your encryption code');
    expect(codeInput).toHaveAttribute('autocomplete', 'new-password');
    expect(codeInput.closest('form')).toHaveAttribute('autocomplete', 'off');
    queryClient.clear();
  });
});
