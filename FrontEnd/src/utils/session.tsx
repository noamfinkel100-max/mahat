import { createContext, useContext } from 'react';
import type { User } from './api';

export const SessionContext = createContext<{ user: User | null; loading: boolean }>({
  user: null,
  loading: false,
});
export const useSession = () => useContext(SessionContext);
