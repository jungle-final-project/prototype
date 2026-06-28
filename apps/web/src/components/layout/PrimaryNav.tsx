import { NavLink } from 'react-router-dom';

export function PrimaryNav() {
  const nav = [
    ['/', '홈'],
    ['/requirements/new', 'AI 견적'],
    ['/self-quote', '셀프 견적'],
    ['/builds/00000000-0000-4000-8000-000000002001', '추천 결과'],
    ['/my/quotes', '목표가 알림'],
    ['/support/new', 'AS 접수'],
    ['/admin', '관리자']
  ];
  return (
    <nav className="h-[42px] bg-brand-blue text-sm text-white">
      <div className="mx-auto flex h-full w-[1320px] items-center gap-1">
        {nav.map(([to, label]) => (
          <NavLink key={to} to={to} className={({ isActive }) => `px-6 py-3 font-semibold ${isActive ? 'bg-white/18' : 'hover:bg-white/10'}`}>
            {label}
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
