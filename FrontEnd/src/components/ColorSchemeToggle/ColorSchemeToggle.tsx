import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';

export function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computedColorScheme = useComputedColorScheme('light');

  return (
    <ActionIcon
      variant="default"
      size="lg"
      radius="xl"
      onClick={() => setColorScheme(computedColorScheme === 'dark' ? 'light' : 'dark')}
      aria-label={`Switch to ${computedColorScheme === 'dark' ? 'light' : 'dark'} mode`}
      title={`Switch to ${computedColorScheme === 'dark' ? 'light' : 'dark'} mode`}
    >
      {computedColorScheme === 'dark' ? <IconSun size={18} /> : <IconMoon size={18} />}
    </ActionIcon>
  );
}
