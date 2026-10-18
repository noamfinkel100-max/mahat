import { useState } from 'react';
import {
  Alert,
  Badge,
  Box,
  Button,
  Divider,
  Group,
  Loader,
  Modal,
  Paper,
  Select,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { IconCheck, IconClock, IconEdit, IconShare, IconUserX } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  errorMessage,
  expirationLabel,
  Share,
  shareState,
  StoredFile,
  User,
} from '@/utils/api';
import { useSession } from '@/utils/session';
import { downloadLimits, expirations, resolveExpiration } from '@/utils/sharing';

export function ShareFileModal({ file, close }: { file: StoredFile; close: VoidFunction }) {
  const { user } = useSession();
  const queryClient = useQueryClient();
  const [recipientId, setRecipientId] = useState<string | null>(null);
  const [limit, setLimit] = useState<string | null>('1');
  const [expiry, setExpiry] = useState<string | null>('24h');
  const [custom, setCustom] = useState('');
  const [editing, setEditing] = useState<Share | null>(null);
  const [message, setMessage] = useState('');
  const [validation, setValidation] = useState('');
  const [revoking, setRevoking] = useState<Share | null>(null);
  const users = useQuery({
    queryKey: ['users'],
    queryFn: async () => (await api.get<User[]>('/users')).data,
  });
  const shares = useQuery({
    queryKey: ['shares', file.fileId],
    queryFn: async () => (await api.get<Share[]>(`/files/${file.fileId}/shares`)).data,
    refetchInterval: 5000,
  });
  const sync = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['shares', file.fileId] }),
      queryClient.invalidateQueries({ queryKey: ['files'] }),
    ]);
  };
  const resetForm = () => {
    setRecipientId(null);
    setLimit('1');
    setExpiry('24h');
    setCustom('');
    setEditing(null);
    setValidation('');
  };
  const save = useMutation({
    mutationFn: async () => {
      if (!recipientId) throw new Error('Select a recipient.');
      const expiresAt = resolveExpiration(expiry ?? '24h', custom, editing?.expiresAt ?? null);
      const policy = {
        recipientId,
        maxDownloads: limit === 'unlimited' ? null : Number(limit),
        expiresAt,
      };
      return editing
        ? api.put(`/files/${file.fileId}/shares/${recipientId}`, policy)
        : api.post(`/files/${file.fileId}/shares`, policy);
    },
    onSuccess: async () => {
      setMessage(editing ? 'Sharing policy updated.' : 'File shared successfully.');
      resetForm();
      await sync();
    },
  });
  const revoke = useMutation({
    mutationFn: async (recipient: string) =>
      (await api.delete<Share>(`/files/${file.fileId}/shares/${recipient}`)).data,
    onSuccess: async (revokedShare) => {
      queryClient.setQueryData<Share[]>(['shares', file.fileId], (current) =>
        current?.map((share) =>
          share.recipientId === revokedShare.recipientId ? revokedShare : share
        )
      );
      setRevoking(null);
      setMessage('Access revoked. This recipient can no longer download the file.');
      await sync();
    },
  });
  const edit = (share: Share) => {
    setEditing(share);
    setRecipientId(share.recipientId);
    setLimit(share.maxDownloads === null ? 'unlimited' : String(share.maxDownloads));
    setExpiry('keep');
    setCustom('');
    setValidation('');
    setMessage('');
    save.reset();
  };
  const existing = new Set(
    (shares.data ?? []).filter((share) => !share.revoked).map((share) => share.recipientId)
  );
  const recipients = (users.data ?? [])
    .filter(
      (candidate) =>
        candidate.userId !== user?.userId &&
        (!existing.has(candidate.userId) || candidate.userId === recipientId)
    )
    .map((candidate) => ({ value: candidate.userId, label: candidate.name }));
  const busy = save.isPending || revoke.isPending;
  return (
    <>
      <Modal
        closeButtonProps={{ 'aria-label': 'Close dialog' }}
        opened={!revoking}
        onClose={close}
        title={
          <Group gap="xs">
            <IconShare size={20} />
            <Text fw={700}>Share file</Text>
          </Group>
        }
        size="lg"
        centered
        closeOnClickOutside={!busy}
        closeOnEscape={!busy}
        withCloseButton={!busy}
      >
        <Stack gap="lg">
          <Box>
            <Text fw={650} style={{ overflowWrap: 'anywhere' }}>
              {file.originalFileName}
            </Text>
            <Text size="sm" c="dimmed" mt={4}>
              Set a separate download allowance and expiration for each recipient. Send the
              encryption code through a trusted channel.
            </Text>
          </Box>
          {message && (
            <Alert color="teal" icon={<IconCheck size={18} />} role="status">
              {message}
            </Alert>
          )}
          {(users.isError || shares.isError) && (
            <Alert color="red" role="alert">
              {errorMessage(users.error || shares.error)}
              <Button
                variant="subtle"
                color="red"
                size="xs"
                ml="sm"
                onClick={() => {
                  void users.refetch();
                  void shares.refetch();
                }}
              >
                Retry loading
              </Button>
            </Alert>
          )}
          <Paper withBorder p="md" radius="lg">
            <Text fw={700} mb="md">
              {editing ? `Edit access for ${editing.recipientName}` : 'Add a recipient'}
            </Text>
            <form
              noValidate
              onSubmit={(event) => {
                event.preventDefault();
                setValidation('');
                setMessage('');
                if (!recipientId) {
                  setValidation('Select a recipient.');
                  return;
                }
                try {
                  resolveExpiration(expiry ?? '24h', custom, editing?.expiresAt ?? null);
                } catch (failure) {
                  setValidation(errorMessage(failure));
                  return;
                }
                save.mutate();
              }}
            >
              <Stack gap="md">
                {(validation || save.isError) && (
                  <Alert color="red" role="alert">
                    {validation || errorMessage(save.error)}
                  </Alert>
                )}
                <Select
                  comboboxProps={{ withinPortal: false }}
                  label="Recipient"
                  placeholder={users.isPending ? 'Loading recipients…' : 'Select a person'}
                  searchable
                  data={recipients}
                  value={recipientId}
                  onChange={setRecipientId}
                  disabled={Boolean(editing) || busy || users.isPending}
                  required
                  nothingFoundMessage="No recipients found"
                />
                <Group grow align="flex-start">
                  <Select
                    comboboxProps={{ withinPortal: false }}
                    label="Download limit"
                    data={downloadLimits}
                    value={limit}
                    onChange={setLimit}
                    allowDeselect={false}
                    disabled={busy}
                  />
                  <Select
                    comboboxProps={{ withinPortal: false }}
                    label="Expires"
                    data={
                      editing
                        ? [{ value: 'keep', label: 'Keep current expiration' }, ...expirations]
                        : expirations
                    }
                    value={expiry}
                    onChange={setExpiry}
                    allowDeselect={false}
                    disabled={busy}
                  />
                </Group>
                {expiry === 'custom' && (
                  <TextInput
                    type="datetime-local"
                    label="Custom expiration date and time"
                    description="Choose your local time. The server stores the timestamp in UTC."
                    value={custom}
                    onChange={(event) => setCustom(event.currentTarget.value)}
                    disabled={busy}
                    required
                  />
                )}
                {editing && (
                  <Text size="xs" c="dimmed">
                    {editing.downloadCount} downloads already used. Changing the limit keeps this
                    counter. {expirationLabel(editing.expiresAt)}
                  </Text>
                )}
                <Group justify="flex-end">
                  {editing && (
                    <Button variant="default" disabled={busy} onClick={resetForm}>
                      Cancel editing
                    </Button>
                  )}
                  <Button
                    type="submit"
                    loading={save.isPending}
                    disabled={revoke.isPending || users.isError || shares.isError}
                    leftSection={<IconShare size={16} />}
                  >
                    Save sharing
                  </Button>
                </Group>
              </Stack>
            </form>
          </Paper>
          <Box>
            <Group justify="space-between" mb="sm">
              <Text fw={700}>People with access</Text>
              <Badge variant="light" color="gray">
                {shares.data?.filter((share) => !share.revoked).length ?? 0} recipients
              </Badge>
            </Group>
            {shares.isPending ? (
              <Loader size="sm" aria-label="Loading sharing permissions" />
            ) : !shares.data?.length ? (
              <Text size="sm" c="dimmed">
                This file has not been shared yet.
              </Text>
            ) : (
              <Stack gap="sm">
                {shares.data.map((share) => {
                  const state = shareState(share);
                  return (
                    <Paper
                      key={share.recipientId}
                      p="md"
                      withBorder
                      radius="md"
                      data-testid="share-policy"
                      data-recipient-id={share.recipientId}
                    >
                      <Group justify="space-between" align="flex-start" gap="sm">
                        <Box style={{ minWidth: 0 }}>
                          <Group gap="xs">
                            <Text fw={650} style={{ overflowWrap: 'anywhere' }}>
                              {share.recipientName}
                            </Text>
                            <Badge
                              size="sm"
                              color={
                                state === 'Active'
                                  ? 'teal'
                                  : state === 'Expired'
                                    ? 'orange'
                                    : 'gray'
                              }
                              variant="light"
                            >
                              {state}
                            </Badge>
                          </Group>
                          <Text size="sm" c="dimmed" mt={5}>
                            {share.maxDownloads === null
                              ? 'Unlimited downloads'
                              : `${Math.max(0, share.maxDownloads - share.downloadCount)} of ${share.maxDownloads} downloads remaining`}
                          </Text>
                          <Group gap={5} mt={4} wrap="nowrap">
                            <IconClock size={14} style={{ flexShrink: 0 }} />
                            <Text size="xs" c="dimmed">
                              {expirationLabel(share.expiresAt)}
                            </Text>
                          </Group>
                        </Box>
                        <Group gap="xs">
                          <Button
                            size="xs"
                            variant="light"
                            disabled={busy}
                            leftSection={<IconEdit size={14} />}
                            aria-label={`Edit access for ${share.recipientName}`}
                            onClick={() => edit(share)}
                          >
                            Edit
                          </Button>
                          {!share.revoked && (
                            <Button
                              size="xs"
                              color="red"
                              variant="subtle"
                              disabled={busy}
                              leftSection={<IconUserX size={14} />}
                              aria-label={`Revoke access for ${share.recipientName}`}
                              onClick={() => {
                                setRevoking(share);
                                revoke.reset();
                              }}
                            >
                              Revoke
                            </Button>
                          )}
                        </Group>
                      </Group>
                    </Paper>
                  );
                })}
              </Stack>
            )}
          </Box>
          <Divider />
          <Group justify="flex-end">
            <Button variant="default" disabled={busy} onClick={close}>
              Done
            </Button>
          </Group>
        </Stack>
      </Modal>
      <Modal
        closeButtonProps={{ 'aria-label': 'Close dialog' }}
        opened={Boolean(revoking)}
        onClose={() => {
          if (!revoke.isPending) setRevoking(null);
        }}
        title="Revoke access?"
        centered
        closeOnEscape={!revoke.isPending}
        closeOnClickOutside={!revoke.isPending}
        withCloseButton={!revoke.isPending}
      >
        <Stack>
          <Text size="sm">
            {revoking?.recipientName} will immediately lose access to this file.
          </Text>
          {revoke.isError && (
            <Alert color="red" role="alert">
              {errorMessage(revoke.error)}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" disabled={revoke.isPending} onClick={() => setRevoking(null)}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={revoke.isPending}
              onClick={() => revoking && revoke.mutate(revoking.recipientId)}
            >
              Revoke access
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
