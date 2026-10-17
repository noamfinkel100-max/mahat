import '@mantine/core/styles.css';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Router } from './Router';
import { theme } from './theme';
import React, { useState } from 'react';

export type filesStateType = {
  userId: string;
  user: string;
  myFiles: Record<string, string>;
  sharedWithMe: string[];
};

export const FilesContext = React.createContext<{
  filesState: filesStateType | undefined;
  setFilesState: React.Dispatch<React.SetStateAction<filesStateType | undefined>>;
}>({
  filesState: undefined,
  setFilesState: () => {},
});

export default function App() {
  const [filesState, setFilesState] = useState<filesStateType>();

  const queryClient = new QueryClient();

  return (
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme}>
        <FilesContext.Provider value={{ filesState, setFilesState }}>
          <Router />
        </FilesContext.Provider>
      </MantineProvider>
    </QueryClientProvider>
  );
}
