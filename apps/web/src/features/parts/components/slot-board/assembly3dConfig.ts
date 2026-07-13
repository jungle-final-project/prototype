import type { PartCategory } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';
import {
  assemblyDimensionsForItem,
  millimetersToSceneUnits,
  millimetersVectorToSceneUnits,
  OPENDB_COMMON_PROFILE
} from './assembly3dProfile';

export type AssemblyPose = {
  position: readonly [number, number, number];
  rotation: readonly [number, number, number];
};

export type AssemblyPartConfig = {
  installedPose: AssemblyPose;
  detachedPose: AssemblyPose;
  assemblyOrder: number;
  maxVisualInstances: number;
  baseColor: string;
  detailColor: string;
  mountType?: 'M2';
};

const INSTALLED_ROTATION = [0, 0, 0] as const;
const mm = millimetersToSceneUnits;

const caseSize = assemblyDimensionsForItem('CASE');
const boardSize = assemblyDimensionsForItem('MOTHERBOARD');
const cpuSize = assemblyDimensionsForItem('CPU');
const ramSize = assemblyDimensionsForItem('RAM');
const gpuSize = assemblyDimensionsForItem('GPU');
const storageSize = assemblyDimensionsForItem('STORAGE');
const psuSize = assemblyDimensionsForItem('PSU');
const coolerSize = assemblyDimensionsForItem('COOLER');

/** Shared profile-backed dimensions keep the visible geometry and collision model aligned. */
export const ASSEMBLY_GEOMETRY = {
  CASE: {
    width: caseSize[0],
    height: caseSize[1],
    depth: caseSize[2],
    frameThickness: mm(10),
    panelThickness: mm(3)
  },
  MOTHERBOARD: {
    width: boardSize[0],
    height: boardSize[1],
    depth: boardSize[2],
    renderDepth: Math.max(boardSize[2], mm(4)),
    pcieWidth: mm(185),
    pcieHeight: mm(5),
    pcieDepth: mm(6),
    pcieOffsetX: mm(-2),
    pcieOffsetZ: mm(5),
    pcieOffsetYs: [mm(-95), mm(-68), mm(-41)]
  },
  CPU: {
    bodyWidth: cpuSize[0],
    bodyHeight: cpuSize[1],
    bodyDepth: cpuSize[2],
    undersideWidth: mm(36),
    undersideHeight: mm(36),
    undersideDepth: mm(1.5),
    undersideOffsetZ: mm(-2.75)
  },
  RAM: {
    moduleWidth: ramSize[0],
    moduleHeight: ramSize[1],
    moduleDepth: ramSize[2],
    moduleSpacing: mm(OPENDB_COMMON_PROFILE.ramModulePitchMm)
  },
  GPU: {
    width: gpuSize[0],
    height: gpuSize[1],
    depth: gpuSize[2]
  },
  STORAGE: {
    width: storageSize[0],
    height: storageSize[1],
    boardDepth: storageSize[2],
    instanceSpacing: mm(27),
    chipWidth: mm(14),
    chipHeight: mm(14),
    chipDepth: mm(1.2),
    chipOffsetZ: mm(2.25)
  },
  PSU: {
    width: psuSize[0],
    height: psuSize[1],
    depth: psuSize[2]
  },
  COOLER: {
    width: coolerSize[0],
    height: coolerSize[1],
    depth: coolerSize[2],
    baseSize: millimetersVectorToSceneUnits([44, 44, 12]),
    towerSize: millimetersVectorToSceneUnits([120, 120, 80]),
    towerOffsetZ: mm(37),
    supportOffsetX: mm(36),
    supportHeight: mm(64),
    supportDepth: mm(62),
    supportOffsetZ: mm(-34)
  }
} as const;

export const ASSEMBLY_CATEGORIES: PartCategory[] = [
  'CASE',
  'MOTHERBOARD',
  'CPU',
  'RAM',
  'GPU',
  'STORAGE',
  'PSU',
  'COOLER'
];

function installedPose(category: PartCategory): AssemblyPose {
  return {
    position: millimetersVectorToSceneUnits(OPENDB_COMMON_PROFILE.installedPositionsMm[category]),
    rotation: INSTALLED_ROTATION
  };
}

/**
 * CASE pose applies to its removable side panel. The chassis remains fixed while
 * the panel follows the same detach animation contract as the first MVP.
 */
export const ASSEMBLY_PART_CONFIG: Record<PartCategory, AssemblyPartConfig> = {
  CASE: {
    installedPose: installedPose('CASE'),
    detachedPose: { position: [5.2, 0.2, 1.5], rotation: [0, -0.18, 0] },
    assemblyOrder: 0,
    maxVisualInstances: 1,
    baseColor: '#2f3742',
    detailColor: '#6b7785'
  },
  PSU: {
    installedPose: installedPose('PSU'),
    detachedPose: { position: [-5.1, mm(-170), 1.8], rotation: [0, -0.18, 0] },
    assemblyOrder: 1,
    maxVisualInstances: 1,
    baseColor: '#3e4854',
    detailColor: '#151b22'
  },
  GPU: {
    installedPose: installedPose('GPU'),
    detachedPose: { position: [0, mm(-57), 4.2], rotation: [0.08, 0, 0] },
    assemblyOrder: 2,
    maxVisualInstances: 1,
    baseColor: '#222a34',
    detailColor: '#738092'
  },
  COOLER: {
    installedPose: installedPose('COOLER'),
    detachedPose: { position: [mm(-42), 4.3, 2.5], rotation: [0.12, 0, 0] },
    assemblyOrder: 3,
    maxVisualInstances: 1,
    baseColor: '#697686',
    detailColor: '#d3dae2'
  },
  STORAGE: {
    installedPose: installedPose('STORAGE'),
    detachedPose: { position: [4.2, mm(47), 2.5], rotation: [0, 0.22, 0] },
    assemblyOrder: 4,
    maxVisualInstances: 2,
    baseColor: '#0f766e',
    detailColor: '#18212b',
    mountType: 'M2'
  },
  RAM: {
    installedPose: installedPose('RAM'),
    detachedPose: { position: [3.8, 3.4, 2.8], rotation: [0, 0.18, -0.08] },
    assemblyOrder: 5,
    maxVisualInstances: 4,
    baseColor: '#313c49',
    detailColor: '#d2a74a'
  },
  CPU: {
    installedPose: installedPose('CPU'),
    detachedPose: { position: [mm(-42), 3.5, 3.3], rotation: [0.08, 0, 0] },
    assemblyOrder: 6,
    maxVisualInstances: 1,
    baseColor: '#c4cbd3',
    detailColor: '#9a6b16'
  },
  MOTHERBOARD: {
    installedPose: installedPose('MOTHERBOARD'),
    detachedPose: { position: [-5.1, mm(27), 1.2], rotation: [0, -0.22, 0] },
    assemblyOrder: 7,
    maxVisualInstances: 1,
    baseColor: '#173a39',
    detailColor: '#738092'
  }
};

export type AssemblyVisualCounts = Record<PartCategory, number>;

/** Converts draft quantities into the deliberately bounded visual instance set. */
export function assemblyVisualCounts(items: QuoteDraftItem[]): AssemblyVisualCounts {
  const counts: AssemblyVisualCounts = {
    CPU: 0,
    MOTHERBOARD: 0,
    RAM: 0,
    GPU: 0,
    STORAGE: 0,
    PSU: 0,
    CASE: 0,
    COOLER: 0
  };

  for (const item of items) {
    if (!ASSEMBLY_CATEGORIES.includes(item.category as PartCategory)) continue;

    const category = item.category as PartCategory;
    const quantity = Number.isFinite(item.quantity) ? Math.max(0, Math.floor(item.quantity)) : 0;
    if (quantity === 0) continue;

    if (category === 'RAM' || category === 'STORAGE') {
      counts[category] = Math.min(
        ASSEMBLY_PART_CONFIG[category].maxVisualInstances,
        counts[category] + quantity
      );
    } else {
      counts[category] = 1;
    }
  }

  return counts;
}
