import { useRef, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Divider,
  Group,
  Modal,
  Paper,
  PasswordInput,
  Progress,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import {
  IconCheck,
  IconCloudUpload,
  IconFile,
  IconInfoCircle,
  IconLock,
  IconRefresh,
  IconShieldLock,
  IconTrash,
  IconX,
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, formatSize } from '@/utils/api';
import classes from './UploadFileModal.module.css';

const MAX_FILE_BYTES = 100 * 1024 * 1024;
export function validateUpload(file: File | null, code: string): string {
  if (!file) return 'Select a file to upload.';
  if (!file.size) return 'Empty files cannot be uploaded.';
  if (file.size > MAX_FILE_BYTES) return 'Choose a file smaller than 100 MB.';
  if (code.length < 8 || code.length > 256)
    return 'Use an encryption code with 8 to 256 characters.';
  return '';
}

type Phase = 'idle' | 'uploading' | 'processing' | 'success' | 'error';
export function UploadFileModal({ opened, close }: { opened: boolean; close: VoidFunction }) {
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const inFlight = useRef(false);
  const [file, setFile] = useState<File | null>(null);
  const [code, setCode] = useState('');
  const [phase, setPhase] = useState<Phase>('idle');
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [dragging, setDragging] = useState(false);
  const busy = phase === 'uploading' || phase === 'processing';

  const choose = (files: FileList | File[]) => {
    if (busy) return;
    setError('');
    setPhase('idle');
    setProgress(null);
    if (files.length !== 1) {
      setError('Choose one file at a time.');
      return;
    }
    const selected = files[0];
    if (!selected.size || selected.size > MAX_FILE_BYTES) {
      setError(
        !selected.size ? 'Empty files cannot be uploaded.' : 'Choose a file smaller than 100 MB.'
      );
      return;
    }
    setFile(selected);
  };
  const reset = () => {
    setFile(null);
    setCode('');
    setError('');
    setPhase('idle');
    setProgress(null);
    setDragging(false);
    if (input.current) input.current.value = '';
  };
  const dismiss = () => {
    if (!busy) {
      reset();
      close();
    }
  };
  const upload = async () => {
    if (inFlight.current) return;
    const validation = validateUpload(file, code);
    if (validation) {
      setError(validation);
      return;
    }
    inFlight.current = true;
    setError('');
    setPhase('uploading');
    setProgress(null);
    const form = new FormData();
    form.append('file', file!);
    form.append('code', code);
    try {
      await api.post('/files', form, {
        onUploadProgress: (event) => {
          if (event.total) {
            const percent = Math.min(100, Math.floor((event.loaded / event.total) * 100));
            setProgress(percent);
            if (percent === 100) setPhase('processing');
          }
        },
      });
      setCode('');
      setPhase('success');
      await queryClient.invalidateQueries({ queryKey: ['files'] });
    } catch (failure) {
      setError(errorMessage(failure));
      setPhase('error');
    } finally {
      inFlight.current = false;
    }
  };

  return (
    <Modal
      closeButtonProps={{ 'aria-label': 'Close dialog' }}
      opened={opened}
      onClose={dismiss}
      title={
        <Group gap="xs">
          <ThemeIcon variant="light" radius="md">
            <IconCloudUpload size={19} />
          </ThemeIcon>
          <Text fw={700}>Upload a file</Text>
        </Group>
      }
      size="lg"
      centered
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      withCloseButton={!busy}
    >
      {phase === 'success' ? (
        <Stack align="center" ta="center" py="xl" gap="md">
          <ThemeIcon size={72} radius="xl" color="teal" variant="light">
            <IconCheck size={36} />
          </ThemeIcon>
          <Title order={3}>Upload complete</Title>
          <Text c="dimmed" size="sm">
            {file?.name} is encrypted and ready in your vault.
          </Text>
          <Text size="sm" c="dimmed">
            Keep your encryption code somewhere safe. You will need it to download this file.
          </Text>
          <Group>
            <Button variant="light" onClick={reset}>
              Upload another file
            </Button>
            <Button onClick={dismiss}>Done</Button>
          </Group>
        </Stack>
      ) : (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void upload();
          }}
        >
          <Stack gap="lg">
            <Text size="sm" c="dimmed">
              Add a file to your private vault. You can manage sharing after the upload finishes.
            </Text>
            {error && (
              <Alert color="red" icon={<IconX size={18} />} role="alert">
                {error}
              </Alert>
            )}
            <Box>
              <Group gap="xs" mb="sm">
                <Badge size="sm" circle variant="light">
                  1
                </Badge>
                <Text fw={700} size="sm">
                  Select file
                </Text>
                <Badge ml="auto" color="gray" variant="light">
                  100 MB maximum
                </Badge>
              </Group>
              <input
                ref={input}
                type="file"
                aria-label="File"
                disabled={busy}
                className={classes.fileInput}
                onChange={(event) => {
                  if (event.currentTarget.files?.length) choose(event.currentTarget.files);
                  event.currentTarget.value = '';
                }}
              />
              <button
                type="button"
                data-testid="upload-dropzone"
                className={classes.dropzone}
                data-dragging={dragging}
                disabled={busy}
                onClick={() => input.current?.click()}
                onDragOver={(event) => {
                  event.preventDefault();
                  if (!busy) setDragging(true);
                }}
                onDragLeave={() => setDragging(false)}
                onDrop={(event) => {
                  event.preventDefault();
                  setDragging(false);
                  choose(event.dataTransfer.files);
                }}
              >
                <ThemeIcon size={54} radius="lg" variant="light">
                  <IconCloudUpload size={29} stroke={1.5} />
                </ThemeIcon>
                <Text fw={650} mt="sm">
                  {file ? 'Drop a file to replace your selection' : 'Drag your file here'}
                </Text>
                <Text size="sm" c="dimmed" mt={4}>
                  or click to browse your device
                </Text>
              </button>
              {file && (
                <Paper withBorder radius="md" p="sm" mt="sm">
                  <Group wrap="nowrap" gap="sm">
                    <ThemeIcon variant="light" color="gray" size={40} radius="md">
                      <IconFile size={22} />
                    </ThemeIcon>
                    <Box style={{ flex: 1, minWidth: 0 }}>
                      <Text fw={600} size="sm" style={{ overflowWrap: 'anywhere' }}>
                        {file.name}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {formatSize(file.size)} · {file.type || 'Unknown file type'}
                      </Text>
                    </Box>
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      aria-label="Replace selected file"
                      disabled={busy}
                      onClick={() => input.current?.click()}
                    >
                      <IconRefresh size={17} />
                    </ActionIcon>
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label="Remove selected file"
                      disabled={busy}
                      onClick={() => {
                        setFile(null);
                        setProgress(null);
                      }}
                    >
                      <IconTrash size={17} />
                    </ActionIcon>
                  </Group>
                </Paper>
              )}
            </Box>
            <Box>
              <Group gap="xs" mb="sm">
                <Badge size="sm" circle variant="light">
                  2
                </Badge>
                <Text fw={700} size="sm">
                  Configure encryption
                </Text>
              </Group>
              <PasswordInput
                visibilityToggleButtonProps={{ 'aria-label': 'Toggle password visibility' }}
                label="Encryption code"
                description="Use 8–256 characters. This code is required for every download."
                placeholder="Choose a strong encryption code"
                autoComplete="new-password"
                leftSection={<IconLock size={17} />}
                value={code}
                onChange={(event) => setCode(event.currentTarget.value)}
                disabled={busy}
                maxLength={256}
                required
              />
              <Group align="flex-start" gap={7} mt="xs" wrap="nowrap">
                <IconInfoCircle size={16} style={{ flexShrink: 0 }} />
                <Text c="dimmed" size="xs">
                  Encryption takes place on the server. Your code is used for this request and is
                  not saved in your account. Share it with recipients through a trusted channel.
                </Text>
              </Group>
            </Box>
            <Divider />
            <Box>
              <Group gap="xs" mb="sm">
                <Badge size="sm" circle variant="light">
                  3
                </Badge>
                <Text fw={700} size="sm">
                  Review and upload
                </Text>
              </Group>
              {busy && (
                <Box aria-live="polite" mb="md">
                  <Group justify="space-between" mb={5}>
                    <Text size="sm" fw={600}>
                      {phase === 'processing'
                        ? 'Encrypting and storing your file…'
                        : 'Transferring file…'}
                    </Text>
                    <Text size="sm">
                      {progress === null ? 'In progress' : `${progress}% transferred`}
                    </Text>
                  </Group>
                  <Progress value={progress ?? 0} animated aria-label="File upload progress" />
                  <Text c="dimmed" size="xs" mt={6}>
                    {phase === 'processing'
                      ? 'Transfer complete. Waiting for server confirmation.'
                      : 'Keep this window open until the server confirms your upload.'}
                  </Text>
                </Box>
              )}
              <Group justify="space-between" gap="sm">
                <Group gap={6}>
                  <IconShieldLock size={16} />
                  <Text c="dimmed" size="xs">
                    Encrypted before storage
                  </Text>
                </Group>
                <Group gap="xs">
                  <Button variant="default" onClick={dismiss} disabled={busy}>
                    Cancel
                  </Button>
                  <Button
                    type="submit"
                    loading={busy}
                    leftSection={
                      phase === 'error' ? <IconRefresh size={17} /> : <IconCloudUpload size={17} />
                    }
                  >
                    {phase === 'error' ? 'Retry upload' : 'Upload file'}
                  </Button>
                </Group>
              </Group>
            </Box>
          </Stack>
        </form>
      )}
    </Modal>
  );
}
