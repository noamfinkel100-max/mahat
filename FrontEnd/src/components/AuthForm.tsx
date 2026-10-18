import { useState } from 'react';
import {
  Alert,
  Anchor,
  Button,
  Container,
  Paper,
  PasswordInput,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { IconAlertCircle, IconLock, IconShieldLock, IconUser } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { api, errorMessage, User } from '@/utils/api';
import { useSession } from '@/utils/session';
import { parseUserSession } from '@/utils/userSession';

export function AuthForm({ register = false }: { register?: boolean }) {
  const { user } = useSession();
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [validation, setValidation] = useState('');
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const request = useMutation({
    mutationFn: async () => {
      const response = await api.post<User>(register ? '/auth/register' : '/auth/login', {
        name: name.trim(),
        password,
      });
      const account = parseUserSession(response.data);
      if (!account)
        throw new Error('The server returned an invalid session. Please try logging in again.');
      return account;
    },
    onSuccess: async (account) => {
      setPassword('');
      await queryClient.cancelQueries({ queryKey: ['session'] });
      queryClient.removeQueries({
        predicate: (query) => ['files', 'shares', 'users'].includes(String(query.queryKey[0])),
      });
      queryClient.setQueryData(['session'], account);
      navigate('/myfiles', { replace: true });
    },
  });
  if (user) return <Navigate to="/myfiles" replace />;
  return (
    <Container size={460} py={{ base: 40, sm: 72 }}>
      <Paper p={{ base: 'lg', sm: 'xl' }} radius="xl" withBorder shadow="sm">
        <ThemeIcon size={52} radius="lg" variant="light" color="violet" mb="lg">
          <IconShieldLock size={28} />
        </ThemeIcon>
        <Title order={1} size="h2">
          {register ? 'Create your account' : 'Welcome back'}
        </Title>
        <Text c="dimmed" size="sm" mt="xs" mb="xl">
          {register
            ? 'A private place for the files that matter.'
            : 'Log in to your encrypted file vault.'}
        </Text>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!name.trim() || !password) {
              setValidation('Enter your username and password.');
              return;
            }
            if (register && name.trim().length < 3) {
              setValidation('Use a username with 3 to 80 characters.');
              return;
            }
            if (register && password.length < 12) {
              setValidation('Use a password with at least 12 characters.');
              return;
            }
            setValidation('');
            request.mutate();
          }}
          noValidate
        >
          <Stack gap="md">
            {(validation || request.isError) && (
              <Alert color="red" icon={<IconAlertCircle size={18} />} role="alert">
                {validation || errorMessage(request.error)}
              </Alert>
            )}
            <TextInput
              label="Username"
              placeholder="Your username"
              leftSection={<IconUser size={17} />}
              autoComplete="username"
              required
              value={name}
              maxLength={80}
              onChange={(event) => setName(event.currentTarget.value)}
            />
            <PasswordInput
              visibilityToggleButtonProps={{ 'aria-label': 'Toggle password visibility' }}
              label="Password"
              placeholder={register ? 'At least 12 characters' : 'Your password'}
              description={
                register ? 'Use a unique password with at least 12 characters.' : undefined
              }
              leftSection={<IconLock size={17} />}
              autoComplete={register ? 'new-password' : 'current-password'}
              required
              value={password}
              maxLength={256}
              onChange={(event) => setPassword(event.currentTarget.value)}
            />
            <Button type="submit" fullWidth loading={request.isPending} radius="md" mt="sm">
              {register ? 'Create account' : 'Log in'}
            </Button>
          </Stack>
        </form>
        <Text size="sm" c="dimmed" ta="center" mt="xl">
          {register ? 'Already have an account? ' : 'New to CypherNod? '}
          <Anchor component={Link} to={register ? '/login' : '/signup'}>
            {register ? 'Log in' : 'Create an account'}
          </Anchor>
        </Text>
      </Paper>
    </Container>
  );
}
