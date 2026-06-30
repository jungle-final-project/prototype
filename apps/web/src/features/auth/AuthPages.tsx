import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Screen, StateMessage } from '../../components/ui';
import { saveToken } from '../../lib/api';
import { login, signup } from './authApi';

export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    const form = new FormData(event.currentTarget);
    try {
      const response = await login(String(form.get('email') ?? ''), String(form.get('password') ?? ''));
      saveToken(response.accessToken);
      navigate(safeRedirect(searchParams.get('redirect')));
    } catch {
      setError('API 연결 전에는 Docker compose로 백엔드를 먼저 실행해 주세요.');
    }
  }
  return (
    <Screen>
      <div className="mx-auto mt-24 w-[420px] panel p-8">
        <h1 className="text-xl font-bold text-brand-navy">로그인</h1>
        <p className="mt-1 text-sm text-slate-500">JWT 기반 프로토타입 인증</p>
        {error ? <div className="mt-4"><StateMessage type="warn" title="로그인 실패" body={error} /></div> : null}
        <form onSubmit={submit} className="mt-6 space-y-4">
          <input name="email" className="h-11 w-full rounded border border-slate-300 px-3 text-sm" defaultValue="user@example.com" />
          <input name="password" className="h-11 w-full rounded border border-slate-300 px-3 text-sm" defaultValue="passw0rd!" type="password" />
          <button className="h-11 w-full rounded bg-brand-blue text-sm font-bold text-white">로그인</button>
          <Link to="/signup" className="block h-11 rounded border border-slate-300 pt-3 text-center text-sm font-bold">회원가입</Link>
        </form>
      </div>
    </Screen>
  );
}

function safeRedirect(value: string | null) {
  if (!value || !value.startsWith('/') || value.startsWith('//')) {
    return '/requirements/new';
  }
  return value;
}

export function SignupPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    const form = new FormData(event.currentTarget);
    try {
      await signup(
        String(form.get('name') ?? ''),
        String(form.get('email') ?? ''),
        String(form.get('password') ?? '')
      );
      navigate('/login');
    } catch {
      setError('API 연결 전에는 Docker compose로 백엔드를 먼저 실행해 주세요.');
    }
  }
  return (
    <Screen>
      <div className="mx-auto mt-16 w-[520px] panel p-8">
        <h1 className="text-xl font-bold text-brand-navy">회원가입</h1>
        <p className="mt-1 text-sm text-slate-500">이메일 로그인용 User/Auth 공통 모듈</p>
        {error ? <div className="mt-4"><StateMessage type="warn" title="회원가입 실패" body={error} /></div> : null}
        <form onSubmit={submit} className="mt-6 grid grid-cols-2 gap-4">
          <input name="name" className="h-11 rounded border border-slate-300 px-3 text-sm" defaultValue="홍길동" />
          <input className="h-11 rounded border border-slate-300 px-3 text-sm" defaultValue="010-0000-0000" />
          <input name="email" className="col-span-2 h-11 rounded border border-slate-300 px-3 text-sm" defaultValue="new-user@example.com" />
          <input name="password" className="h-11 rounded border border-slate-300 px-3 text-sm" defaultValue="passw0rd!" type="password" />
          <input className="h-11 rounded border border-slate-300 px-3 text-sm" defaultValue="passw0rd!" type="password" />
          <label className="col-span-2 flex items-center gap-2 text-sm"><input type="checkbox" defaultChecked /> 서비스 이용약관 및 로그 업로드 정책 확인</label>
          <button className="col-span-2 h-11 rounded bg-brand-blue text-sm font-bold text-white">회원가입</button>
        </form>
      </div>
    </Screen>
  );
}
