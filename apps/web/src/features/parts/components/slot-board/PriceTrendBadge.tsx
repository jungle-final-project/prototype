import { useQuery } from '@tanstack/react-query';
import { getPartPriceHistory } from '../../partsApi';

export function PriceTrendBadge({ partId }: { partId: string }) {
  const { data } = useQuery({
    queryKey: ['parts', partId, 'price-history', 'all-sources'],
    queryFn: () => getPartPriceHistory(partId, { days: 3650, limit: 60 }),
    staleTime: 60_000
  });
  const points = [...(data?.items ?? [])]
    .filter((point) => Number.isFinite(point.price))
    .sort((first, second) => Date.parse(first.collectedAt) - Date.parse(second.collectedAt));

  if (points.length < 2) {
    return null;
  }

  const previousPrice = points[points.length - 2]?.price ?? 0;
  const latestPrice = points[points.length - 1]?.price ?? 0;
  if (previousPrice <= 0 || latestPrice <= 0) {
    return null;
  }

  const change = latestPrice - previousPrice;
  if (change === 0) {
    return null;
  }

  const changeRatePercent = (change / previousPrice) * 100;
  const tone = change > 0 ? 'text-orange-700' : 'text-emerald-700';
  const sign = change > 0 ? '+' : '';
  return (
    <div className={`mt-1 text-[11px] font-bold ${tone}`}>
      직전 기록 대비 {sign}{change.toLocaleString()}원 ({sign}{changeRatePercent.toFixed(2)}%)
    </div>
  );
}
