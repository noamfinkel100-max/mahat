import { createBrowserRouter, Outlet, RouterProvider } from 'react-router-dom';
import { HomePage } from './pages/Home.page';
import { LogInPage } from './components/LogInPage';
import { SignUpPage } from './components/SignUpPage';
import { MyFiles } from './components/MyFiles';
import { AppNavigation } from './components/AppNavigation';

function AppLayout() {
  return (
    <>
      <AppNavigation />
      <Outlet />
    </>
  );
}

const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      {
        path: '/',
        element: <HomePage />,
      },
      {
        path: '/login',
        element: <LogInPage />,
      },
      {
        path: '/signup',
        element: <SignUpPage />,
      },
      {
        path: '/myfiles',
        element: <MyFiles />,
      },
    ],
  },
]);

export function Router() {
  return <RouterProvider router={router} />;
}
