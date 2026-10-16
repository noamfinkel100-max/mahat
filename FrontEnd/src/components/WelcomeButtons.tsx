import { Button, Group } from '@mantine/core';
import { useNavigate } from 'react-router-dom';

const WelcomeButtons = () => {
  const nevigate = useNavigate();
  return (
    <>
      <Group justify="center" mt="xl">
        <Button onClick={() => nevigate('/signup')}>Sign Up</Button>
        <Button onClick={() => nevigate('/login')}>Log In</Button>
      </Group>
    </>
  );
};

export { WelcomeButtons };
