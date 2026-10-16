import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { HomePage } from './pages/Home.page';
import { ColorSchemeToggle } from './components/ColorSchemeToggle/ColorSchemeToggle';
import { LogInPage } from './components/LogInPage';
import { SignUpPage } from './components/SignUpPage';
import { MyFiles } from './components/MyFiles';

const router = createBrowserRouter([
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
]);

export function Router() {
  return (
    <>
      <ColorSchemeToggle />
      <RouterProvider router={router} />
    </>
  );
}
