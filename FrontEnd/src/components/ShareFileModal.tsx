import { FilesContext } from '@/App';
import { Button, Modal, MultiSelect } from '@mantine/core';
import { useMutation, useQuery } from '@tanstack/react-query';
import axios from 'axios';
import React, { useContext, useState } from 'react';

interface UploadFileModalProps {
  opened: boolean;
  fileName: string;
  close: VoidFunction;
}

const ShareFileModal: React.FC<UploadFileModalProps> = ({ opened, close, fileName }) => {
  console.log(fileName);
  const { filesState } = useContext(FilesContext);
  const [selectedPeople, setSelectedPeople] = useState<string[]>([]);

  const allusersRequest = useQuery({
    queryKey: [filesState?.user],
    queryFn: () => axios.get('http://localhost:12000/all/users'),
  });

  const share = useMutation({
    mutationFn: (body: { fileName: string; allowed: string[] }) =>
      axios.post('http://localhost:12000/share', body),
    onError: (err) => {
      alert('share failed, try again');
    },
  });

  const allusers = allusersRequest.data?.data?.map((datum: any) => datum?.map ?? null) as {
    name: string;
    _id: string;
  }[];

  const selectedIds = selectedPeople.map(
    (username) => allusers.find((user) => user.name === username)?._id
  );

  return (
    <>
      <Modal opened={opened} onClose={close} title={'lets share our file'}>
        <MultiSelect
          data={allusers?.map((user) => user.name)}
          searchable
          placeholder="Select people"
          value={selectedPeople}
          onChange={setSelectedPeople}
          mb="md"
        />
        <Button
          onClick={() => {
            share.mutate({
              allowed: selectedIds.filter((id) => id) ? (selectedIds as string[]) : [],
              fileName,
            });
            close();
          }}
        >
          Share!
        </Button>
      </Modal>
    </>
  );
};

export { ShareFileModal };
