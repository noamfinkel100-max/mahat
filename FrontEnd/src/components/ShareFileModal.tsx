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

type UserSummary = {
  name: string;
  _id: string;
};

const ShareFileModal: React.FC<UploadFileModalProps> = ({ opened, close, fileName }) => {
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

  const userData = (allusersRequest.data?.data ?? []) as Array<{
    map?: UserSummary;
    name?: string;
    _id?: string;
  }>;
  const allusers: UserSummary[] = userData
    .map((datum) => {
      if (datum.map) return datum.map;
      if (datum.name && datum._id) return { name: datum.name, _id: datum._id };
      return null;
    })
    .filter((user): user is UserSummary => user !== null);

  const selectedIds = selectedPeople
    .map((username) => allusers.find((user) => user.name === username)?._id)
    .filter((id): id is string => Boolean(id));

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
              allowed: selectedIds,
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
