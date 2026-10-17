import { useState } from 'react';
import axios from 'axios';
import { Alert, Button, Group, Modal, PasswordInput, Stack, Text } from '@mantine/core';
import { IconDownload, IconLock } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, StoredFile } from '@/utils/api';

export function DownloadFileModal({ file, close }: { file: StoredFile; close: VoidFunction }) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const queryClient = useQueryClient();
  const download = async () => {
    if (busy) return;
    if (!code) {
      setError('Enter the encryption code to download this file.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const response = await api.post<Blob>(
        `/files/${file.fileId}/download`,
        { code },
        { responseType: 'blob' }
      );
      const url = URL.createObjectURL(response.data);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.originalFileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setCode('');
      close();
    } catch (failure) {
      if (axios.isAxiosError<Blob>(failure) && failure.response?.data instanceof Blob) {
        try {
          setError(
            (JSON.parse(await failure.response.data.text()) as { error?: string }).error ||
              'Download failed. Please try again.'
          );
        } catch {
          setError('Download failed. Please try again.');
        }
      } else setError(errorMessage(failure));
    } finally {
      setBusy(false);
      await queryClient.invalidateQueries({ queryKey: ['files'] });
    }
  };
  return (
    <Modal
      closeButtonProps={{ 'aria-label': 'Close dialog' }}
      opened
      onClose={() => {
        if (!busy) {
          setCode('');
          close();
        }
      }}
      title="Download file"
      centered
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      withCloseButton={!busy}
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void download();
        }}
        noValidate
      >
        <Stack>
          <Text fw={600} style={{ overflowWrap: 'anywhere' }}>
            {file.originalFileName}
          </Text>
          <Text size="sm" c="dimmed">
            Enter the code used when this file was uploaded. An incorrect code does not consume your
            download allowance.
          </Text>
          {error && (
            <Alert color="red" role="alert">
              {error}
            </Alert>
          )}
          <PasswordInput
            visibilityToggleButtonProps={{ 'aria-label': 'Toggle password visibility' }}
            label="Encryption code"
            autoComplete="off"
            placeholder="Enter your encryption code"
            value={code}
            onChange={(event) => setCode(event.currentTarget.value)}
            disabled={busy}
            leftSection={<IconLock size={17} />}
            maxLength={256}
            required
          />
          <Group justify="flex-end">
            <Button variant="default" disabled={busy} onClick={close}>
              Cancel
            </Button>
            <Button type="submit" loading={busy} leftSection={<IconDownload size={17} />}>
              Download file
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
