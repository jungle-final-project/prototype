import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';

/**
 * 보드 위 떠 있는 카드(후보 패널·관계/문제 팝오버)를 헤더로 잡아 옮기는 공용 드래그 훅.
 * - 데스크톱(lg 이상) 전용 — 모바일 바텀시트/인라인 렌더는 고정.
 * - 보드([data-testid="slot-board"]) 영역 안으로 클램프해 overflow에 잘려 사라지지 않는다.
 *   (오버레이/팝오버는 보드 바디 div의 형제일 수 있어 closest 실패 시 문서 조회로 폴백 —
 *    렌더 중인 보드는 항상 1개다.)
 * - 핸들 안의 컨트롤(button·select·input·label·a) 조작은 드래그로 삼키지 않는다.
 * - resetKey가 바뀌면(다른 슬롯으로 다시 열림) 위치를 초기화한다.
 */
export function useBoardDrag<T extends HTMLElement>({ resetKey }: { resetKey?: unknown } = {}) {
  const targetRef = useRef<T | null>(null);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragSessionRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    baseX: number;
    baseY: number;
    minDx: number;
    maxDx: number;
    minDy: number;
    maxDy: number;
  } | null>(null);

  useEffect(() => {
    setDragOffset({ x: 0, y: 0 });
  }, [resetKey]);

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
    const board = target.closest('[data-testid="slot-board"]')
      ?? document.querySelector('[data-testid="slot-board"]');
    const bounds = (board ?? document.body).getBoundingClientRect();
    const rect = target.getBoundingClientRect();
    dragSessionRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      baseX: dragOffset.x,
      baseY: dragOffset.y,
      // 현재 위치 기준으로 보드 밖으로 못 나가는 이동 범위.
      minDx: bounds.left - rect.left,
      maxDx: bounds.right - rect.right,
      minDy: bounds.top - rect.top,
      maxDy: bounds.bottom - rect.bottom
    };
    setIsDragging(true);
    event.preventDefault();
    const handleMove = (move: globalThis.PointerEvent) => {
      const session = dragSessionRef.current;
      if (!session || move.pointerId !== session.pointerId) {
        return;
      }
      const dx = Math.min(Math.max(move.clientX - session.startX, session.minDx), session.maxDx);
      const dy = Math.min(Math.max(move.clientY - session.startY, session.minDy), session.maxDy);
      setDragOffset({ x: session.baseX + dx, y: session.baseY + dy });
    };
    const handleUp = (up: globalThis.PointerEvent) => {
      if (dragSessionRef.current && up.pointerId !== dragSessionRef.current.pointerId) {
        return;
      }
      dragSessionRef.current = null;
      setIsDragging(false);
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      window.removeEventListener('pointercancel', handleUp);
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
    window.addEventListener('pointercancel', handleUp);
  };

  const dragStyle = dragOffset.x !== 0 || dragOffset.y !== 0
    ? { transform: `translate(${dragOffset.x}px, ${dragOffset.y}px)` }
    : undefined;

  return { targetRef, dragOffset, dragStyle, isDragging, startDrag };
}
