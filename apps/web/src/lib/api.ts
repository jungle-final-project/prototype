export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
export const AUTH_CHANGED_EVENT = 'buildgraph-auth-change';
const ACCESS_TOKEN_KEY = 'buildgraph.token';
const REFRESH_TOKEN_KEY = 'buildgraph.refreshToken';
const AUTH_USER_KEY = 'buildgraph.authUser';

type ErrorResponseBody = {
  code?: unknown;
  message?: unknown;
};

type RefreshResponseBody = {
  accessToken?: unknown;
  refreshToken?: unknown;
};

export type AuthChangedDetail = {
  user?: unknown;
};

let refreshPromise: Promise<boolean> | null = null;

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
    public readonly code?: string,
    message?: string
  ) {
    super(message ?? `API ${status}: ${path}`);
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetchApi(path, init);

  if (!response.ok) {
    if (response.status === 401 && shouldAttemptTokenRefresh(path) && await refreshAuthTokens()) {
      const retryResponse = await fetchApi(path, init);
      if (retryResponse.ok) {
        return retryResponse.json() as Promise<T>;
      }
      const retryErrorBody = await readErrorResponse(retryResponse);
      throw new ApiError(retryResponse.status, path, retryErrorBody.code, retryErrorBody.message);
    }

    const errorBody = await readErrorResponse(response);
    throw new ApiError(response.status, path, errorBody.code, errorBody.message);
  }

  return response.json() as Promise<T>;
}

async function fetchApi(path: string, init?: RequestInit) {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  const headers = new Headers(init?.headers);
  if (!(init?.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) headers.set('Authorization', `Bearer ${token}`);

  return fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers
  });
}

function shouldAttemptTokenRefresh(path: string) {
  if (!getRefreshToken()) {
    return false;
  }
  return ![
    '/api/auth/login',
    '/api/auth/refresh',
    '/api/auth/logout',
    '/api/auth/exchange',
    '/api/users'
  ].includes(path) && !path.startsWith('/api/auth/google/');
}

async function refreshAuthTokens() {
  refreshPromise ??= requestTokenRefresh().finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

async function requestTokenRefresh() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return false;
  }

  try {
    const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    });

    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        clearToken();
      }
      return false;
    }

    const body = await response.json() as RefreshResponseBody;
    if (typeof body.accessToken !== 'string' || typeof body.refreshToken !== 'string') {
      return false;
    }

    storeAuthTokens(body.accessToken, body.refreshToken);
    return true;
  } catch {
    return false;
  }
}

async function readErrorResponse(response: Response) {
  try {
    const body = await response.json() as ErrorResponseBody;
    return {
      code: typeof body.code === 'string' ? body.code : undefined,
      message: typeof body.message === 'string' ? body.message : undefined
    };
  } catch {
    return {};
  }
}

function dispatchAuthChanged(detail?: AuthChangedDetail) {
  window.dispatchEvent(new CustomEvent<AuthChangedDetail>(AUTH_CHANGED_EVENT, { detail }));
}

function storeAuthTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

function storeAuthUser(user: unknown) {
  if (user === undefined) {
    return;
  }
  localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user));
}

export function saveAuthTokens(accessToken: string, refreshToken: string, user?: unknown) {
  storeAuthTokens(accessToken, refreshToken);
  storeAuthUser(user);
  dispatchAuthChanged({ user });
}

export function saveToken(token: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
  localStorage.removeItem(AUTH_USER_KEY);
  dispatchAuthChanged();
}

export function getToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function getCachedAuthUser() {
  try {
    const raw = localStorage.getItem(AUTH_USER_KEY);
    return raw ? JSON.parse(raw) as unknown : null;
  } catch {
    return null;
  }
}

export function clearToken() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(AUTH_USER_KEY);
  dispatchAuthChanged();
}
