import { Link, useNavigate, useParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../components/ui';
import { tickets } from './mocks/supportMock';

export function SupportNewPage() {
  const navigate = useNavigate();
  return (
    <Screen>
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="AS 접수 / 로그 업로드" subtitle="최근 30분 JSONL 로그 업로드 동의 후 티켓을 생성합니다.">
          <div className="space-y-4">
            <select className="h-10 w-[240px] rounded border border-slate-300 px-3 text-sm" defaultValue="performance">
              <option value="performance">성능 저하</option>
              <option value="crash">앱/드라이버 오류</option>
            </select>
            <textarea className="h-36 w-full rounded border border-slate-300 p-4 text-sm" defaultValue="게임 중 프레임이 갑자기 떨어지고 GPU 온도가 높게 표시됩니다." />
            <div className="rounded bg-slate-900 p-4 font-mono text-xs leading-6 text-slate-200">
              sample-agent-log.jsonl<br />range: recent 30 minutes<br />format: JSON Lines
            </div>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" defaultChecked /> 최근 30분 로그 업로드와 30일 보관에 동의합니다.</label>
            <button onClick={() => navigate('/support/00000000-0000-4000-8000-000000006001')} className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white">AS 접수하기</button>
          </div>
        </Panel>
        <Panel title="AI 1차 원인 후보">
          <StateMessage type="warn" title="GPU 온도 과열 가능성" body="최근 로그에서 GPU 온도 88도와 사용률 96%가 함께 관측되었습니다. 드라이버/쿨링 상태 확인이 필요합니다." />
          <Link to="/admin/as-tickets" className="mt-5 block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">관리자 티켓 보기</Link>
        </Panel>
      </div>
    </Screen>
  );
}

export function SupportTicketPage() {
  const { ticketId = '00000000-0000-4000-8000-000000006001' } = useParams();
  return (
    <Screen>
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title={`AS 티켓 상세 / ${ticketId}`} subtitle="사용자에게는 티켓 번호와 진행 상태 중심으로 표시">
          <DataTable columns={['시간', '단계', '내용', '상태']} rows={[
            { 시간: '20:01', 단계: '접수', 내용: '증상 및 로그 업로드 완료', 상태: <StatusBadge status="PASS" /> },
            { 시간: '20:02', 단계: 'AI 후보', 내용: 'GPU 온도 과열 가능성', 상태: <StatusBadge status="MEDIUM" /> },
            { 시간: '20:04', 단계: '상담원', 내용: '담당자 배정 대기', 상태: <StatusBadge status="OPEN" /> }
          ]} />
        </Panel>
        <Panel title="요약">
          <StateMessage type="info" title="티켓이 접수되었습니다" body="관리자 화면에서 최근 30분 로그 요약과 원인 후보를 확인할 수 있습니다." />
          <Link to="/support/new" className="mt-5 block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">새 AS 접수</Link>
        </Panel>
      </div>
    </Screen>
  );
}
