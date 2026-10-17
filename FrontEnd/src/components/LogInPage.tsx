import { FilesContext } from '@/App';
import { parseUserSession } from '@/utils/userSession';
import {
  Text,
  Input,
  Group,
  Box,
  Button,
  Container,
  Paper,
  Title,
  Space,
  Alert,
  PasswordInput,
  Flex,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import axios from 'axios';
import { useContext, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { IconAlertCircle, IconUser, IconLock } from '@tabler/icons-react';

type user = {
  name: string;
  password: string;
};

const LogInPage = () => {
  const [userInput, setUserInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [error, setError] = useState('');
  const { setFilesState } = useContext(FilesContext);
  const navigate = useNavigate();

  const login = useMutation({
    mutationFn: async (newUser: user) => {
      const response = await axios.post('http://localhost:12000/login', newUser);
      const session = parseUserSession(response.data, newUser.name);
      if (!session) {
        throw new Error('Login succeeded, but the server returned invalid account data.');
      }
      return session;
    },
    onSuccess: (session) => {
      setFilesState(session);
      navigate('/myfiles');
    },
    onError: (err) => {
      setError('Login failed. Please check your credentials and try again.');
    },
  });

  return (
    <div
      style={{
        backgroundImage: 'url(https://source.unsplash.com/random/1600x900)',
        backgroundSize: 'cover',
        minHeight: '100vh',
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
      }}
    >
      <Container size="xs" px="xs">
        <Paper
          shadow="lg"
          radius="md"
          p="xl"
          withBorder
          style={{ background: 'rgba(255, 255, 255, 0.9)' }}
        >
          <Title order={2} style={{ fontFamily: 'Roboto, sans-serif', alignItems: 'center' }}>
            Welcome Back!
          </Title>
          <Text style={{ alignItems: 'center' }} size="sm" color="dimmed" mb="xl">
            Please login to your account
          </Text>
          <Space h="md" />
          {error && (
            <Alert icon={<IconAlertCircle size={16} />} title="Error" color="red">
              {error}
            </Alert>
          )}
          <Group dir="column" grow>
            <Box>
              <Flex>
                <IconUser />
                <Text>Enter Name:</Text>
              </Flex>
              <Input
                value={userInput}
                onChange={(event) => setUserInput(event.currentTarget.value)}
                placeholder="myUserName"
              ></Input>
            </Box>
            <Box>
              <Flex>
                <IconLock /> <Text>Enter Password:</Text>
              </Flex>
              <PasswordInput
                value={passwordInput}
                onChange={(event) => setPasswordInput(event.currentTarget.value)}
                placeholder="myPassword"
              ></PasswordInput>
            </Box>
            <Button
              variant="gradient"
              gradient={{ from: 'teal', to: 'blue', deg: 60 }}
              onClick={() => login.mutate({ name: userInput, password: passwordInput })}
              fullWidth
              mt="md"
            >
              Log me In!
            </Button>
          </Group>
        </Paper>
      </Container>
    </div>
  );
};

export { LogInPage };
