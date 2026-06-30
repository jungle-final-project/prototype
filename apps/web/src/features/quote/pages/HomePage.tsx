import { Link } from 'react-router-dom';
import { Bot } from 'lucide-react';
import { CategorySidebar, DataTable, Panel, Screen, StatusBadge } from '../../../components/ui';
import { QuoteCard } from '../components/QuoteCard';
import { builds, categories } from '../mocks/quoteMock';

export function HomePage() {
  return (
    <Screen>
      <div className="mb-3 text-xs text-slate-500">Home / AI PC consulting</div>
      <div className="grid grid-cols-[216px_1fr] gap-5">
        <CategorySidebar items={categories} />
        <div className="space-y-5">
          <Panel title="AI 기반 온라인 견적" subtitle="용도와 예산을 입력하면 요구사항을 구조화하고 추천 Build를 생성합니다.">
            <div className="grid grid-cols-[1fr_190px] gap-4">
              <textarea className="h-24 rounded border border-slate-300 p-3 text-sm outline-none focus:border-brand-blue" defaultValue="200만원 안에서 QHD 게임과 개발을 같이 할 PC 추천해줘. NVIDIA 선호." />
              <div className="space-y-3">
                <Link to="/requirements/new" className="flex h-10 items-center justify-center gap-2 rounded bg-brand-blue text-sm font-bold text-white"><Bot size={16} /> AI 견적 생성</Link>
                <Link to="/self-quote" className="flex h-10 items-center justify-center rounded border border-slate-300 text-sm font-bold">셀프 견적 보기</Link>
                <div className="rounded bg-brand-pale px-3 py-2 text-xs font-semibold text-brand-blue">POST /api/requirements/parse</div>
              </div>
            </div>
          </Panel>
          <Panel title="추천 Build 샘플" subtitle="P0 범위: 구매 전 AI 컨설팅, Tool 검증, 목표가 알림">
            <div className="flex gap-4">
              {builds.map((build) => <QuoteCard key={build.id} build={build} />)}
            </div>
          </Panel>
          <Panel title="운영 공지 / 작업 큐">
            <DataTable columns={['구분', '내용', '상태']} rows={[
              { 구분: 'Tool', 내용: '호환성/전력/규격 검증 seed 응답 연결', 상태: <StatusBadge status="PASS" /> },
              { 구분: 'LLM', 내용: '요구사항 파싱은 fallback mock 우선', 상태: <StatusBadge status="WARN" /> },
              { 구분: 'Agent', 내용: '최근 30분 JSONL 업로드 흐름 준비', 상태: <StatusBadge status="PASS" /> }
            ]} />
          </Panel>
        </div>
      </div>
    </Screen>
  );
}
