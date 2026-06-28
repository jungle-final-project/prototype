import { Link } from 'react-router-dom';
import { CategorySidebar, DataTable, MetricCard, Panel, Screen, StatusBadge } from '../../../components/ui';
import { categories } from '../../quote/mocks/quoteMock';
import { parts } from '../mocks/partsMock';

export function SelfQuotePage() {
  return (
    <Screen>
      <div className="grid grid-cols-[216px_1fr_300px] gap-5">
        <CategorySidebar items={categories} />
        <Panel title="셀프 견적 / 부품 선택표" subtitle="필수 부품 선택 후 Tool 검증을 실행합니다.">
          <DataTable columns={['category', 'name', 'price', 'status', 'score']} rows={parts.map((part) => ({ ...part, price: `${part.price.toLocaleString()}원`, status: <StatusBadge status={part.status} /> }))} />
        </Panel>
        <Panel title="검증 / 합계">
          <MetricCard label="선택 합계" value="1,581,000원" />
          <div className="mt-4 space-y-3">
            <button className="w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white">Tool 검증하기</button>
            <Link to="/builds/00000000-0000-4000-8000-000000002001" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">추천 결과로 보기</Link>
          </div>
        </Panel>
      </div>
    </Screen>
  );
}
