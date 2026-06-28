import { Link, useParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';

export function ChangePartPage() {
  const { buildId = '00000000-0000-4000-8000-000000002001' } = useParams();
  return (
    <Screen>
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="부품 변경 비교 / 검증" subtitle={`Build ${buildId}: 변경 전후 가격, 성능, 전력, 호환성 차이`}>
          <DataTable columns={['구분', '변경 전', '변경 후', '차이', '판정']} rows={[
            { 구분: 'GPU', '변경 전': 'RTX 4060 Ti', '변경 후': 'RTX 4070 SUPER', 차이: '+318,000원', 판정: <StatusBadge status="WARN" /> },
            { 구분: 'QHD 성능', '변경 전': '기준 1.0x', '변경 후': '예상 1.42x', 차이: '+42%', 판정: <StatusBadge status="PASS" /> },
            { 구분: '전력', '변경 전': '520W peak', '변경 후': '640W peak', 차이: '+120W', 판정: <StatusBadge status="WARN" /> }
          ]} />
        </Panel>
        <Panel title="적용 결과">
          <StateMessage type="warn" title="조건부 적용 가능" body="성능은 개선되지만 PSU 여유율이 낮습니다. 750W 이상 구성과 함께 적용하세요." />
          <div className="mt-5 space-y-3">
            <Link to={`/builds/${buildId}`} className="block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">변경 적용</Link>
            <Link to="/self-quote" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">다른 부품 선택</Link>
          </div>
        </Panel>
      </div>
    </Screen>
  );
}
