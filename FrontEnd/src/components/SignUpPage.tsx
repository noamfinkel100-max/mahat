import {
  Text,
  TextInput,
  Group,
  Box,
  Button,
  Loader,
  Paper,
  Title,
  Space,
  Container,
  Notification,
} from '@mantine/core';
import { FilesContext } from '@/App';
import { parseUserSession } from '@/utils/userSession';
import { useMutation } from '@tanstack/react-query';
import axios from 'axios';
import { useContext, useState } from 'react';
import { useNavigate } from 'react-router-dom';

type user = {
  name: string;
  password: string;
};

const SignUpPage = () => {
  const [userInput, setUserInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [error, setError] = useState('');
  const { setFilesState } = useContext(FilesContext);

  const navigate = useNavigate();

  const signUp = useMutation({
    mutationFn: async (newUser: user) => {
      const signupResponse = await axios.post('http://localhost:12000/sign/up', newUser);
      const signupState = parseUserSession(signupResponse.data, newUser.name);
      if (signupState) {
        return signupState;
      }

      const loginResponse = await axios.post('http://localhost:12000/login', newUser);
      let authenticatedState = parseUserSession(loginResponse.data, newUser.name);

      if (!authenticatedState) {
        const usersResponse = await axios.get<
          Array<{ _id?: string; name?: string; map?: { _id?: string; name?: string } }>
        >('http://localhost:12000/all/users');
        const matchingUser = usersResponse.data
          .map((entry) => entry.map ?? entry)
          .find((entry) => entry.name === newUser.name && entry._id);

        if (matchingUser?._id) {
          authenticatedState = {
            userId: matchingUser._id,
            user: newUser.name,
            myFiles: {},
            sharedWithMe: [],
          };
        }
      }

      if (!authenticatedState) {
        throw new Error('Account created, but sign-in could not be completed. Please log in.');
      }

      return authenticatedState;
    },
    onSuccess: (authenticatedState) => {
      setFilesState(authenticatedState);
      setError('');
      navigate('/myfiles', { replace: true });
    },
    onError: (err: unknown) => {
      const message =
        axios.isAxiosError<{ message?: string }>(err) && err.response?.data?.message
          ? err.response.data.message
          : err instanceof Error
            ? err.message
            : 'Failed to sign up, please try again.';
      setError(message);
    },
  });

  return (
    <Container size="xs" px="xs">
      <Paper withBorder shadow="md" p="xl" mt="xl" radius="md">
        <Title order={2} style={{ alignItems: 'center' }} mb="md">
          Sign Up
        </Title>
        <Space h="md" />
        {error && (
          <Notification color="red" onClose={() => setError('')}>
            {error}
          </Notification>
        )}
        <Space h="md" />
        <Group dir="column" grow>
          <Box>
            <TextInput
              label="Enter Name:"
              value={userInput}
              onChange={(event) => setUserInput(event.currentTarget.value)}
              placeholder="myUserName"
              required
            />
          </Box>
          <Box>
            <TextInput
              label="Enter Password:"
              value={passwordInput}
              onChange={(event) => setPasswordInput(event.currentTarget.value)}
              placeholder="myPassword"
              type="password"
              required
            />
          </Box>
          <Button
            variant="gradient"
            gradient={{ from: 'teal', to: 'blue', deg: 60 }}
            onClick={() => {
              signUp.mutate({ name: userInput, password: passwordInput });
            }}
            disabled={signUp.status === 'pending'}
          >
            Sign Me Up!
          </Button>
          {signUp.status === 'pending' && <Loader />}
        </Group>
      </Paper>
    </Container>
  );
};

export { SignUpPage };
