import '@mantine/core/styles.css';
import { useEffect } from 'react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { Router } from './Router';
import { theme } from './theme';
import { api, User } from './utils/api';
import { parseUserSession } from './utils/userSession';
import { SessionContext } from './utils/session';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: true } },
});

function Session() {
  useEffect(() => {
    const interceptor = api.interceptors.response.use(
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
          queryClient.setQueryData(['session'], null);
          queryClient.removeQueries({
            predicate: (query) => ['files', 'shares', 'users'].includes(String(query.queryKey[0])),
          });
        }
        return Promise.reject(error);
      }
    );
    return () => api.interceptors.response.eject(interceptor);
  }, []);
  const session = useQuery({
    queryKey: ['session'],
    queryFn: async () => {
      try {
        return parseUserSession((await api.get<User>('/auth/me')).data);
      } catch (error) {
        if ((error as { response?: { status?: number } }).response?.status === 401) return null;
        throw error;
      }
    },
    staleTime: 60000,
    refetchInterval: 60000,
  });
  return (
    <SessionContext.Provider value={{ user: session.data ?? null, loading: session.isPending }}>
      <Router />
    </SessionContext.Provider>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme} defaultColorScheme="auto">
        <Session />
      </MantineProvider>
    </QueryClientProvider>
  );
}
