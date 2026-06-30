import { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { getToken } from '../../lib/api';

export function RequireUser({ children, preserveRedirect = true }: { children: ReactNode; preserveRedirect?: boolean }) {
  const location = useLocation();
  if (!getToken()) {
    const redirect = preserveRedirect
      ? `?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`
      : '';
    return <Navigate to={`/login${redirect}`} replace />;
  }

  return <>{children}</>;
}
