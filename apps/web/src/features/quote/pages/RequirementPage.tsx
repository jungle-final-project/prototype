import { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';
import { Field } from '../components/Field';

export function RequirementPage() {
  const navigate = useNavigate();
  function submit(event: FormEvent) {
    event.preventDefault();
    navigate('/builds/00000000-0000-4000-8000-000000002001');
  }
  return (
    <Screen>
      <div className="grid grid-cols-[650px_1fr_300px] gap-5">
        <Panel title="AI 견적 입력" subtitle="자연어 또는 필수 필드 중 하나 이상 입력">
          <form onSubmit={submit} className="space-y-5">
            <textarea className="h-36 w-full rounded border border-slate-300 p-4 text-sm" defaultValue="배틀그라운드 QHD 옵션, 개발 IDE도 같이 쓸 예정. 예산은 200만원." />
            <div className="grid grid-cols-3 gap-4">
              <Field label="예산" value="2,000,000" />
              <Field label="주 용도" value="게임, 개발" />
              <Field label="해상도" value="QHD" />
              <Field label="브랜드 선호" value="NVIDIA" />
              <Field label="우선순위" value="성능 > 안정성 > 가격" wide />
            </div>
            <div className="flex gap-2">
              {['게임', '개발', '영상편집', 'AI 실습', '저소음', '업그레이드 여유'].map((chip) => <span key={chip} className="rounded-full border border-blue-200 bg-brand-pale px-3 py-1 text-xs font-bold text-brand-blue">{chip}</span>)}
            </div>
            <div className="flex gap-3">
              <button className="rounded bg-brand-blue px-5 py-2 text-sm font-bold text-white">요구사항 분석</button>
              <button type="button" className="rounded border border-slate-300 px-5 py-2 text-sm font-bold">초기화</button>
            </div>
          </form>
        </Panel>
        <Panel title="파싱 결과" subtitle="LLM fallback 결과">
          <DataTable columns={['필드', '값', '확신도']} rows={[
            { 필드: 'budget', 값: '2,000,000', 확신도: <StatusBadge status="HIGH" /> },
            { 필드: 'usage', 값: 'gaming, development', 확신도: <StatusBadge status="HIGH" /> },
            { 필드: 'targetResolution', 값: 'QHD', 확신도: <StatusBadge status="MEDIUM" /> },
            { 필드: 'brandPreference', 값: 'NVIDIA', 확신도: <StatusBadge status="HIGH" /> }
          ]} />
        </Panel>
        <Panel title="추가 질문" subtitle="누락 필드가 있을 때 표시">
          <StateMessage type="success" title="추가 질문 없음" body="추천 생성에 필요한 기본 조건이 충족되었습니다." />
          <Link to="/builds/00000000-0000-4000-8000-000000002001" className="mt-5 block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">추천 결과 보기</Link>
        </Panel>
      </div>
    </Screen>
  );
}
