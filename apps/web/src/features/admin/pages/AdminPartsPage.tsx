import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { parts } from '../../parts/mocks/partsMock';

export function AdminPartsPage() {
  return (
    <AdminShell title="부품 / 가격 관리자">
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="부품 DB">
          <DataTable columns={['id', 'category', 'name', 'price', 'status']} rows={parts.map((part) => ({ ...part, price: `${part.price.toLocaleString()}원`, status: <StatusBadge status={part.status} /> }))} />
        </Panel>
        <Panel title="가격 데이터 기준">
          <StateMessage type="info" title="표시 가격 기준" body="배송비/쿠폰/카드할인을 제외한 표시 가격 기준으로 부품 가격을 비교합니다." />
          <Link to="/admin/price-jobs" className="mt-5 block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">가격 Job 보기</Link>
        </Panel>
      </div>
    </AdminShell>
  );
}
