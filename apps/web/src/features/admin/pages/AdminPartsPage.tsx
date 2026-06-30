import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { listParts } from '../../parts/partsApi';
import type { PartRow } from '../../parts/types';

export function AdminPartsPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-parts'],
    queryFn: () => listParts({ size: 100, sort: 'category' })
  });
  const parts = data?.items ?? [];

  return (
    <AdminShell title="부품 / 가격 관리자">
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="부품 DB" subtitle={`${data?.total ?? 0}개 내부 자산`}>
          {isLoading ? <StateMessage type="info" title="부품 DB 로딩 중" body="서버의 parts 테이블에서 내부 쇼핑몰 자산을 불러오고 있습니다." /> : null}
          {isError ? <StateMessage type="warn" title="부품 DB 조회 실패" body="GET /api/parts 응답을 확인해야 합니다." /> : null}
          {!isLoading && !isError ? (
            <DataTable columns={['category', 'name', 'manufacturer', 'price', 'status', 'source']} rows={partRows(parts)} />
          ) : null}
        </Panel>
        <Panel title="가격 데이터 기준">
          <StateMessage type="info" title="표시 가격 기준" body="배송비/쿠폰/카드할인을 제외한 표시 가격 기준으로 부품 가격을 비교합니다." />
          <Link to="/admin/price-jobs" className="mt-5 block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">가격 Job 보기</Link>
        </Panel>
      </div>
    </AdminShell>
  );
}

function partRows(parts: PartRow[]) {
  return parts.map((part) => ({
    category: part.category,
    name: part.name,
    manufacturer: part.manufacturer ?? '-',
    price: `${part.price.toLocaleString()}원`,
    status: <StatusBadge status={part.status} />,
    source: part.latestPriceSource ?? '-'
  }));
}
