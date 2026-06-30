import { api } from '../../lib/api';

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  user: {
    id: string;
    email: string;
    name: string;
    role: 'USER' | 'ADMIN';
  };
};

export type CurrentUser = LoginResponse['user'];
export type SignupResponse = LoginResponse['user'];

export function login(email: string, password: string) {
  return api<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

export function getCurrentUser() {
  return api<CurrentUser>('/api/auth/me');
}

type SignupPayload = {
  name: string;
  email: string;
  password: string;
  termsAccepted: boolean;
  marketingAccepted: boolean;
};

export function signup(payload: SignupPayload) {
  return api<SignupResponse>('/api/users', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}
