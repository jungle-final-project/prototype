import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';

const priceJobRows = [
  { source: '네이버 쇼핑 API', scope: '1차 가격 수집', cadence: '하루 1회', status: <StatusBadge status="ACTIVE" />, owner: '2번' },
  { source: '다나와 제한 크롤링', scope: 'PC 부품 가격 보완', cadence: '누락/실패 보완', status: <StatusBadge status="WARN" />, owner: '2번' },
  { source: '목표가 알림', scope: '목표가 이하 최초 확인', cadence: '수집 후 1회', status: <StatusBadge status="ACTIVE" />, owner: '2번/5번' }
];

const failureRows = [
  { type: 'API 실패', policy: '이전 가격 임시 사용', owner: '2번' },
  { type: '크롤링 실패', policy: '실패 이력 저장 후 다음 스케줄 재시도', owner: '2번' },
  { type: '메일 발송 실패', policy: 'Mailpit smoke와 SMTP 설정 확인', owner: '5번 협업' }
];

export function AdminPriceJobsPage() {
  return (
    <AdminShell title="가격 Job 관리자">
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="가격 수집 작업" subtitle="네이버 쇼핑 API와 다나와 제한 크롤링으로 수집하는 가격 Job 상태">
          <DataTable columns={['source', 'scope', 'cadence', 'status', 'owner']} rows={priceJobRows} />
        </Panel>
        <Panel title="실행 정책">
          <StateMessage type="info" title="2번 owner 작업" body="가격 수집, 상품 매칭, 실패 이력 저장은 2번 담당이며 5번은 AdminShell과 실행 환경을 협업합니다." />
          <button disabled className="mt-5 w-full cursor-not-allowed rounded bg-slate-300 px-4 py-3 text-sm font-bold text-slate-600">가격 Job 실행 API 연동 대기</button>
        </Panel>
        <Panel title="실패 이력 기준" className="col-span-2">
          <DataTable columns={['type', 'policy', 'owner']} rows={failureRows} />
        </Panel>
      </div>
    </AdminShell>
  );
}
