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
import { useMutation } from '@tanstack/react-query';
import axios from 'axios';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

type user = {
  name: string;
  password: string;
};

const SignUpPage = () => {
  const [userInput, setUserInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const navigate = useNavigate();

  const signUp = useMutation({
    mutationFn: (newUser: user) => {
      return axios.post('http://localhost:12000/sign/up', newUser);
    },
    onSuccess: (res) => {
      setSuccess('User created successfully, please log in!');
      setError('');
      setUserInput('');
      setPasswordInput('');
      setTimeout(() => {
        navigate('/');
      }, 2000);
    },
    onError: (err) => {
      setError('Failed to sign up, please try again.');
      setSuccess('');
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
        {success && (
          <Notification color="teal" onClose={() => setSuccess('')}>
            {success}
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
