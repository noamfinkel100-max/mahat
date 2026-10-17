import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Container,
  Group,
  Loader,
  Modal,
  Paper,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core';
import {
  IconDownload,
  IconFile,
  IconInbox,
  IconLock,
  IconRefresh,
  IconSearch,
  IconShare,
  IconShieldCheck,
  IconTrash,
  IconUpload,
} from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate } from 'react-router-dom';
import {
  api,
  errorMessage,
  expirationLabel,
  FileList,
  formatSize,
  shareState,
  StoredFile,
} from '@/utils/api';
import { useSession } from '@/utils/session';
import { DownloadFileModal } from './DownloadFileModal';
import { ShareFileModal } from './ShareFileModal';
import { UploadFileModal } from './UploadFileModal';
import classes from './MyFiles.module.css';

export function MyFiles() {
  const { user } = useSession();
  const queryClient = useQueryClient();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [shareFile, setShareFile] = useState<StoredFile | null>(null);
  const [downloadFile, setDownloadFile] = useState<StoredFile | null>(null);
  const [deleteFile, setDeleteFile] = useState<StoredFile | null>(null);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const files = useQuery({
    queryKey: ['files'],
    queryFn: async () => (await api.get<FileList>('/files')).data,
    refetchInterval: 5000,
  });
  const remove = useMutation({
    mutationFn: (file: StoredFile) => api.delete(`/files/${file.fileId}`),
    onSuccess: async () => {
      setDeleteFile(null);
      setNotice('File deleted. Access has been removed for every recipient.');
      await queryClient.invalidateQueries({ queryKey: ['files'] });
      queryClient.removeQueries({ queryKey: ['shares', deleteFile?.fileId] });
    },
    onError: async () => {
      await queryClient.invalidateQueries({ queryKey: ['files'] });
    },
  });
  if ((files.error as { response?: { status?: number } })?.response?.status === 401)
    return <Navigate to="/login" replace />;
  const owned = files.data?.owned ?? [];
  const shared = files.data?.shared ?? [];
  const matches = (file: StoredFile) =>
    file.originalFileName.toLowerCase().includes(search.trim().toLowerCase());
  const empty = (isOwned: boolean) => (
    <Paper className={classes.emptyState} radius="lg" p="xl" withBorder>
      <ThemeIcon size={54} radius="xl" variant="light">
        {isOwned ? <IconLock size={25} /> : <IconInbox size={25} />}
      </ThemeIcon>
      <Text fw={700} mt="md">
        {search
          ? 'No matching files'
          : isOwned
            ? 'Your vault starts here'
            : 'Nothing shared with you yet'}
      </Text>
      <Text c="dimmed" size="sm" mt={5}>
        {search
          ? 'Try a different filename.'
          : isOwned
            ? 'Upload your first file and keep access on your terms.'
            : 'Files shared with your account will appear here.'}
      </Text>
      {isOwned && !search && (
        <Button mt="md" variant="light" onClick={() => setUploadOpen(true)}>
          Upload your first file
        </Button>
      )}
    </Paper>
  );
  const cards = (items: StoredFile[], isOwned: boolean) =>
    items.filter(matches).length ? (
      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
        {items.filter(matches).map((file) => {
          const state = file.share ? shareState(file.share) : 'Active';
          const available = file.status === 'ACTIVE' && (isOwned || state === 'Active');
          return (
            <Card
              key={file.fileId}
              data-testid="file-card"
              data-file-id={file.fileId}
              className={classes.fileCard}
              withBorder
              radius="lg"
              p="lg"
            >
              <Group justify="space-between" mb="md">
                <ThemeIcon className={classes.fileIcon} size={44} radius="md">
                  <IconFile size={24} />
                </ThemeIcon>
                <Badge variant="light" size="sm" color={available ? 'teal' : 'orange'}>
                  {file.status !== 'ACTIVE'
                    ? 'Deletion in progress'
                    : isOwned
                      ? 'Encrypted'
                      : state}
                </Badge>
              </Group>
              <Text className={classes.fileName} fw={700} title={file.originalFileName}>
                {file.originalFileName}
              </Text>
              <Text c="dimmed" size="xs" mt={5}>
                {formatSize(file.fileSize)} · {new Date(file.createdAt).toLocaleDateString()}
              </Text>
              {!isOwned && file.share && (
                <Box mt="md">
                  <Text size="sm" fw={600}>
                    {file.share.maxDownloads === null
                      ? 'Unlimited downloads'
                      : `${Math.max(0, file.share.maxDownloads - file.share.downloadCount)} downloads remaining`}
                  </Text>
                  <Text c="dimmed" size="xs" mt={4}>
                    {expirationLabel(file.share.expiresAt)}
                  </Text>
                </Box>
              )}
              <Group
                gap="xs"
                mt="lg"
                style={{ marginTop: 'auto', paddingTop: 'var(--mantine-spacing-lg)' }}
              >
                <Button
                  variant="light"
                  size="xs"
                  leftSection={<IconDownload size={15} />}
                  aria-label={`Download ${file.originalFileName}`}
                  disabled={!available}
                  onClick={() => setDownloadFile(file)}
                  style={{ flex: 1 }}
                >
                  Download
                </Button>
                {isOwned && (
                  <>
                    <Button
                      variant="subtle"
                      size="xs"
                      leftSection={<IconShare size={15} />}
                      aria-label={`Share ${file.originalFileName}`}
                      disabled={!available}
                      onClick={() => setShareFile(file)}
                    >
                      Share
                    </Button>
                    {file.status === 'DELETING' ? (
                      <Button
                        size="xs"
                        color="orange"
                        variant="light"
                        leftSection={<IconRefresh size={15} />}
                        aria-label={`Retry deletion ${file.originalFileName}`}
                        onClick={() => {
                          setDeleteFile(file);
                          remove.reset();
                        }}
                      >
                        Retry deletion
                      </Button>
                    ) : (
                      <Tooltip label="Delete for everyone">
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          aria-label={`Delete ${file.originalFileName}`}
                          onClick={() => {
                            setDeleteFile(file);
                            remove.reset();
                          }}
                        >
                          <IconTrash size={17} />
                        </ActionIcon>
                      </Tooltip>
                    )}
                  </>
                )}
              </Group>
            </Card>
          );
        })}
      </SimpleGrid>
    ) : (
      empty(isOwned)
    );

  return (
    <Box className={classes.page}>
      <Container size="xl" className={classes.container}>
        {notice && (
          <Alert color="teal" mb="lg" withCloseButton onClose={() => setNotice('')} role="status">
            {notice}
          </Alert>
        )}
        <Paper className={classes.welcomeCard} radius="xl" p={{ base: 'lg', sm: 'xl' }}>
          <div className={classes.welcomeGlow} />
          <Group className={classes.welcomeContent} justify="space-between" align="center" gap="xl">
            <Stack gap="sm">
              <Badge
                className={classes.eyebrow}
                leftSection={<IconShieldCheck size={14} />}
                variant="light"
                radius="xl"
                w="fit-content"
              >
                YOUR PRIVATE SPACE
              </Badge>
              <Title order={1} className={classes.greeting}>
                Your vault, <span>{user?.name}</span>.
              </Title>
              <Text className={classes.welcomeText}>
                Encrypted files. Thoughtful sharing. Complete control.
              </Text>
            </Stack>
            <Button
              size="md"
              radius="xl"
              leftSection={<IconUpload size={18} />}
              onClick={() => setUploadOpen(true)}
            >
              Upload a file
            </Button>
          </Group>
        </Paper>
        <SimpleGrid className={classes.stats} cols={{ base: 1, xs: 2 }} spacing="md">
          {[
            { count: owned.length, label: 'Files in your vault', icon: <IconLock size={21} /> },
            { count: shared.length, label: 'Shared with you', icon: <IconInbox size={21} /> },
          ].map((stat) => (
            <Paper key={stat.label} className={classes.statCard} radius="lg" p="md" withBorder>
              <Group wrap="nowrap">
                <ThemeIcon size={42} radius="md" variant="light">
                  {stat.icon}
                </ThemeIcon>
                <Stack gap={0}>
                  <Text className={classes.statValue}>{stat.count}</Text>
                  <Text size="sm" c="dimmed">
                    {stat.label}
                  </Text>
                </Stack>
              </Group>
            </Paper>
          ))}
        </SimpleGrid>
        <Paper className={classes.library} radius="xl" p="lg" withBorder>
          <Group className={classes.libraryHeader} justify="space-between" mb="lg">
            <Box>
              <Title order={2} className={classes.libraryTitle}>
                Your library
              </Title>
              <Text size="sm" c="dimmed" mt={4}>
                Access updates automatically every few seconds.
              </Text>
            </Box>
            <Group gap="xs" wrap="nowrap">
              <TextInput
                className={classes.search}
                leftSection={<IconSearch size={17} />}
                placeholder="Find a file"
                value={search}
                onChange={(event) => setSearch(event.currentTarget.value)}
                aria-label="Search files"
              />
              <ActionIcon
                size="lg"
                variant="default"
                aria-label="Refresh files"
                loading={files.isFetching}
                onClick={() => {
                  void files.refetch();
                }}
              >
                <IconRefresh size={18} />
              </ActionIcon>
            </Group>
          </Group>
          {files.isError && (
            <Alert color="red" mb="md" role="alert">
              {errorMessage(files.error)}
              <Button
                variant="subtle"
                color="red"
                onClick={() => {
                  void files.refetch();
                }}
                size="xs"
              >
                Retry
              </Button>
            </Alert>
          )}
          <Tabs defaultValue="mine" className={classes.tabs}>
            <Tabs.List>
              <Tabs.Tab value="mine" leftSection={<IconLock size={16} />}>
                My Files <span className={classes.tabCount}>{owned.length}</span>
              </Tabs.Tab>
              <Tabs.Tab value="shared" leftSection={<IconInbox size={16} />}>
                Shared With Me <span className={classes.tabCount}>{shared.length}</span>
              </Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="mine" pt="lg">
              {files.isPending ? (
                <Group justify="center" py="xl">
                  <Loader aria-label="Loading files" />
                </Group>
              ) : (
                cards(owned, true)
              )}
            </Tabs.Panel>
            <Tabs.Panel value="shared" pt="lg">
              {files.isPending ? (
                <Loader aria-label="Loading shared files" />
              ) : (
                cards(shared, false)
              )}
            </Tabs.Panel>
          </Tabs>
        </Paper>
        <UploadFileModal opened={uploadOpen} close={() => setUploadOpen(false)} />
        {shareFile && (
          <ShareFileModal
            key={shareFile.fileId}
            file={shareFile}
            close={() => setShareFile(null)}
          />
        )}
        {downloadFile && (
          <DownloadFileModal
            key={downloadFile.fileId}
            file={downloadFile}
            close={() => setDownloadFile(null)}
          />
        )}
        <Modal
          closeButtonProps={{ 'aria-label': 'Close dialog' }}
          opened={Boolean(deleteFile)}
          onClose={() => {
            if (!remove.isPending) setDeleteFile(null);
          }}
          title="Delete file everywhere?"
          centered
          closeOnClickOutside={!remove.isPending}
          closeOnEscape={!remove.isPending}
          withCloseButton={!remove.isPending}
        >
          <Stack>
            <Text size="sm">
              <Text component="span" fw={700}>
                {deleteFile?.originalFileName}
              </Text>{' '}
              will be permanently deleted from your vault and become unavailable to everyone it was
              shared with. This cannot be undone.
            </Text>
            {remove.isError && (
              <Alert color="red" role="alert">
                {errorMessage(remove.error)} Access is blocked once deletion begins. You can retry
                cleanup safely.
              </Alert>
            )}
            <Group justify="flex-end">
              <Button
                variant="default"
                disabled={remove.isPending}
                onClick={() => setDeleteFile(null)}
              >
                Cancel
              </Button>
              <Button
                color="red"
                loading={remove.isPending}
                leftSection={<IconTrash size={16} />}
                onClick={() => deleteFile && remove.mutate(deleteFile)}
              >
                Delete file
              </Button>
            </Group>
          </Stack>
        </Modal>
      </Container>
    </Box>
  );
}
