import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, screen } from '@testing-library/react';
import { api } from '@/utils/api';

import userEvent from '@testing-library/user-event';
import { render } from '@test-utils';
import { UploadFileModal, validateUpload } from './UploadFileModal';

describe('secure upload form', () => {
  it('validates missing files, empty files, and short codes', () => {
    expect(validateUpload(null, 'valid-secret')).toContain('Select a file');
    expect(validateUpload(new File([], 'empty.txt'), 'valid-secret')).toContain('Empty files');
    expect(validateUpload(new File(['hello'], 'hello.txt'), 'short')).toContain('8 to 256');
    expect(validateUpload(new File(['hello'], 'hello.txt'), 'valid-secret')).toBe('');
  });
  it('supports selection, replacement, removal, and visible validation', async () => {
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <UploadFileModal opened close={() => {}} />
      </QueryClientProvider>
    );
    await user.click(screen.getByRole('button', { name: 'Upload file' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Select a file');
    const input = screen.getByLabelText('File');
    await user.upload(input, new File(['report'], 'report.txt', { type: 'text/plain' }));
    expect(screen.getByText('report.txt')).toBeInTheDocument();
    await user.upload(input, new File(['replacement'], 'new.txt', { type: 'text/plain' }));
    expect(screen.queryByText('report.txt')).not.toBeInTheDocument();
    expect(screen.getByText('new.txt')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove selected file' }));
    expect(screen.queryByText('new.txt')).not.toBeInTheDocument();
    fireEvent.drop(screen.getByTestId('upload-dropzone'), {
      dataTransfer: { files: [new File(['dropped'], 'dropped.txt')] },
    });
    expect(screen.getByText('dropped.txt')).toBeInTheDocument();
  });
  it('shows transfer completion as processing until the server confirms success', async () => {
    const user = userEvent.setup();
    let resolveUpload!: (response: Awaited<ReturnType<typeof api.post>>) => void;
    const pending = new Promise<Awaited<ReturnType<typeof api.post>>>((resolve) => {
      resolveUpload = resolve;
    });
    const request = vi.spyOn(api, 'post').mockImplementationOnce((_url, _body, config) => {
      config?.onUploadProgress?.({
        loaded: 100,
        total: 100,
        bytes: 100,
        lengthComputable: true,
        upload: true,
      });
      return pending;
    });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <UploadFileModal opened close={() => {}} />
      </QueryClientProvider>
    );
    await user.upload(screen.getByLabelText('File'), new File(['report'], 'report.txt'));
    await user.type(screen.getByLabelText(/Encryption code/), 'private-code');
    await user.click(screen.getByRole('button', { name: 'Upload file' }));
    expect(screen.getByRole('progressbar', { name: 'File upload progress' })).toHaveAttribute(
      'aria-valuenow',
      '100'
    );
    expect(
      screen.getByText('Transfer complete. Waiting for server confirmation.')
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Upload complete' })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Encryption code/)).toBeDisabled();
    await act(async () =>
      resolveUpload({ data: {}, status: 201 } as Awaited<ReturnType<typeof api.post>>)
    );
    expect(screen.getByRole('heading', { name: 'Upload complete' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Encryption code/)).not.toBeInTheDocument();
    request.mockRestore();
  });
  it('keeps a failed upload retryable and never reports failure as success', async () => {
    const user = userEvent.setup();
    const request = vi
      .spyOn(api, 'post')
      .mockRejectedValueOnce(new Error('Storage temporarily unavailable.'))
      .mockResolvedValueOnce({ data: {}, status: 201 } as Awaited<ReturnType<typeof api.post>>);
    render(
      <QueryClientProvider client={new QueryClient()}>
        <UploadFileModal opened close={() => {}} />
      </QueryClientProvider>
    );
    await user.upload(screen.getByLabelText('File'), new File(['report'], 'retry.txt'));
    await user.type(screen.getByLabelText(/Encryption code/), 'private-code');
    await user.click(screen.getByRole('button', { name: 'Upload file' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Storage temporarily unavailable.');
    expect(screen.queryByRole('heading', { name: 'Upload complete' })).not.toBeInTheDocument();
    expect(screen.getByText('retry.txt')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry upload' }));
    expect(await screen.findByRole('heading', { name: 'Upload complete' })).toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(2);
    request.mockRestore();
  });
});
