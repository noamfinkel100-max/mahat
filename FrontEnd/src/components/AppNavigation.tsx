import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/utils/api';
import { useSession } from '@/utils/session';
import { Anchor, Box, Burger, Button, Container, Drawer, Group, Text } from '@mantine/core';
import {
  IconFolder,
  IconHome,
  IconLogout,
  IconShieldLock,
  IconUserPlus,
} from '@tabler/icons-react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { ColorSchemeToggle } from './ColorSchemeToggle/ColorSchemeToggle';
import classes from './AppNavigation.module.css';

export function AppNavigation() {
  const { user } = useSession();
  const queryClient = useQueryClient();
  const logoutRequest = useMutation({
    mutationFn: () => api.post('/auth/logout'),
    onSuccess: async () => {
      await queryClient.cancelQueries({ queryKey: ['session'] });
      queryClient.removeQueries({
        predicate: (query) => ['files', 'shares', 'users'].includes(String(query.queryKey[0])),
      });
      queryClient.setQueryData(['session'], null);
      closeDrawer();
      navigate('/');
    },
  });
  const [drawerOpened, setDrawerOpened] = useState(false);
  const navigate = useNavigate();
  const isLoggedIn = Boolean(user);

  const closeDrawer = () => setDrawerOpened(false);
  const logout = () => logoutRequest.mutate();

  const links = [
    { to: '/', label: 'Home', icon: <IconHome size={18} />, end: true },
    ...(isLoggedIn
      ? [{ to: '/myfiles', label: 'My files', icon: <IconFolder size={18} />, end: false }]
      : []),
  ];

  const renderLinks = (mobile = false) =>
    links.map(({ to, label, icon, end }) => (
      <NavLink
        key={to}
        to={to}
        end={end}
        onClick={mobile ? closeDrawer : undefined}
        className={({ isActive }) =>
          `${mobile ? classes.mobileLink : classes.navLink}${isActive ? ` ${classes.activeLink}` : ''}`
        }
      >
        {icon}
        <span>{label}</span>
      </NavLink>
    ));

  return (
    <Box component="header" className={classes.header}>
      <Container size="xl" className={classes.inner}>
        <Anchor component={Link} to="/" className={classes.brand} underline="never">
          <span className={classes.brandMark}>
            <IconShieldLock size={23} stroke={1.8} />
          </span>
          <Text fw={800} size="lg" c="var(--mantine-color-text)">
            Cypher<span className={classes.brandAccent}>Nod</span>
          </Text>
        </Anchor>

        <Group gap={6} className={classes.desktopNav}>
          {renderLinks()}
        </Group>

        <Group gap="sm">
          <ColorSchemeToggle />
          <Group gap="xs" visibleFrom="sm">
            {logoutRequest.isError && (
              <Text c="red" size="xs" role="alert">
                Logout failed. Try again.
              </Text>
            )}
            {isLoggedIn ? (
              <>
                <Text
                  size="sm"
                  c="dimmed"
                  truncate="end"
                  maw={{ sm: 80, md: 140 }}
                  title={user?.name}
                >
                  {user?.name}
                </Text>
                <Button
                  variant="subtle"
                  color="gray"
                  leftSection={<IconLogout size={17} />}
                  onClick={logout}
                  loading={logoutRequest.isPending}
                >
                  Log out
                </Button>
              </>
            ) : (
              <>
                <Button component={Link} to="/login" variant="subtle" color="gray">
                  Log in
                </Button>
                <Button
                  component={Link}
                  to="/signup"
                  leftSection={<IconUserPlus size={17} />}
                  radius="xl"
                >
                  Sign up
                </Button>
              </>
            )}
          </Group>
          <Burger
            opened={drawerOpened}
            onClick={() => setDrawerOpened((opened) => !opened)}
            hiddenFrom="sm"
            size="sm"
            aria-label={drawerOpened ? 'Close navigation menu' : 'Open navigation menu'}
          />
        </Group>
      </Container>

      <Drawer
        closeButtonProps={{ 'aria-label': 'Close navigation menu' }}
        opened={drawerOpened}
        onClose={closeDrawer}
        title={
          <Group gap="xs">
            <IconShieldLock size={20} />
            <Text fw={700}>CypherNod</Text>
          </Group>
        }
        position="right"
        size="xs"
      >
        <Box className={classes.mobileNav}>
          {logoutRequest.isError && (
            <Text c="red" role="alert">
              Could not log out. Please try again.
            </Text>
          )}
          {renderLinks(true)}
          {isLoggedIn ? (
            <>
              <Text size="sm" c="dimmed" mt="md" style={{ overflowWrap: 'anywhere' }}>
                Signed in as {user?.name}
              </Text>
              <Button
                variant="light"
                color="gray"
                leftSection={<IconLogout size={17} />}
                onClick={logout}
                loading={logoutRequest.isPending}
                mt="sm"
                fullWidth
              >
                Log out
              </Button>
            </>
          ) : (
            <>
              <Button
                component={Link}
                to="/login"
                onClick={closeDrawer}
                variant="light"
                mt="md"
                fullWidth
              >
                Log in
              </Button>
              <Button
                component={Link}
                to="/signup"
                onClick={closeDrawer}
                leftSection={<IconUserPlus size={17} />}
                mt="sm"
                fullWidth
              >
                Sign up
              </Button>
            </>
          )}
        </Box>
      </Drawer>
    </Box>
  );
}
