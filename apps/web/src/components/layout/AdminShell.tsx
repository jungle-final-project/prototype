import { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { Bot, Cpu, Home, LifeBuoy } from 'lucide-react';

export function AdminShell({ children, title }: { children: ReactNode; title: string }) {
  return (
    <div className="screen-shell flex bg-slate-100">
      <AdminSidebar />
      <div className="flex-1">
        <div className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-7">
          <div className="text-lg font-bold text-brand-navy">{title}</div>
          <div className="flex gap-2">
            <button className="rounded border border-slate-300 px-3 py-2 text-xs font-semibold">내보내기</button>
            <button className="rounded bg-brand-blue px-3 py-2 text-xs font-semibold text-white">작업 실행</button>
          </div>
        </div>
        <main className="p-7">{children}</main>
      </div>
    </div>
  );
}

function AdminSidebar() {
  const items = [
    ['/admin', 'Dashboard', Home],
    ['/admin/agent-sessions/00000000-0000-4000-8000-000000003001', 'Agent/RAG', Bot],
    ['/admin/parts', 'Parts/Price', Cpu],
    ['/admin/as-tickets', 'AS Tickets', LifeBuoy]
  ] as const;
  return (
    <aside className="w-[220px] bg-brand-navy px-4 py-6 text-white">
      <div className="mb-10 text-xl font-bold">BuildGraph<br />Admin</div>
      <div className="space-y-2">
        {items.map(([to, label, Icon]) => (
          <NavLink key={to} to={to} className={({ isActive }) => `flex items-center gap-2 rounded px-3 py-2 text-sm ${isActive ? 'bg-brand-blue' : 'text-slate-300 hover:bg-white/10'}`}>
            <Icon size={16} />
            {label}
          </NavLink>
        ))}
      </div>
    </aside>
  );
}
