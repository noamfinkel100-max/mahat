import { Button, Group } from '@mantine/core';
import { IconArrowUpRight, IconFolder } from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { useSession } from '@/utils/session';

const WelcomeButtons = () => {
  const { user } = useSession();

  return (
    <Group gap="sm">
      {user ? (
        <Button
          component={Link}
          to="/myfiles"
          size="lg"
          radius="xl"
          rightSection={<IconFolder size={18} />}
        >
          Open my vault
        </Button>
      ) : (
        <>
          <Button
            component={Link}
            to="/signup"
            size="lg"
            radius="xl"
            rightSection={<IconArrowUpRight size={18} />}
          >
            Create your vault
          </Button>
          <Button component={Link} to="/login" size="lg" radius="xl" variant="subtle">
            Sign in
          </Button>
        </>
      )}
    </Group>
  );
};

export { WelcomeButtons };
