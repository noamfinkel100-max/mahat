import { useContext, useState } from 'react';
import { Anchor, Box, Burger, Button, Container, Drawer, Group, Text } from '@mantine/core';
import {
  IconFolder,
  IconHome,
  IconLogout,
  IconShieldLock,
  IconUserPlus,
} from '@tabler/icons-react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { FilesContext } from '@/App';
import { ColorSchemeToggle } from './ColorSchemeToggle/ColorSchemeToggle';
import classes from './AppNavigation.module.css';

export function AppNavigation() {
  const { filesState, setFilesState } = useContext(FilesContext);
  const [drawerOpened, setDrawerOpened] = useState(false);
  const navigate = useNavigate();
  const isLoggedIn = Boolean(filesState?.user);

  const closeDrawer = () => setDrawerOpened(false);
  const logout = () => {
    setFilesState(undefined);
    closeDrawer();
    navigate('/');
  };

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
            {isLoggedIn ? (
              <>
                <Text size="sm" c="dimmed">
                  {filesState?.user}
                </Text>
                <Button
                  variant="subtle"
                  color="gray"
                  leftSection={<IconLogout size={17} />}
                  onClick={logout}
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
          {renderLinks(true)}
          {isLoggedIn ? (
            <>
              <Text size="sm" c="dimmed" mt="md">
                Signed in as {filesState?.user}
              </Text>
              <Button
                variant="light"
                color="gray"
                leftSection={<IconLogout size={17} />}
                onClick={logout}
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
