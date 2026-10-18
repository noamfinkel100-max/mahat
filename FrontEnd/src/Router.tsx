import { Center, Loader } from '@mantine/core';
import {
  createBrowserRouter,
  Navigate,
  Outlet,
  RouterProvider,
  useLocation,
} from 'react-router-dom';
import { HomePage } from './pages/Home.page';
import { LogInPage } from './components/LogInPage';
import { SignUpPage } from './components/SignUpPage';
import { MyFiles } from './components/MyFiles';
import { AppNavigation } from './components/AppNavigation';
import { useSession } from './utils/session';

function AppLayout() {
  return (
    <>
      <AppNavigation />
      <Outlet />
    </>
  );
}
function PrivateRoute() {
  const { user, loading } = useSession();
  const location = useLocation();
  if (loading)
    return (
      <Center mih="50vh">
        <Loader aria-label="Checking your session" />
      </Center>
    );
  return user ? <Outlet /> : <Navigate to="/login" replace state={{ from: location.pathname }} />;
}
const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/login', element: <LogInPage /> },
      { path: '/signup', element: <SignUpPage /> },
      { element: <PrivateRoute />, children: [{ path: '/myfiles', element: <MyFiles /> }] },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]);
export function Router() {
  return <RouterProvider router={router} />;
}
