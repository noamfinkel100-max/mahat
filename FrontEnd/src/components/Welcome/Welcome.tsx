import { Badge, Box, Group, Stack, Text, Title } from '@mantine/core';
import { IconLock, IconShieldCheck, IconUsers } from '@tabler/icons-react';
import { WelcomeButtons } from '@/components/WelcomeButtons';
import classes from './Welcome.module.css';

export function Welcome() {
  return (
    <Stack className={classes.copy} gap="xl">
      <Badge
        className={classes.eyebrow}
        leftSection={<IconShieldCheck size={14} />}
        variant="light"
        radius="xl"
        w="fit-content"
      >
        A QUIETER KIND OF SECURITY
      </Badge>
      <Box>
        <Title className={classes.title} order={1}>
          Your files.
          <br />
          <Text
            inherit
            component="span"
            variant="gradient"
            gradient={{ from: 'cyan.3', to: 'violet.3', deg: 100 }}
          >
            Protected.
          </Text>{' '}
          Shared.
        </Title>
        <Text className={classes.description} mt="lg">
          Keep important files in your own encrypted vault, then share access with the people you
          trust.
        </Text>
      </Box>
      <WelcomeButtons />
      <Group className={classes.assurances} gap="lg">
        <Group gap={7}>
          <IconLock size={16} />
          <Text size="sm">Encrypted files</Text>
        </Group>
        <Group gap={7}>
          <IconUsers size={16} />
          <Text size="sm">Sharing on your terms</Text>
        </Group>
      </Group>
    </Stack>
  );
}
