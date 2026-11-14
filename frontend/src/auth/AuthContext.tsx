import React, { createContext, useContext, useCallback } from 'react';
import { useAuth as useClerkAuth, useUser } from '@clerk/clerk-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { UserDto } from '../types';
import { userApi } from '../api';

interface AuthContextType {
  user: UserDto | null;
  isLoading: boolean;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const { isSignedIn, isLoaded } = useClerkAuth();
  const { user: clerkUser } = useUser();
  const queryClient = useQueryClient();

  // The profile (storage used / limit) lives in React Query under ['user'],
  // so the invalidateQueries({ queryKey: ['user'] }) calls fired after
  // uploads, deletes and restores refetch it and the sidebar meter stays
  // current. The previous version fetched once into useState: those
  // invalidations matched nothing, and a single failed fetch (e.g. racing
  // Clerk's session setup right after sign-in, or hitting a cold backend)
  // left the meter stuck at 0% until a full page reload.
  const userQuery = useQuery({
    queryKey: ['user', clerkUser?.id ?? 'session'],
    queryFn: () => userApi.getMe().then((r) => r.data),
    enabled: isLoaded && !!isSignedIn,
    // Right after sign-in the first request can go out before
    // window.Clerk.session exists and come back 401 — retry instead of
    // giving up for the rest of the session.
    retry: 3,
    retryDelay: (attempt) => 500 * 2 ** attempt,
  });

  const refreshUser = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: ['user'] });
  }, [queryClient]);

  return (
    <AuthContext.Provider
      value={{
        user: (isSignedIn ? userQuery.data : undefined) ?? null,
        isLoading: !isLoaded,
        refreshUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within an AuthProvider');
  return context;
}
