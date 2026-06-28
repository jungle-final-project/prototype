import { Link, useParams } from 'react-router-dom';
import { DataTable, MetricCard, Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';
import { toolRows } from '../../parts/mocks/toolMock';
import { QuoteCard } from '../components/QuoteCard';
import { builds } from '../mocks/quoteMock';

export function BuildResultPage() {
  const { buildId = '00000000-0000-4000-8000-000000002001' } = useParams();
  return (
    <Screen>
      <div className="grid grid-cols-[1fr_320px] gap-5">
        <div className="space-y-5">
          <Panel title={`추천 Build 결과 / ${buildId}`} subtitle="추천 Build 2~3개, Tool 검증 근거, 경고를 함께 표시">
            <div className="flex gap-4">
              {builds.map((build) => <QuoteCard key={build.id} build={build} />)}
            </div>
          </Panel>
          <Panel title="Tool 검증 결과">
            <DataTable columns={['tool', 'status', 'confidence', 'summary']} rows={toolRows.map((row) => ({ ...row, status: <StatusBadge status={row.status} />, confidence: <StatusBadge status={row.confidence} /> }))} />
          </Panel>
        </div>
        <Panel title="견적 요약 / 액션">
          <div className="space-y-4">
            <MetricCard label="총액" value="1,980,000원" />
            <StateMessage type="warn" title="PSU 여유율 확인" body="피크 전력 기준 여유율이 낮아 750W 이상을 권장합니다." />
            <Link to={`/builds/${buildId}/change-part`} className="block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">부품 변경 비교</Link>
            <Link to="/my/quotes" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">견적 저장</Link>
          </div>
        </Panel>
      </div>
    </Screen>
  );
}
