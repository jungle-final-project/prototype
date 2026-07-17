import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent
} from 'react';

/** lg(1024px) 이상 여부 — 떠 있는 카드의 포탈/드래그/리사이즈는 데스크톱 전용이다. */
export function useIsDesktop() {
  const [isDesktop, setIsDesktop] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches
  );
  useEffect(() => {
    const media = window.matchMedia('(min-width: 1024px)');
    const handle = (event: MediaQueryListEvent) => setIsDesktop(event.matches);
    media.addEventListener('change', handle);
    return () => media.removeEventListener('change', handle);
  }, []);
  return isDesktop;
}

// 카드가 화면 밖으로 나가더라도 이만큼은 남겨 항상 다시 잡을 수 있게 한다.
const MIN_VISIBLE_PX = 48;

/** 사용자가 옮겨 놓은 자리(화면 절대 좌표). */
type PlacedPoint = { left: number; top: number };

// 옮겨 놓은 자리는 카드가 사라져도(부품 전환·닫기) 이 모듈에 남는다 — "가져다 놓고 쓰는" 도구라
// 한 번 놓으면 그 자리를 지킨다. 새로고침하면 초기화되고, 핸들 더블클릭이 유일한 수동 해제다.
const placedByKey = new Map<string, PlacedPoint>();

/** 화면이 줄어 기억해둔 자리가 밖으로 나갔을 때, 다시 잡을 수 있는 위치로 끌어들인다. */
function clampIntoViewport(point: PlacedPoint, width: number): PlacedPoint {
  if (typeof window === 'undefined') {
    return point;
  }
  const minLeft = MIN_VISIBLE_PX - width;
  const maxLeft = window.innerWidth - MIN_VISIBLE_PX;
  const maxTop = Math.max(window.innerHeight - MIN_VISIBLE_PX, 0);
  return {
    left: Math.min(Math.max(point.left, minLeft), maxLeft),
    // 헤더(잡는 곳)가 화면 위로 사라지면 다시 옮길 수 없다 — 위쪽은 0 아래로 못 간다.
    top: Math.min(Math.max(point.top, 0), maxTop)
  };
}

/**
 * 보드 위 떠 있는 카드(후보 패널·관계/문제 팝오버)를 헤더로 잡아 옮기는 공용 드래그 훅.
 * - 데스크톱(lg 이상) 전용 — 모바일 바텀시트/인라인 렌더는 고정이라 위치 스타일을 주지 않는다.
 * - anchor = 시스템이 정한 기본 자리(팝오버는 부품 옆, 패널은 보드 좌상단). 아직 안 옮겼으면 여기 뜬다.
 * - 한 번 옮기면 그 자리를 persistKey에 절대 좌표로 기억해, 부품을 바꾸거나 닫았다 열어도 그대로 있다.
 * - 경계는 화면(뷰포트): 보드 밖으로도 밀어낼 수 있되, 카드의 일부(48px)와 헤더 상단은 항상 남는다.
 * - 핸들 안의 컨트롤(button·select·input·label·a) 조작은 드래그로 삼키지 않는다.
 * - 핸들 더블클릭 = 기억한 자리를 버리고 기본 자리·기본 크기로(resetDrag).
 */
export function useBoardDrag<T extends HTMLElement>({
  persistKey,
  anchor
}: {
  persistKey: string;
  anchor: PlacedPoint;
}) {
  const isDesktop = useIsDesktop();
  const targetRef = useRef<T | null>(null);
  const [placed, setPlaced] = useState<PlacedPoint | null>(() => placedByKey.get(persistKey) ?? null);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragSessionRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null>(null);
  // 드래그 중 최신 오프셋 — 종료 시 절대 좌표로 확정할 때 읽는다(상태 갱신 함수 안에서 부수효과를 내지 않도록).
  const offsetRef = useRef({ x: 0, y: 0 });

  const base = placed ?? anchor;
  // 이벤트 핸들러가 최신 기준점을 읽도록 — 드래그 중 재렌더로 base가 바뀌어도 세션은 흔들리지 않는다.
  const baseRef = useRef(base);
  baseRef.current = base;

  const resetDrag = () => {
    placedByKey.delete(persistKey);
    setPlaced(null);
    setDragOffset({ x: 0, y: 0 });
    offsetRef.current = { x: 0, y: 0 };
    const target = targetRef.current;
    if (target) {
      // 네이티브 리사이즈가 남긴 인라인 크기도 함께 초기화한다.
      target.style.width = '';
      target.style.height = '';
    }
  };

  // 창이 작아지면 기억해둔 자리가 화면 밖일 수 있다 — 복원 직후와 리사이즈 때 다시 잡을 수 있게 끌어들인다.
  useEffect(() => {
    if (!placed || !isDesktop) {
      return;
    }
    const pull = () => {
      const target = targetRef.current;
      const current = baseRef.current;
      const rect = target?.getBoundingClientRect();
      const next = clampIntoViewport(current, rect?.width ?? 0);
      if (next.left !== current.left || next.top !== current.top) {
        placedByKey.set(persistKey, next);
        setPlaced(next);
      }
    };
    pull();
    window.addEventListener('resize', pull);
    return () => window.removeEventListener('resize', pull);
  }, [placed, persistKey, isDesktop]);

  const startDrag = (event: ReactPointerEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('button, select, input, label, a')) {
      return;
    }
    if (typeof window === 'undefined' || !window.matchMedia('(min-width: 1024px)').matches) {
      return;
    }
    const target = targetRef.current;
    if (!target) {
      return;
    }
    const rect = target.getBoundingClientRect();
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    dragSessionRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      // 화면 경계 클램프: 좌우는 48px 가시 유지, 위로는 헤더가 화면 위로 사라지지 않게.
      minDx: MIN_VISIBLE_PX - rect.right,
      maxDx: viewportWidth - MIN_VISIBLE_PX - rect.left,
      minDy: -rect.top,
      maxDy: viewportHeight - MIN_VISIBLE_PX - rect.top
    };
    setIsDragging(true);
    event.preventDefault();
    const handleMove = (move: globalThis.PointerEvent) => {
      const session = dragSessionRef.current;
      if (!session || move.pointerId !== session.pointerId) {
        return;
      }
      const next = {
        x: Math.min(Math.max(move.clientX - session.startX, session.minDx), session.maxDx),
        y: Math.min(Math.max(move.clientY - session.startY, session.minDy), session.maxDy)
      };
      offsetRef.current = next;
      setDragOffset(next);
    };
    const handleUp = (up: globalThis.PointerEvent) => {
      if (dragSessionRef.current && up.pointerId !== dragSessionRef.current.pointerId) {
        return;
      }
      dragSessionRef.current = null;
      setIsDragging(false);
      // 옮긴 만큼을 절대 좌표로 확정하고 오프셋은 0으로 — 같은 렌더에 반영돼 화면은 그대로다.
      const offset = offsetRef.current;
      if (offset.x !== 0 || offset.y !== 0) {
        const next = { left: baseRef.current.left + offset.x, top: baseRef.current.top + offset.y };
        placedByKey.set(persistKey, next);
        setPlaced(next);
        offsetRef.current = { x: 0, y: 0 };
        setDragOffset({ x: 0, y: 0 });
      }
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      window.removeEventListener('pointercancel', handleUp);
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
    window.addEventListener('pointercancel', handleUp);
  };

  // 모바일(바텀시트·인라인)은 좌표를 주지 않는다 — 레이아웃이 위치를 소유한다.
  const dragStyle: CSSProperties | undefined = isDesktop
    ? {
        left: base.left,
        top: base.top,
        ...(dragOffset.x !== 0 || dragOffset.y !== 0
          ? { transform: `translate(${dragOffset.x}px, ${dragOffset.y}px)` }
          : {})
      }
    : undefined;

  return { targetRef, dragStyle, isDragging, startDrag, resetDrag, isPlaced: placed !== null };
}
