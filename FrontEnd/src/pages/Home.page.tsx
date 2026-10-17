import {
  Badge,
  Box,
  Card,
  Container,
  Group,
  Paper,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
} from '@mantine/core';
import {
  IconArrowUpRight,
  IconFile,
  IconLock,
  IconShieldCheck,
  IconUsers,
} from '@tabler/icons-react';
import { Welcome } from '../components/Welcome/Welcome';
import classes from './Home.page.module.css';

const features = [
  {
    icon: <IconLock size={21} />,
    title: 'Private by default',
    description: 'Files are encrypted before they reach object storage.',
    color: 'violet',
  },
  {
    icon: <IconLock size={21} />,
    title: 'Your code, your key',
    description: 'Choose the decryption code for each file you upload.',
    color: 'cyan',
  },
  {
    icon: <IconUsers size={21} />,
    title: 'Share with people',
    description: 'Give people access to a file without sending another copy.',
    color: 'pink',
  },
];

export function HomePage() {
  return (
    <Box className={classes.page}>
      <Container size="xl" className={classes.container}>
        <section className={classes.hero}>
          <Welcome />
          <Paper className={classes.visual} radius="xl" p="xl">
            <div className={classes.glow} />
            <Group justify="space-between" align="center" className={classes.visualHeader}>
              <Group gap="xs">
                <ThemeIcon variant="light" color="violet" radius="xl" size="sm">
                  <IconShieldCheck size={14} />
                </ThemeIcon>
                <Text size="xs" fw={700} c="dimmed">
                  YOUR PRIVATE VAULT
                </Text>
              </Group>
              <Badge
                variant="light"
                color="teal"
                leftSection={<span className={classes.statusDot} />}
              >
                Protected
              </Badge>
            </Group>

            <div className={classes.lockStage}>
              <div className={classes.orbit} />
              <div className={classes.orbitInner} />
              <div className={classes.lockIcon}>
                <IconShieldCheck size={58} stroke={1.3} />
              </div>
            </div>

            <Card className={classes.fileCard} radius="lg" p="md" withBorder>
              <Group justify="space-between" wrap="nowrap">
                <Group gap="sm" wrap="nowrap">
                  <ThemeIcon size={42} radius="md" variant="light" color="violet">
                    <IconFile size={22} />
                  </ThemeIcon>
                  <Stack gap={2}>
                    <Text size="sm" fw={700} lineClamp={1}>
                      important-document.pdf
                    </Text>
                    <Text size="xs" c="dimmed">
                      Encrypted · Ready to share
                    </Text>
                  </Stack>
                </Group>
                <ThemeIcon variant="light" color="teal" radius="xl">
                  <IconLock size={16} />
                </ThemeIcon>
              </Group>
            </Card>
            <Group className={classes.visualFooter} gap={8}>
              <Text size="xs" c="dimmed">
                A little peace of mind, built in.
              </Text>
              <IconArrowUpRight size={15} />
            </Group>
          </Paper>
        </section>

        <section className={classes.features}>
          <Group justify="space-between" align="end" mb="lg">
            <Box>
              <Text className={classes.sectionEyebrow}>MADE FOR YOUR FILES</Text>
              <Text className={classes.sectionTitle}>Simple to use. Thoughtful by design.</Text>
            </Box>
            <Text className={classes.sectionAside} visibleFrom="sm">
              Keep control of what you share.
            </Text>
          </Group>
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
            {features.map((feature) => (
              <Card key={feature.title} className={classes.featureCard} radius="lg" p="lg">
                <ThemeIcon size={46} radius="md" variant="light" color={feature.color}>
                  {feature.icon}
                </ThemeIcon>
                <Text fw={700} mt="md">
                  {feature.title}
                </Text>
                <Text size="sm" c="dimmed" mt={6} lh={1.6}>
                  {feature.description}
                </Text>
              </Card>
            ))}
          </SimpleGrid>
        </section>
      </Container>
    </Box>
  );
}
