import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Screen, StateMessage } from '../../components/ui';
import { ApiError, saveAuthTokens } from '../../lib/api';
import { login, signup } from './authApi';

export function LoginPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const response = await login(email, password);
      saveAuthTokens(response.accessToken, response.refreshToken, response.user);
      navigate('/');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'API 연결 전에는 Docker compose로 백엔드를 먼저 실행해 주세요.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen>
      <div className="mx-auto mt-24 w-[420px] panel p-8">
        <h1 className="text-xl font-bold text-brand-navy">로그인</h1>
        <p className="mt-1 text-sm text-slate-500">JWT 기반 프로토타입 인증</p>
        {error ? <div className="mt-4"><StateMessage type="warn" title="로그인 실패" body={error} /></div> : null}
        <form onSubmit={submit} className="mt-6 space-y-4">
          <label className="block text-sm font-semibold text-slate-700">
            이메일
            <input
              name="email"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="user@example.com"
              autoComplete="username"
              type="email"
              required
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            비밀번호
            <input
              name="password"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="비밀번호"
              autoComplete="current-password"
              type="password"
              required
            />
          </label>
          <button
            className="h-11 w-full rounded bg-brand-blue text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={submitting}
          >
            {submitting ? '로그인 중' : '로그인'}
          </button>
          <Link to="/signup" className="block h-11 rounded border border-slate-300 pt-3 text-center text-sm font-bold">회원가입</Link>
        </form>
      </div>
    </Screen>
  );
}

export function SignupPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const name = String(form.get('name') ?? '').trim();
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const passwordConfirm = String(form.get('passwordConfirm') ?? '');
      const termsAccepted = form.get('termsAccepted') === 'on';
      const marketingAccepted = form.get('marketingAccepted') === 'on';

      if (password !== passwordConfirm) {
        setError('비밀번호 확인이 일치하지 않습니다.');
        return;
      }

      await signup({ name, email, password, termsAccepted, marketingAccepted });
      navigate('/login');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'API 연결 전에는 Docker compose로 백엔드를 먼저 실행해 주세요.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen>
      <div className="mx-auto mt-16 w-[520px] panel p-8">
        <h1 className="text-xl font-bold text-brand-navy">회원가입</h1>
        <p className="mt-1 text-sm text-slate-500">이메일 로그인용 User/Auth 공통 모듈</p>
        {error ? <div className="mt-4"><StateMessage type="warn" title="회원가입 실패" body={error} /></div> : null}
        <form onSubmit={submit} className="mt-6 grid grid-cols-2 gap-4">
          <label className="block text-sm font-semibold text-slate-700">
            이름
            <input
              name="name"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="홍길동"
              autoComplete="name"
              required
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            이메일
            <input
              name="email"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="new-user@example.com"
              autoComplete="email"
              type="email"
              required
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            비밀번호
            <input
              name="password"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="비밀번호"
              autoComplete="new-password"
              type="password"
              required
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            비밀번호 확인
            <input
              name="passwordConfirm"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
              placeholder="비밀번호 확인"
              autoComplete="new-password"
              type="password"
              required
            />
          </label>
          <label className="col-span-2 flex items-center gap-2 text-sm text-slate-700">
            <input name="termsAccepted" type="checkbox" required />
            서비스 이용약관 및 로그 업로드 정책 확인
          </label>
          <label className="col-span-2 flex items-center gap-2 text-sm text-slate-700">
            <input name="marketingAccepted" type="checkbox" />
            마케팅 정보 수신 동의
          </label>
          <button
            className="col-span-2 h-11 rounded bg-brand-blue text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={submitting}
          >
            {submitting ? '가입 중' : '회원가입'}
          </button>
        </form>
      </div>
    </Screen>
  );
}
