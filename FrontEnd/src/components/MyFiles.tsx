import { FilesContext, filesStateType } from '@/App';
import {
  Group,
  Card,
  Text,
  Button,
  Grid,
  Title,
  Space,
  Container,
  Badge,
  Notification,
} from '@mantine/core';
import { useContext, useMemo, useState } from 'react';
import { UploadFileModal } from './UploadFileModal';
import { DownloadFileModal } from './DownloadFileModal';
import {
  IconCheck,
  IconX,
  IconUpload,
  IconShare,
  IconTrash,
  IconDownload,
} from '@tabler/icons-react';
import { useMutation, useQuery } from '@tanstack/react-query';
import axios from 'axios';
import { ShareFileModal } from './ShareFileModal';

const MyFiles = () => {
  const { filesState, setFilesState } = useContext(FilesContext);
  const [isShareFileOpen, setIsShareFileOpen] = useState(false);
  const [isUploadFileOpen, setIsUploadFileOpen] = useState(false);
  const [isDownloadFileOpen, setIsDownloadFileOpen] = useState(false);
  const [selectedFileToShare, setSelcetedFileToShare] = useState<string>('');
  const [selectedFileToDelete, setSelectedFileToDelete] = useState<string>('');
  const [selectedFileToDownload, setSelectedFileToDownload] = useState<string>('');
  const [notification, setNotification] = useState<{
    message: string;
    type: 'success' | 'error' | '';
  }>({ message: '', type: '' });

  const allusersRequest = useQuery({
    queryKey: [filesState?.user],
    queryFn: () => axios.get('http://localhost:12000/all/users'),
  });

  const deleteFileRequest = useMutation({
    mutationFn: (fileToDelete: { _id: string; fileName: string }) => {
      return axios.post('http://localhost:12000/delete', fileToDelete);
    },
    onSuccess: (res) => {
      const newMyFiles = filesState?.myFiles;
      if (newMyFiles) delete newMyFiles[selectedFileToDelete];

      setFilesState({ ...filesState, myFiles: newMyFiles } as filesStateType);
    },
    onError: (err) => {
      console.log('something went wrong');
    },
  });

  const currentUser = useMemo(() => {
    return allusersRequest.data?.data.find(
      (user: { map: { _id: string; name: string } }) => user.map.name === filesState?.user
    )?.map;
  }, [allusersRequest.data?.data]);

  return (
    <div>
      <Container size="lg" px="md" mt="xl">
        {notification.message && (
          <Notification
            icon={notification.type === 'success' ? <IconCheck /> : <IconX />}
            color={notification.type}
            onClose={() => setNotification({ message: '', type: '' })}
          >
            {notification.message}
          </Notification>
        )}
        <Group style={{ alignItems: 'center' }}>
          <Title order={2} style={{ alignItems: 'center' }} variant="gradient">
            File Management
          </Title>
        </Group>
        <Space h="md" />
        <Grid>
          <Grid.Col span={6}>
            <Title order={3} variant="gradient" mb="md">
              My Files
            </Title>
            {filesState?.myFiles &&
              Object.entries(filesState?.myFiles as Record<string, string>).map(([key, value]) => (
                <Card
                  shadow="md"
                  padding="lg"
                  radius="md"
                  withBorder
                  mb="md"
                  key={value}
                  style={{ maxWidth: 320, margin: 'auto' }}
                >
                  <Group dir="column" p="xs">
                    <Text size="lg" w={500}>
                      {key}
                    </Text>
                    <Badge color="teal" variant="light">
                      {value}
                    </Badge>
                    <Group p="xs">
                      <Button
                        variant="outline"
                        color="blue"
                        onClick={() => {
                          setIsShareFileOpen(true);
                          setSelcetedFileToShare(key);
                        }}
                      >
                        <IconShare />
                        Share
                      </Button>
                      <Button
                        variant="outline"
                        color="red"
                        onClick={() => {
                          setSelectedFileToDelete(key);
                          deleteFileRequest.mutate({ _id: currentUser._id, fileName: key });
                        }}
                      >
                        <IconTrash />
                        Delete
                      </Button>
                    </Group>
                  </Group>
                </Card>
              ))}
          </Grid.Col>
          <Grid.Col span={6}>
            <Title order={3} variant="gradient" mb="md">
              Shared With Me
            </Title>
            {filesState?.sharedWithMe &&
              filesState?.sharedWithMe.map((fileName, index) => (
                <Card
                  shadow="md"
                  padding="lg"
                  radius="md"
                  withBorder
                  mb="md"
                  key={index}
                  style={{ maxWidth: 320, margin: 'auto' }}
                >
                  <Group dir="column" m="xs">
                    <Text w={500} size="lg">
                      {fileName}
                    </Text>
                    <Button
                      variant="outline"
                      color="green"
                      onClick={() => {
                        setIsDownloadFileOpen(true);
                        setSelectedFileToDownload(fileName);
                      }}
                    >
                      <IconDownload />
                      Download
                    </Button>
                  </Group>
                </Card>
              ))}
          </Grid.Col>
          <Grid.Col span={12}>
            <Group style={{ alignItems: 'center' }}>
              <Button
                variant="gradient"
                gradient={{ from: 'orange', to: 'red' }}
                onClick={() => {
                  setIsUploadFileOpen(true);
                }}
              >
                <IconUpload />
                Upload new file
              </Button>
            </Group>
          </Grid.Col>
        </Grid>
        <UploadFileModal
          opened={isUploadFileOpen}
          userId={currentUser?._id}
          close={() => {
            setIsUploadFileOpen(false);
          }}
        />
        <DownloadFileModal
          fileName={selectedFileToDownload}
          opened={isDownloadFileOpen}
          close={() => {
            setIsDownloadFileOpen(false);
          }}
        />
        <ShareFileModal
          fileName={selectedFileToShare}
          opened={isShareFileOpen}
          close={() => {
            setIsShareFileOpen(false);
          }}
        />
      </Container>
    </div>
  );
};

export { MyFiles };
