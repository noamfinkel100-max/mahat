import { Button, Input, Modal, Text, Notification } from '@mantine/core';
import { useMutation, useQuery } from '@tanstack/react-query';
import axios from 'axios';
import React, { useState } from 'react';
import { IconCheck, IconX } from '@tabler/icons-react';

interface DownloadFileModalProps {
  opened: boolean;
  close: VoidFunction;
  fileName: string;
}

const DownloadFileModal: React.FC<DownloadFileModalProps> = ({ opened, close, fileName }) => {
  const [decryptionCode, setDecryptionCode] = useState('');
  const [notification, setNotification] = useState<{
    message: string;
    type: 'success' | 'error' | '';
  }>({ message: '', type: '' });
  const [isLoading, setIsLoading] = useState(false);

  const downloadFile = useMutation({
    mutationFn: (body: { fileName: string; code: string }) => {
      setIsLoading(true);
      return axios.post('http://localhost:12000/download', body, {
        headers: {
          'Content-Type': 'application/json', // Change to application/json if you're sending JSON
        },
        responseType: 'blob', // Move responseType out of headers
      });
    },
    onSuccess: (res) => {
      const url = window.URL.createObjectURL(new Blob([res.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', fileName);
      document.body.appendChild(link);
      link.click();
      link.remove();
      setNotification({ message: 'File downloaded successfully!', type: 'success' });
      setIsLoading(false);
      close();
    },
    onError: (err) => {
      console.error(err);
      setNotification({
        message:
          'Download failed. Check the decryption code or re-upload this file using the current app version.',
        type: 'error',
      });
      setIsLoading(false);
    },
  });

  return (
    <Modal opened={opened} onClose={close} title={`Let's download ${fileName}`}>
      <Text>Code for decryption:</Text>
      <Input
        placeholder="Enter your decryption code"
        mb="xl"
        value={decryptionCode}
        onChange={(event) => setDecryptionCode(event.currentTarget.value)}
      />
      {notification.message && (
        <Notification
          icon={notification.type === 'success' ? <IconCheck /> : <IconX />}
          color={notification.type}
          onClose={() => setNotification({ message: '', type: '' })}
        >
          {notification.message}
        </Notification>
      )}
      <Button
        onClick={() => {
          downloadFile.mutate({ fileName, code: decryptionCode });
        }}
        disabled={isLoading}
      >
        {isLoading ? 'Downloading...' : 'Download!'}
      </Button>
    </Modal>
  );
};

export { DownloadFileModal };
