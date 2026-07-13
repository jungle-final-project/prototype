import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, RotateCcw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { handlePartImageError, partImageUrl } from '../../parts/partDisplay';
import { listHomeRecommendedParts, recordRecommendationEvent } from '../../parts/partsApi';
import type { HomeRecommendedPart } from '../../parts/types';
import './HomeProductShelf.css';

const HOME_PRODUCT_LIMIT = 8;
const SKELETON_COUNT = 6;
const SCROLL_EDGE_TOLERANCE = 2;
const HOME_CATEGORY_LABELS: Record<string, string> = {
  CPU: 'CPU',
  GPU: 'GPU',
  MOTHERBOARD: '메인보드',
  RAM: '메모리',
  STORAGE: 'SSD',
  PSU: '파워',
  CASE: '케이스',
  COOLER: '쿨러'
};

export function HomeProductShelf() {
  const trackRef = useRef<HTMLDivElement>(null);
  const impressedIdsRef = useRef(new Set<string>());
  const [hasOverflow, setHasOverflow] = useState(false);
  const [canScrollPrevious, setCanScrollPrevious] = useState(false);
  const [canScrollNext, setCanScrollNext] = useState(false);
  const partsQuery = useQuery({
    queryKey: ['recommendations', 'modern-home-parts', HOME_PRODUCT_LIMIT],
    queryFn: () => listHomeRecommendedParts(HOME_PRODUCT_LIMIT),
    staleTime: 60_000,
    retry: false
  });
  const items = partsQuery.data?.items ?? [];

  useEffect(() => {
    for (const item of items) {
      if (impressedIdsRef.current.has(item.recommendationId)) continue;
      impressedIdsRef.current.add(item.recommendationId);
      void recordRecommendationEvent({
        eventType: 'IMPRESSION',
        sourceSurface: 'HOME_RECOMMENDED_PARTS',
        recommendationId: item.recommendationId,
        partId: item.part.id,
        category: item.part.category,
        rankPosition: item.rankPosition,
        idempotencyKey: `home-impression-${item.recommendationId}`
      }).catch(() => undefined);
    }
  }, [items]);

  const updateScrollState = useCallback(() => {
    const track = trackRef.current;
    if (!track) return;
    const maxScrollLeft = Math.max(0, track.scrollWidth - track.clientWidth);
    const overflow = maxScrollLeft > SCROLL_EDGE_TOLERANCE;
    setHasOverflow(overflow);
    setCanScrollPrevious(overflow && track.scrollLeft > SCROLL_EDGE_TOLERANCE);
    setCanScrollNext(overflow && track.scrollLeft < maxScrollLeft - SCROLL_EDGE_TOLERANCE);
  }, []);

  useEffect(() => {
    const track = trackRef.current;
    if (!track || items.length === 0) {
      setHasOverflow(false);
      setCanScrollPrevious(false);
      setCanScrollNext(false);
      return;
    }

    const frame = window.requestAnimationFrame(updateScrollState);
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(updateScrollState);
    resizeObserver?.observe(track);
    track.addEventListener('scroll', updateScrollState, { passive: true });
    window.addEventListener('resize', updateScrollState);

    return () => {
      window.cancelAnimationFrame(frame);
      resizeObserver?.disconnect();
      track.removeEventListener('scroll', updateScrollState);
      window.removeEventListener('resize', updateScrollState);
    };
  }, [items.length, updateScrollState]);

  function scrollShelf(direction: -1 | 1) {
    const track = trackRef.current;
    if (!track) return;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    track.scrollBy({
      left: direction * Math.max(220, track.clientWidth * 0.82),
      behavior: reducedMotion ? 'auto' : 'smooth'
    });
  }

  function handleTrackKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      scrollShelf(-1);
    } else if (event.key === 'ArrowRight') {
      event.preventDefault();
      scrollShelf(1);
    }
  }

  return (
    <section
      data-testid="home-product-shelf"
      className="home-product-shelf"
      aria-labelledby="home-product-shelf-title"
    >
      <div className="home-product-shelf__header">
        <div className="home-product-shelf__heading">
          <h1 id="home-product-shelf-title">추천하는 부품</h1>
          <p>현재 등록된 가격과 내부 추천 순서로 부품을 살펴보세요.</p>
        </div>
        <div className="home-product-shelf__actions">
          {hasOverflow ? (
            <div className="home-product-shelf__controls" aria-label="추천 부품 목록 이동">
              <button
                type="button"
                data-testid="home-product-shelf-prev"
                aria-label="이전 추천 부품 보기"
                disabled={!canScrollPrevious}
                onClick={() => scrollShelf(-1)}
              >
                <ArrowLeft size={19} aria-hidden="true" />
              </button>
              <button
                type="button"
                data-testid="home-product-shelf-next"
                aria-label="다음 추천 부품 보기"
                disabled={!canScrollNext}
                onClick={() => scrollShelf(1)}
              >
                <ArrowRight size={19} aria-hidden="true" />
              </button>
            </div>
          ) : null}
          <Link to="/parts" className="home-product-shelf__all-link">
            전체 부품 보기
            <ArrowRight size={17} aria-hidden="true" />
          </Link>
        </div>
      </div>

      {partsQuery.isLoading ? <HomeProductShelfSkeleton /> : null}

      {partsQuery.isError ? (
        <div className="home-product-shelf__state" role="alert">
          <div>
            <strong>추천 부품을 불러오지 못했습니다</strong>
            <p>잠시 후 다시 시도하거나 전체 부품에서 직접 확인해 주세요.</p>
          </div>
          <button
            type="button"
            disabled={partsQuery.isFetching}
            onClick={() => void partsQuery.refetch()}
          >
            <RotateCcw size={17} aria-hidden="true" />
            {partsQuery.isFetching ? '다시 불러오는 중' : '다시 시도'}
          </button>
        </div>
      ) : null}

      {!partsQuery.isLoading && !partsQuery.isError && items.length === 0 ? (
        <div className="home-product-shelf__state home-product-shelf__state--empty">
          <div>
            <strong>추천할 부품을 찾지 못했습니다</strong>
            <p>전체 부품에서 카테고리별 상품을 확인해 보세요.</p>
          </div>
        </div>
      ) : null}

      {items.length > 0 ? (
        <div
          ref={trackRef}
          data-testid="home-product-shelf-track"
          className="home-product-shelf__track"
          role="region"
          aria-label="추천 부품 상품 목록"
          tabIndex={0}
          onKeyDown={handleTrackKeyDown}
        >
          <ul className="home-product-shelf__list">
            {items.map((item) => (
              <li key={item.recommendationId} className="home-product-shelf__item">
                <HomeProductCard item={item} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

function HomeProductCard({ item }: { item: HomeRecommendedPart }) {
  const href = `/parts/${item.part.id}?recId=${encodeURIComponent(item.recommendationId)}&recSurface=HOME_RECOMMENDED_PARTS&rank=${item.rankPosition}`;
  const categoryLabel = HOME_CATEGORY_LABELS[item.part.category] ?? item.part.category;
  const accessiblePrice = `${item.part.price.toLocaleString()}원`;

  return (
    <Link
      to={href}
      data-testid={`home-product-card-${item.part.id}`}
      aria-label={`${item.part.name}, ${categoryLabel}, ${accessiblePrice} 상품 정보 보기`}
      className="home-product-card"
      onClick={() => {
        void recordRecommendationEvent({
          eventType: 'CLICK',
          sourceSurface: 'HOME_RECOMMENDED_PARTS',
          recommendationId: item.recommendationId,
          partId: item.part.id,
          category: item.part.category,
          rankPosition: item.rankPosition,
          idempotencyKey: `home-click-${item.recommendationId}`
        }).catch(() => undefined);
      }}
    >
      <span className="home-product-card__media">
        <img
          src={partImageUrl(item.part)}
          alt={`${item.part.name} 제품 사진`}
          loading="lazy"
          onError={(event) => handlePartImageError(event, item.part.category)}
        />
      </span>
      <span className="home-product-card__category">{categoryLabel}</span>
      <span className="home-product-card__name">{item.part.name}</span>
      <span className="home-product-card__price">{accessiblePrice}</span>
    </Link>
  );
}

function HomeProductShelfSkeleton() {
  return (
    <div className="home-product-shelf__track home-product-shelf__track--skeleton" aria-label="추천 부품 불러오는 중">
      <ul className="home-product-shelf__list">
        {Array.from({ length: SKELETON_COUNT }, (_, index) => (
          <li
            key={index}
            data-testid="home-product-shelf-skeleton"
            className="home-product-shelf__item home-product-shelf__skeleton"
            aria-hidden="true"
          >
            <span className="home-product-shelf__skeleton-media" />
            <span className="home-product-shelf__skeleton-line home-product-shelf__skeleton-line--category" />
            <span className="home-product-shelf__skeleton-line home-product-shelf__skeleton-line--name" />
            <span className="home-product-shelf__skeleton-line home-product-shelf__skeleton-line--price" />
          </li>
        ))}
      </ul>
    </div>
  );
}
