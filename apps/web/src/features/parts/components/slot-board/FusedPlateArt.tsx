import { useEffect, useState, type CSSProperties } from 'react';
import type { PartCategory } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';
import {
  FUSED_BOARD_SIZE,
  FUSED_PART_AREAS,
  FUSED_PART_LAYERS,
  FUSED_PLATE_BG,
  FUSED_PRELOAD_URLS
} from './fusedPlateConfig';
import { itemStickCount } from './slotBoardItemCounts';
import './FusedPlateArt.css';

type FusedPlateArtProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  flashingCategories: Set<PartCategory>;
  onSlotSelect: (category: PartCategory) => void;
};

export function FusedPlateArt({
  items,
  selectedCategory,
  flashingCategories,
  onSlotSelect
}: FusedPlateArtProps) {
  const filledCategories = new Set(items.map((item) => item.category));
  const ramSlotCount = Math.min(4, items.filter((item) => item.category === 'RAM').reduce((sum, item) => sum + itemStickCount(item), 0));
  const [hoveredCategory, setHoveredCategory] = useState<PartCategory | null>(null);
  const hasHoveredLayer = hoveredCategory
    ? hoveredCategory === 'RAM'
      ? ramSlotCount > 0 || selectedCategory === 'RAM'
      : filledCategories.has(hoveredCategory) || selectedCategory === hoveredCategory
    : false;
  const spotlightCategory = hasHoveredLayer ? hoveredCategory : null;

  useEffect(() => {
    FUSED_PRELOAD_URLS.forEach((src) => {
      const image = new Image();
      image.src = src;
    });
  }, []);

  return (
    <div data-testid="slot-board-fused-plate" className="absolute inset-0 hidden items-center justify-center bg-[#f6f7f9] lg:flex">
      <div className="relative aspect-[1672/941] w-full max-h-full">
        <img
          src={FUSED_PLATE_BG}
          alt=""
          aria-hidden="true"
          data-dimmed={spotlightCategory ? 'true' : 'false'}
          className="fused-plate-bg pointer-events-none absolute inset-0 h-full w-full select-none object-contain"
        />
        {FUSED_PART_LAYERS.map((layer) => {
          const selectedEmptyRamPreview =
            selectedCategory === 'RAM' && ramSlotCount === 0 && layer.category === 'RAM' && layer.slotIndex === 0;
          const visible = layer.category === 'RAM'
            ? (layer.slotIndex ?? 0) < ramSlotCount || selectedEmptyRamPreview
            : filledCategories.has(layer.category) || selectedCategory === layer.category;
          const focused = selectedCategory === layer.category;
          const hovered = spotlightCategory === layer.category;
          const dimmed = Boolean(spotlightCategory && !hovered);
          const mounting = visible && flashingCategories.has(layer.category);
          return (
            <img
              key={layer.src}
              data-testid={`slot-fused-layer-${layer.category}${layer.slotIndex !== undefined ? `-${layer.slotIndex + 1}` : ''}`}
              data-visible={visible ? 'true' : 'false'}
              data-hovered={hovered ? 'true' : 'false'}
              data-dimmed={dimmed ? 'true' : 'false'}
              data-mounting={mounting ? 'true' : 'false'}
              src={layer.src}
              alt=""
              aria-hidden="true"
              className={`fused-part-layer pointer-events-none absolute inset-0 h-full w-full select-none object-contain ${
                visible ? 'opacity-100' : 'opacity-0'
              } ${focused ? 'z-20' : 'z-10'}`}
            />
          );
        })}
        {FUSED_PART_AREAS.map((area) => (
          <button
            key={area.category}
            type="button"
            data-testid={`slot-fused-area-${area.category}`}
            aria-label={`${area.label} 부품 선택`}
            aria-pressed={selectedCategory === area.category}
            onPointerEnter={() => setHoveredCategory(area.category)}
            onPointerLeave={() => setHoveredCategory((current) => current === area.category ? null : current)}
            onFocus={() => setHoveredCategory(area.category)}
            onBlur={() => setHoveredCategory((current) => current === area.category ? null : current)}
            onClick={() => onSlotSelect(area.category)}
            className="absolute z-30 cursor-pointer rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
            style={fusedAreaStyle(area.box)}
          />
        ))}
      </div>
    </div>
  );
}

function fusedAreaStyle(box: { x: number; y: number; w: number; h: number }): CSSProperties {
  return {
    left: `${(box.x / FUSED_BOARD_SIZE.width) * 100}%`,
    top: `${(box.y / FUSED_BOARD_SIZE.height) * 100}%`,
    width: `${(box.w / FUSED_BOARD_SIZE.width) * 100}%`,
    height: `${(box.h / FUSED_BOARD_SIZE.height) * 100}%`
  };
}
