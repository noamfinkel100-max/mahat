import {
  Button,
  Input,
  Modal,
  Text,
  Group,
  Notification,
  Divider,
  Title,
  Box,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useContext, useState } from 'react';
import { IconCheck, IconX, IconUpload } from '@tabler/icons-react';
import axios from 'axios';
import { FilesContext, filesStateType } from '@/App';

interface UploadFileModalProps {
  userId: string;
  opened: boolean;
  close: VoidFunction;
}

const UploadFileModal: React.FC<UploadFileModalProps> = ({ opened, close, userId }) => {
  const [file, setFile] = useState<File | null>(null);
  const { filesState, setFilesState } = useContext(FilesContext);

  const [fileName, setFileName] = useState('');
  const [code, setCode] = useState('');
  const [notification, setNotification] = useState<{
    message: string;
    type: 'success' | 'error' | '';
  }>({ message: '', type: '' });

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFile = event.target.files?.[0] || null;
    if (selectedFile) {
      setFile(selectedFile);
      setFileName(selectedFile.name);
    }
  };

  const uploadFile = useMutation({
    mutationFn: (formData: FormData) => {
      return axios.post('http://localhost:12000/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });
    },
    onSuccess: () => {
      setFilesState({
        ...filesState,
        myFiles: { ...filesState?.myFiles, [fileName]: code },
      } as filesStateType);
      setNotification({ message: 'File uploaded successfully!', type: 'success' });
      close();
    },
    onError: (err) => {
      console.error(err);
      setNotification({ message: 'Failed to upload the file, please try again', type: 'error' });
    },
  });

  const handleSubmit = () => {
    if (file && code) {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('code', code);
      formData.append('_id', userId);

      uploadFile.mutate(formData);
    } else {
      alert('Please select a file and enter the code.');
    }
  };

  return (
    <Modal opened={opened} onClose={close} title="Upload a File" size="lg" centered>
      <Box>
        {notification.message && (
          <Notification
            icon={notification.type === 'success' ? <IconCheck /> : <IconX />}
            color={notification.type}
            onClose={() => setNotification({ message: '', type: '' })}
          >
            {notification.message}
          </Notification>
        )}
        <Title order={4} mb="md">
          Code for Encryption
        </Title>
        <Input
          placeholder="Enter your code"
          value={code}
          onChange={(event) => setCode(event.target.value)}
          mb="md"
          required
        />
        <Title order={4} mb="md">
          Select People to Share With
        </Title>

        <Divider mb="md" />
        <Title order={4} mb="md">
          Select File to Upload
        </Title>
        <Input type="file" onChange={handleFileChange} mb="md" />
        {fileName && <Text>Selected file: {fileName}</Text>}
        <Group style={{ alignItems: 'end' }} mt="xl">
          <Button onClick={handleSubmit} disabled={!file || !code}>
            <IconUpload />
            Upload
          </Button>
        </Group>
      </Box>
    </Modal>
  );
};

export { UploadFileModal };
