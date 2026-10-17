import { FilesContext } from '@/App';
import {
  ActionIcon,
  Badge,
  Box,
  Button,
  Card,
  Container,
  Group,
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
  IconCheck,
  IconDownload,
  IconFile,
  IconInbox,
  IconLock,
  IconSearch,
  IconShare,
  IconShieldCheck,
  IconTrash,
  IconUpload,
  IconUsers,
  IconX,
} from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import axios from 'axios';
import { useContext, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';
import type { ReactNode } from 'react';
import { DownloadFileModal } from './DownloadFileModal';
import { ShareFileModal } from './ShareFileModal';
import { UploadFileModal } from './UploadFileModal';
import classes from './MyFiles.module.css';

type Notice = {
  message: string;
  type: 'success' | 'error';
};

const MyFiles = () => {
  const { filesState, setFilesState } = useContext(FilesContext);
  const [isShareFileOpen, setIsShareFileOpen] = useState(false);
  const [isUploadFileOpen, setIsUploadFileOpen] = useState(false);
  const [isDownloadFileOpen, setIsDownloadFileOpen] = useState(false);
  const [selectedFileToShare, setSelectedFileToShare] = useState('');
  const [selectedFileToDownload, setSelectedFileToDownload] = useState('');
  const [search, setSearch] = useState('');
  const [notification, setNotification] = useState<Notice>();

  const myFiles = Object.entries(filesState?.myFiles ?? {});
  const sharedFiles = filesState?.sharedWithMe ?? [];
  const matchingMyFiles = useMemo(
    () => myFiles.filter(([name]) => name.toLowerCase().includes(search.trim().toLowerCase())),
    [filesState?.myFiles, search]
  );
  const matchingSharedFiles = useMemo(
    () => sharedFiles.filter((name) => name.toLowerCase().includes(search.trim().toLowerCase())),
    [filesState?.sharedWithMe, search]
  );

  const deleteFileRequest = useMutation({
    mutationFn: (fileName: string) =>
      axios.post('http://localhost:12000/delete', {
        _id: filesState?.userId,
        fileName,
      }),
    onSuccess: (_response, fileName) => {
      setFilesState((current) =>
        current
          ? {
              ...current,
              myFiles: Object.fromEntries(
                Object.entries(current.myFiles).filter(([name]) => name !== fileName)
              ),
            }
          : current
      );
      setNotification({ message: 'File removed from your vault.', type: 'success' });
    },
    onError: () => {
      setNotification({ message: 'Could not delete the file. Please try again.', type: 'error' });
    },
  });

  if (!filesState) {
    return <Navigate to="/login" replace />;
  }

  const openShare = (fileName: string) => {
    setSelectedFileToShare(fileName);
    setIsShareFileOpen(true);
  };

  const openDownload = (fileName: string) => {
    setSelectedFileToDownload(fileName);
    setIsDownloadFileOpen(true);
  };

  const emptyState = (title: string, message: string, icon: ReactNode) => (
    <Paper className={classes.emptyState} radius="lg" p="xl" withBorder>
      <ThemeIcon size={54} radius="xl" variant="light" color="violet">
        {icon}
      </ThemeIcon>
      <Text fw={700} mt="md">
        {title}
      </Text>
      <Text c="dimmed" size="sm" mt={5}>
        {message}
      </Text>
    </Paper>
  );

  return (
    <Box className={classes.page}>
      <Container size="xl" className={classes.container}>
        {notification && (
          <div className={classes.notification}>
            <Paper
              className={classes.notice}
              p="sm"
              radius="md"
              withBorder
              data-type={notification.type}
            >
              <Group gap="xs">
                {notification.type === 'success' ? <IconCheck size={18} /> : <IconX size={18} />}
                <Text size="sm" fw={600}>
                  {notification.message}
                </Text>
                <ActionIcon
                  variant="subtle"
                  color="gray"
                  size="sm"
                  aria-label="Dismiss notification"
                  onClick={() => setNotification(undefined)}
                >
                  <IconX size={15} />
                </ActionIcon>
              </Group>
            </Paper>
          </div>
        )}

        <Paper className={classes.welcomeCard} radius="xl" p="xl">
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
                Your vault, <span>{filesState.user}</span>.
              </Title>
              <Text className={classes.welcomeText}>
                Your files stay encrypted. You decide who gets access.
              </Text>
            </Stack>
            <Button
              size="md"
              radius="xl"
              leftSection={<IconUpload size={18} />}
              onClick={() => setIsUploadFileOpen(true)}
            >
              Upload a file
            </Button>
          </Group>
        </Paper>

        <SimpleGrid className={classes.stats} cols={{ base: 1, xs: 2 }} spacing="md">
          <Paper className={classes.statCard} radius="lg" p="md" withBorder>
            <Group wrap="nowrap">
              <ThemeIcon size={44} radius="md" variant="light" color="violet">
                <IconLock size={21} />
              </ThemeIcon>
              <Stack gap={0}>
                <Text className={classes.statValue}>{myFiles.length}</Text>
                <Text size="sm" c="dimmed">
                  Files in your vault
                </Text>
              </Stack>
            </Group>
          </Paper>
          <Paper className={classes.statCard} radius="lg" p="md" withBorder>
            <Group wrap="nowrap">
              <ThemeIcon size={44} radius="md" variant="light" color="cyan">
                <IconInbox size={21} />
              </ThemeIcon>
              <Stack gap={0}>
                <Text className={classes.statValue}>{sharedFiles.length}</Text>
                <Text size="sm" c="dimmed">
                  Shared with you
                </Text>
              </Stack>
            </Group>
          </Paper>
        </SimpleGrid>

        <Paper className={classes.library} radius="xl" p="lg" withBorder>
          <Group className={classes.libraryHeader} justify="space-between" align="center" mb="lg">
            <Box>
              <Title order={2} className={classes.libraryTitle}>
                Your library
              </Title>
              <Text size="sm" c="dimmed" mt={4}>
                Manage your files and the files shared with you.
              </Text>
            </Box>
            <TextInput
              className={classes.search}
              leftSection={<IconSearch size={17} />}
              placeholder="Find a file"
              value={search}
              onChange={(event) => setSearch(event.currentTarget.value)}
              aria-label="Search files"
            />
          </Group>

          <Tabs defaultValue="mine" className={classes.tabs} color="violet">
            <Tabs.List>
              <Tabs.Tab value="mine" leftSection={<IconLock size={16} />}>
                My files <span className={classes.tabCount}>{myFiles.length}</span>
              </Tabs.Tab>
              <Tabs.Tab value="shared" leftSection={<IconInbox size={16} />}>
                Shared with me <span className={classes.tabCount}>{sharedFiles.length}</span>
              </Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel value="mine" pt="lg">
              {matchingMyFiles.length === 0 ? (
                emptyState(
                  search ? 'No matching files' : 'Your vault is ready',
                  search
                    ? 'Try a different search term.'
                    : 'Upload a file to encrypt it and keep it close at hand.',
                  <IconLock size={25} />
                )
              ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                  {matchingMyFiles.map(([fileName]) => (
                    <Card key={fileName} className={classes.fileCard} radius="lg" p="md" withBorder>
                      <Group justify="space-between" align="flex-start" wrap="nowrap">
                        <ThemeIcon
                          className={classes.fileIcon}
                          size={46}
                          radius="md"
                          variant="light"
                        >
                          <IconFile size={23} />
                        </ThemeIcon>
                        <Badge
                          className={classes.encryptedBadge}
                          variant="light"
                          color="teal"
                          leftSection={<IconLock size={12} />}
                        >
                          Encrypted
                        </Badge>
                      </Group>
                      <Text
                        className={classes.fileName}
                        fw={700}
                        mt="md"
                        lineClamp={2}
                        title={fileName}
                      >
                        {fileName}
                      </Text>
                      <Text c="dimmed" size="xs" mt={4}>
                        Stored privately in your vault
                      </Text>
                      <Group className={classes.fileActions} gap="xs" mt="lg">
                        <Button
                          className={classes.shareButton}
                          variant="light"
                          color="violet"
                          leftSection={<IconShare size={16} />}
                          onClick={() => openShare(fileName)}
                        >
                          Share
                        </Button>
                        <Tooltip label="Delete file">
                          <ActionIcon
                            variant="light"
                            color="red"
                            size={36}
                            radius="md"
                            aria-label={`Delete ${fileName}`}
                            loading={deleteFileRequest.isPending}
                            onClick={() => deleteFileRequest.mutate(fileName)}
                          >
                            <IconTrash size={17} />
                          </ActionIcon>
                        </Tooltip>
                      </Group>
                    </Card>
                  ))}
                </SimpleGrid>
              )}
            </Tabs.Panel>

            <Tabs.Panel value="shared" pt="lg">
              {matchingSharedFiles.length === 0 ? (
                emptyState(
                  search ? 'No matching files' : 'Nothing shared with you yet',
                  search
                    ? 'Try a different search term.'
                    : 'When someone shares a file with you, it will appear here.',
                  <IconInbox size={25} />
                )
              ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                  {matchingSharedFiles.map((fileName, index) => (
                    <Card
                      key={`${fileName}-${index}`}
                      className={classes.fileCard}
                      radius="lg"
                      p="md"
                      withBorder
                    >
                      <Group justify="space-between" align="flex-start" wrap="nowrap">
                        <ThemeIcon
                          className={classes.fileIcon}
                          size={46}
                          radius="md"
                          variant="light"
                          color="cyan"
                        >
                          <IconFile size={23} />
                        </ThemeIcon>
                        <Badge variant="light" color="cyan" leftSection={<IconUsers size={12} />}>
                          Shared
                        </Badge>
                      </Group>
                      <Text
                        className={classes.fileName}
                        fw={700}
                        mt="md"
                        lineClamp={2}
                        title={fileName}
                      >
                        {fileName}
                      </Text>
                      <Text c="dimmed" size="xs" mt={4}>
                        Enter the decryption code to open this file.
                      </Text>
                      <Button
                        className={classes.downloadButton}
                        variant="light"
                        color="cyan"
                        leftSection={<IconDownload size={17} />}
                        onClick={() => openDownload(fileName)}
                        mt="lg"
                        fullWidth
                      >
                        Download securely
                      </Button>
                    </Card>
                  ))}
                </SimpleGrid>
              )}
            </Tabs.Panel>
          </Tabs>
        </Paper>

        <UploadFileModal
          opened={isUploadFileOpen}
          userId={filesState.userId}
          close={() => setIsUploadFileOpen(false)}
        />
        <DownloadFileModal
          fileName={selectedFileToDownload}
          opened={isDownloadFileOpen}
          close={() => setIsDownloadFileOpen(false)}
        />
        <ShareFileModal
          fileName={selectedFileToShare}
          opened={isShareFileOpen}
          close={() => setIsShareFileOpen(false)}
        />
      </Container>
    </Box>
  );
};

export { MyFiles };
