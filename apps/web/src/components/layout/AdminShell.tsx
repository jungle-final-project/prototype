import { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Activity, Bot, Cpu, Gauge, Home, LifeBuoy, RefreshCw, Search } from 'lucide-react';

export function AdminShell({ children, title }: { children: ReactNode; title: string }) {
  return (
    <div className="screen-shell flex bg-slate-100">
      <AdminSidebar />
      <div className="flex-1">
        <div className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-7">
          <div className="text-lg font-bold text-brand-navy">{title}</div>
          <div className="flex gap-2">
            <button
              type="button"
              disabled
              title="Sprint 1 export scope pending"
              className="cursor-not-allowed rounded border border-slate-200 bg-slate-100 px-3 py-2 text-xs font-semibold text-slate-400"
            >
              내보내기
            </button>
            <button
              type="button"
              disabled
              title="Sprint 1 job action pending"
              className="cursor-not-allowed rounded bg-slate-300 px-3 py-2 text-xs font-semibold text-slate-500"
            >
              작업 실행
            </button>
          </div>
        </div>
        <main className="p-7">{children}</main>
      </div>
    </div>
  );
}

function AdminSidebar() {
  const { pathname } = useLocation();
  const items = [
    { to: '/admin', label: '대시보드', Icon: Home, match: (path: string) => path === '/admin' },
    { to: '/admin/agent-sessions/00000000-0000-4000-8000-000000003001', label: 'Agent 세션', Icon: Bot, match: (path: string) => path.startsWith('/admin/agent-sessions') },
    { to: '/admin/tool-invocations/00000000-0000-4000-8000-000000005002', label: 'Tool 이력', Icon: Activity, match: (path: string) => path.startsWith('/admin/tool-invocations') },
    { to: '/admin/rag-evidence/00000000-0000-4000-8000-000000004001', label: 'RAG 근거', Icon: Search, match: (path: string) => path.startsWith('/admin/rag-evidence') },
    { to: '/admin/parts', label: '부품/가격', Icon: Cpu, match: (path: string) => path === '/admin/parts' },
    { to: '/admin/as-tickets', label: 'AS 티켓', Icon: LifeBuoy, match: (path: string) => path.startsWith('/admin/as-tickets') },
    { to: '/admin/price-jobs', label: '가격 Job', Icon: RefreshCw, match: (path: string) => path.startsWith('/admin/price-jobs') },
    { to: '/admin/load-tests', label: '부하 테스트', Icon: Gauge, match: (path: string) => path.startsWith('/admin/load-tests') }
  ];

  return (
    <aside className="w-60 bg-brand-navy px-4 py-6 text-white">
      <div className="mb-10 text-xl font-bold">BuildGraph<br />Admin</div>
      <nav aria-label="관리자 메뉴" className="space-y-2">
        {items.map(({ to, label, Icon, match }) => {
          const isActive = match(pathname);
          return (
            <Link key={to} to={to} aria-current={isActive ? 'page' : undefined} className={`flex h-10 items-center gap-2 rounded px-3 text-sm font-semibold ${isActive ? 'bg-brand-blue text-white' : 'text-slate-300 hover:bg-white/10'}`}>
              <Icon size={16} />
              {label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
