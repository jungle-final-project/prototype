import { X } from 'lucide-react';
import { useLayoutEffect, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { FLOATING_CONTROL_STRIP_HEIGHT } from './slotBoardConfig';
import { useBoardDrag, useIsDesktop } from './useBoardDrag';

type FloatingQuotePanelProps = {
  testId: string;
  ariaLabel: string;
  title: string;
  subtitle?: string;
  persistKey: string;
  onClose: () => void;
  closeLabel?: string;
  closeOnBackdrop?: boolean;
  headerActions?: ReactNode;
  children: ReactNode;
  dataView?: string;
  dataPlacement?: string;
};

function initialRect() {
  const fallback = { left: 24, top: 96, width: 420, height: 560 };
  if (typeof document === 'undefined') return fallback;
  const stage = document.querySelector('[data-testid="slot-board-body-stage"]')
    ?? document.querySelector('[data-testid="slot-board"]');
  const rect = stage?.getBoundingClientRect();
  if (!rect || rect.width === 0) return fallback;
  return {
    left: rect.left + 12,
    top: rect.top + FLOATING_CONTROL_STRIP_HEIGHT,
    width: Math.min(Math.min(520, Math.max(360, rect.width * 0.52)), rect.width) - 24,
    height: Math.max(320, rect.height - FLOATING_CONTROL_STRIP_HEIGHT - 12)
  };
}

export function FloatingQuotePanel({
  testId,
  ariaLabel,
  title,
  subtitle,
  persistKey,
  onClose,
  closeLabel,
  closeOnBackdrop = true,
  headerActions,
  children,
  dataView,
  dataPlacement
}: FloatingQuotePanelProps) {
  const isDesktop = useIsDesktop();
  const [rect, setRect] = useState(initialRect);
  const { targetRef, dragStyle, isDragging, startDrag, resetDrag, rememberedSize } = useBoardDrag<HTMLElement>({
    persistKey,
    anchor: { left: rect.left, top: rect.top },
    remember: 'local',
    rememberSize: true
  });

  useLayoutEffect(() => setRect(initialRect()), []);

  const resetPlacement = () => {
    resetDrag();
    const element = targetRef.current;
    if (element && typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches) {
      element.style.width = `${rect.width}px`;
      element.style.height = `${rect.height}px`;
    }
  };

  const content = (
    <>
      <div
        aria-hidden="true"
        data-testid={`${testId}-backdrop`}
        onClick={closeOnBackdrop ? onClose : undefined}
        className="fixed inset-0 z-30 bg-slate-900/40 lg:hidden"
      />
      <section
        ref={targetRef}
        data-testid={testId}
        data-view={dataView}
        data-placement={dataPlacement}
        role="dialog"
        aria-label={ariaLabel}
        style={isDesktop
          ? { width: rememberedSize?.width ?? rect.width, height: rememberedSize?.height ?? rect.height, ...dragStyle }
          : dragStyle}
        className="panel slot-candidate-panel slot-panel-in fixed inset-x-0 bottom-0 z-40 flex max-h-[72vh] flex-col overflow-hidden rounded-t-xl border-t border-commerce-line shadow-2xl lg:inset-auto lg:z-[55] lg:max-h-[92vh] lg:min-h-[280px] lg:w-auto lg:min-w-[320px] lg:max-w-[92vw] lg:rounded-xl lg:border lg:border-commerce-line lg:shadow-xl lg:[resize:both]"
      >
        <div
          data-testid={`${testId}-handle`}
          title="드래그해서 옮기고, 더블클릭하면 원래 자리로 돌아옵니다"
          onPointerDown={startDrag}
          onDoubleClick={resetPlacement}
          className={`slot-candidate-panel__header flex items-start justify-between gap-3 border-b border-commerce-line px-4 py-3 ${isDragging ? 'lg:cursor-grabbing' : 'lg:cursor-grab'} select-none lg:touch-none`}
        >
          <div className="min-w-0 flex-1">
            <h2 className="text-base font-black text-commerce-ink">{title}</h2>
            {subtitle ? <p className="mt-0.5 text-[11px] font-bold text-slate-500">{subtitle}</p> : null}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {headerActions}
            <button
              type="button"
              aria-label={closeLabel ?? `${title} 닫기`}
              onClick={onClose}
              className="grid h-8 w-8 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-ink hover:text-commerce-ink"
            >
              <X size={15} />
            </button>
          </div>
        </div>
        {children}
      </section>
    </>
  );

  return isDesktop ? createPortal(content, document.body) : content;
}
