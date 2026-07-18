import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import type { AiAssistantSession, AiRecommendedBuild } from '../aiSelection';
import type { BuildSummary, ToolResult, WarningDto } from '../types';

export function BuildDetailSections({
  displayBuild,
  conditionBody,
  summaryNotice,
  summaryActions
}: {
  displayBuild: BuildSummary;
  conditionBody: string;
  summaryNotice?: ReactNode;
  summaryActions: ReactNode;
}) {
  const toolResults = displayBuild.toolResults ?? [];
  const passCount = toolResults.filter((row) => row.status === 'PASS').length;

  return (
    <div className="grid items-stretch gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 lg:h-full">
        <Panel
          title="구성 부품"
          action={<InlineVerificationSummary results={toolResults} passCount={passCount} />}
          className="lg:h-full"
        >
          <div className="space-y-2 md:hidden">
            {displayBuild.items.map((item) => (
              <div key={`${displayBuild.id}-${item.category}`} className="rounded-md border border-commerce-line bg-white p-3">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-xs font-black text-slate-500">{item.category}</span>
                  <span className="whitespace-nowrap text-sm font-black text-[#de6c2d]">{item.price.toLocaleString()}원</span>
                </div>
                <div className="mt-2 text-sm font-black leading-5 text-commerce-ink">
                  {item.partId ? (
                    <Link to={`/parts/${item.partId}`} className="hover:text-[#de6c2d] hover:underline">{item.name}</Link>
                  ) : (
                    item.name
                  )}
                </div>
                <div className="mt-2 flex items-center justify-between gap-3">
                  <span className="text-xs font-semibold text-slate-500">{item.manufacturer ?? '-'}</span>
                  <PartStatus status={item.status} />
                </div>
              </div>
            ))}
          </div>
          <div className="hidden md:block">
            <DataTable columns={['분류', '부품명', '제조사', '상태', '가격']} nowrapColumns={['분류', '제조사', '상태', '가격']} rows={displayBuild.items.map((item) => ({
              분류: item.category,
              부품명: item.partId ? (
                <Link to={`/parts/${item.partId}`} className="font-bold text-commerce-ink hover:text-[#de6c2d] hover:underline">{item.name}</Link>
              ) : (
                item.name
              ),
              제조사: item.manufacturer ?? '-',
              상태: <PartStatus status={item.status} />,
              가격: <span className="whitespace-nowrap font-black text-commerce-ink">{item.price.toLocaleString()}원</span>
            }))} />
          </div>
        </Panel>
      </div>
      <Panel title="견적 요약 / 액션" className="lg:h-full">
        <div className="space-y-4">
          <div className="rounded-md border border-commerce-line bg-white p-4 shadow-sm">
            <div className="text-xs font-bold text-slate-500">총액</div>
            <div className="mt-2 text-2xl font-black tracking-tight text-[#de6c2d]">
              {displayBuild.totalPrice.toLocaleString()}원
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <MetricCard label="부품 수" value={`${displayBuild.items.length}개`} />
            <MetricCard label="경고" value={displayBuild.warnings.length > 0 ? `${displayBuild.warnings.length}건` : '없음'} />
          </div>
          <StateMessage
            type={displayBuild.warnings.length > 0 ? 'warn' : 'success'}
            title={displayBuild.warnings.length > 0 ? '확인 필요' : '주요 조건 충족'}
            body={displayBuild.warnings[0]?.message ?? conditionBody}
          />
          {summaryNotice}
          {summaryActions}
        </div>
      </Panel>
    </div>
  );
}

export function temporaryBuildToBuildSummary(build: AiRecommendedBuild): BuildSummary {
  return {
    id: build.id,
    name: build.title,
    recommendedFor: build.tierLabel,
    summary: build.summary,
    totalPrice: build.totalPrice,
    confidence: build.confidence ?? 'MEDIUM',
    items: build.items.map((item) => ({
      id: item.partId,
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer,
      price: item.price * item.quantity,
      status: 'ACTIVE',
      attributes: { note: item.note, quantity: item.quantity }
    })),
    warnings: warningDtos(build.warnings ?? []),
    evidenceIds: [],
    agentSessionId: null,
    agentSummary: null,
    changeableCategories: ['CPU', 'GPU', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'],
    toolResults: build.toolResults ?? []
  };
}

export function latestUserMessage(session: AiAssistantSession) {
  return [...session.messages].reverse().find((message) => message.role === 'user')?.text;
}

function warningDtos(warnings: string[]): WarningDto[] {
  return warnings.map((message) => ({ message, severity: 'WARN' }));
}

function PartStatus({ status }: { status?: string }) {
  return status ? (
    <StatusBadge status={status} />
  ) : (
    <span className="text-[11px] font-bold text-slate-400">상태 정보 없음</span>
  );
}

function InlineVerificationSummary({ results, passCount }: { results: ToolResult[]; passCount: number }) {
  const overallStatus = results.some((row) => row.status.toUpperCase() === 'FAIL')
    ? 'FAIL'
    : results.some((row) => row.status.toUpperCase() === 'WARN')
      ? 'WARN'
      : 'PASS';

  return (
    <div aria-label="검증 요약" className="flex items-center gap-2 whitespace-nowrap">
      <span className="text-xs font-black text-slate-500">검증 요약</span>
      <span className="text-xs font-bold text-slate-700">
        {results.length > 0 ? `${passCount}/${results.length} 통과` : '결과 없음'}
      </span>
      {results.length > 0 ? <StatusBadge status={overallStatus} /> : null}
    </div>
  );
}
