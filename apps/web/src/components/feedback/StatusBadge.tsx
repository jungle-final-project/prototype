export function StatusBadge({ status }: { status: string }) {
  const s = status.toUpperCase();
  const cls = s === 'PASS' || s === 'HIGH' || s === 'ACTIVE' || s === 'RESOLVED'
    ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
    : s === 'WARN' || s === 'MEDIUM' || s === 'OPEN'
      ? 'bg-orange-50 text-orange-700 border-orange-200'
      : 'bg-slate-100 text-slate-600 border-slate-200';
  return <span title={statusTitle(s)} className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-bold ${cls}`}>{status}</span>;
}

function statusTitle(status: string) {
  const titles: Record<string, string> = {
    PASS: '검증 통과',
    WARN: '주의 필요',
    FAIL: '검증 실패',
    HIGH: '신뢰도 높음',
    MEDIUM: '신뢰도 중간',
    LOW: '신뢰도 낮음',
    ACTIVE: '알림 활성',
    TRIGGERED: '목표가 도달',
    OPEN: '접수됨',
    IN_PROGRESS: '처리 중',
    RESOLVED: '해결됨',
    QUEUED: '대기 중',
    RUNNING: '실행 중',
    SUCCEEDED: '성공',
    FAILED: '실패',
    READY: '준비됨',
    TODO: '예정'
  };
  return titles[status];
}
