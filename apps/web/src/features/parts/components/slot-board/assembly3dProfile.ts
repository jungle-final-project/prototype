import { PerspectiveCamera, Vector3 } from 'three';
import type { PartCategory } from '../../../quote/aiSelection';

export type AssemblyVector3 = readonly [number, number, number];

export type AssemblyAabb = {
  id: string;
  min: AssemblyVector3;
  max: AssemblyVector3;
};

export type ProjectedAssemblyBounds = {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
};

const SOURCE_COMMIT = 'b9d3f3165b6a2e76576f929de8d9e796d40530a3';

/**
 * Frozen category-common profile derived from BuildCores OpenDB at SOURCE_COMMIT.
 *
 * The median samples below were filtered before aggregation. Missing axes use the
 * common ATX, PCIe-slot, JEDEC DIMM, and M.2-2280 dimensions described in `basis`.
 * Product attributes deliberately do not participate in this MVP profile.
 */
export const OPENDB_COMMON_PROFILE = {
  id: 'opendb-common-v1',
  millimetersPerSceneUnit: 60,
  source: {
    name: 'BuildCores OpenDB',
    commit: SOURCE_COMMIT,
    url: `https://github.com/buildcores/buildcores-open-db/tree/${SOURCE_COMMIT}`,
    license: 'ODC-By 1.0',
    licenseUrl: `https://github.com/buildcores/buildcores-open-db/blob/${SOURCE_COMMIT}/LICENSE.txt`
  },
  dimensionsMm: {
    CASE: [458, 472, 231],
    MOTHERBOARD: [244, 305, 2],
    CPU: [40, 40, 4],
    RAM: [7, 133, 38.6],
    GPU: [275, 120, 40],
    STORAGE: [80, 22, 3.5],
    PSU: [150, 86, 150],
    COOLER: [120, 120, 154]
  } satisfies Record<PartCategory, AssemblyVector3>,
  installedPositionsMm: {
    CASE: [0, 0, 0],
    MOTHERBOARD: [21, 27, -102],
    CPU: [-42, 87, -98],
    RAM: [93, 60, -81],
    GPU: [0, -57, -78],
    STORAGE: [-9, 47, -98],
    PSU: [-120, -170, -36],
    COOLER: [-42, 87, -25]
  } satisfies Record<PartCategory, AssemblyVector3>,
  storageSlotPositionsMm: [
    [-9, 47, -98],
    [-9, 20, -98]
  ] as const,
  ramModulePitchMm: 12,
  statistics: {
    CASE: {
      sampleCount: 937,
      basis: 'dimensions_mm three-axis median',
      filterMm: [120, 800],
      axisFilterMm: { width: [120, 400], height: [250, 800], depth: [250, 800] }
    },
    MOTHERBOARD: {
      sampleCount: 1689,
      basis: 'most common OpenDB form factor (ATX); standard dimensions',
      filterMm: [0, 0]
    },
    CPU: {
      sampleCount: 0,
      basis: 'common desktop package envelope; axis absent from OpenDB',
      filterMm: [0, 0]
    },
    RAM: {
      sampleCount: 346,
      basis: 'module-height median; JEDEC width and depth fill',
      filterMm: [20, 80]
    },
    GPU: {
      sampleCount: 3756,
      basis: 'length median; 2-slot median and common board height fill',
      filterMm: [150, 450],
      secondarySampleCount: 3769,
      secondaryFilterSlots: [1, 5]
    },
    STORAGE: {
      sampleCount: 1533,
      basis: 'most common OpenDB M.2 form factor (2280); standard thickness',
      filterMm: [0, 0]
    },
    PSU: {
      sampleCount: 2961,
      basis: 'ATX length median; ATX standard width and height fill',
      filterMm: [100, 250]
    },
    COOLER: {
      sampleCount: 1029,
      basis: 'reported cooler-height median; common tower footprint fill',
      filterMm: [70, 200]
    }
  }
} as const;

export const DEFAULT_ASSEMBLY_CAMERA = {
  position: [10, 6.5, 14],
  target: [0, 0, -0.3],
  fov: 35,
  near: 0.1,
  far: 100
} as const;

export const ASSEMBLY_SELECTION_OUTLINE_COLOR = '#2563eb';
export const ASSEMBLY_OCCLUDER_OPACITY = 0.15;

export function millimetersToSceneUnits(value: number) {
  return value / OPENDB_COMMON_PROFILE.millimetersPerSceneUnit;
}

export function millimetersVectorToSceneUnits(value: AssemblyVector3): AssemblyVector3 {
  return value.map(millimetersToSceneUnits) as unknown as AssemblyVector3;
}

/** Fixed common dimensions. `attributes` is accepted only to make the ignored contract explicit. */
export function assemblyDimensionsForItem(
  category: PartCategory,
  _attributes?: Readonly<Record<string, unknown>>
): AssemblyVector3 {
  return millimetersVectorToSceneUnits(OPENDB_COMMON_PROFILE.dimensionsMm[category]);
}

export function assemblyInstancePositions(
  category: PartCategory,
  requestedCount = 1
): AssemblyVector3[] {
  const count = Math.max(0, Math.min(maxVisualInstances(category), Math.floor(requestedCount)));
  if (count === 0) return [];

  if (category === 'STORAGE') {
    return OPENDB_COMMON_PROFILE.storageSlotPositionsMm
      .slice(0, count)
      .map(millimetersVectorToSceneUnits);
  }

  if (category === 'RAM') {
    const center = OPENDB_COMMON_PROFILE.installedPositionsMm.RAM;
    return Array.from({ length: count }, (_, index) => {
      const x = center[0] + (index - (count - 1) / 2) * OPENDB_COMMON_PROFILE.ramModulePitchMm;
      return millimetersVectorToSceneUnits([x, center[1], center[2]]);
    });
  }

  return [millimetersVectorToSceneUnits(OPENDB_COMMON_PROFILE.installedPositionsMm[category])];
}

export function assemblyInstalledVolumes(
  category: PartCategory,
  requestedCount = 1
): AssemblyAabb[] {
  if (category === 'COOLER') return coolerCompoundVolumes();

  const size = assemblyDimensionsForItem(category);
  return assemblyInstancePositions(category, requestedCount).map((center, index) =>
    aabbFromCenterSize(`${category.toLowerCase()}-${index}`, center, size)
  );
}

export function assemblyBoundsForCategory(category: PartCategory, requestedCount = 1): AssemblyAabb {
  const volumes = assemblyInstalledVolumes(category, requestedCount);
  if (volumes.length === 0) {
    const position = millimetersVectorToSceneUnits(OPENDB_COMMON_PROFILE.installedPositionsMm[category]);
    return aabbFromCenterSize(`${category.toLowerCase()}-empty`, position, [0, 0, 0]);
  }
  return unionAabbs(`${category.toLowerCase()}-bounds`, volumes);
}

export function aabbIntersects(left: AssemblyAabb, right: AssemblyAabb, epsilon = 1e-6) {
  return left.min[0] < right.max[0] - epsilon
    && left.max[0] > right.min[0] + epsilon
    && left.min[1] < right.max[1] - epsilon
    && left.max[1] > right.min[1] + epsilon
    && left.min[2] < right.max[2] - epsilon
    && left.max[2] > right.min[2] + epsilon;
}

export function aabbContains(container: AssemblyAabb, item: AssemblyAabb, epsilon = 1e-6) {
  return item.min[0] >= container.min[0] - epsilon
    && item.max[0] <= container.max[0] + epsilon
    && item.min[1] >= container.min[1] - epsilon
    && item.max[1] <= container.max[1] + epsilon
    && item.min[2] >= container.min[2] - epsilon
    && item.max[2] <= container.max[2] + epsilon;
}

export function projectedAssemblyBounds(
  volumes: readonly AssemblyAabb[],
  aspect: number
): ProjectedAssemblyBounds {
  const camera = new PerspectiveCamera(
    DEFAULT_ASSEMBLY_CAMERA.fov,
    aspect,
    DEFAULT_ASSEMBLY_CAMERA.near,
    DEFAULT_ASSEMBLY_CAMERA.far
  );
  camera.position.set(...DEFAULT_ASSEMBLY_CAMERA.position);
  camera.lookAt(...DEFAULT_ASSEMBLY_CAMERA.target);
  camera.updateProjectionMatrix();
  camera.updateMatrixWorld(true);

  const points = volumes.flatMap(aabbCorners).map((point) =>
    new Vector3(...point).project(camera)
  );
  return {
    minX: Math.min(...points.map((point) => point.x)),
    maxX: Math.max(...points.map((point) => point.x)),
    minY: Math.min(...points.map((point) => point.y)),
    maxY: Math.max(...points.map((point) => point.y))
  };
}

export function projectedBoundsOverlap(
  left: ProjectedAssemblyBounds,
  right: ProjectedAssemblyBounds,
  epsilon = 1e-6
) {
  return left.minX < right.maxX - epsilon
    && left.maxX > right.minX + epsilon
    && left.minY < right.maxY - epsilon
    && left.maxY > right.minY + epsilon;
}

export function resolveAssemblyOccluders(
  selectedCategory: PartCategory | null,
  aiFocusCategories: readonly PartCategory[],
  detachedCategories: ReadonlySet<PartCategory>
): PartCategory[] {
  const focusCategories = new Set<PartCategory>();
  if (selectedCategory && !detachedCategories.has(selectedCategory)) {
    focusCategories.add(selectedCategory);
  }
  for (const category of aiFocusCategories) {
    if (!detachedCategories.has(category)) focusCategories.add(category);
  }

  const occluders = new Set<PartCategory>();
  if (focusCategories.has('CPU')) occluders.add('COOLER');
  if (focusCategories.has('STORAGE')) {
    occluders.add('GPU');
    occluders.add('COOLER');
  }

  return OCCLUDER_ORDER.filter((category) =>
    occluders.has(category) && !detachedCategories.has(category)
  );
}

function maxVisualInstances(category: PartCategory) {
  if (category === 'RAM') return 4;
  if (category === 'STORAGE') return 2;
  return 1;
}

function coolerCompoundVolumes(): AssemblyAabb[] {
  const boxesMm: Array<{ id: string; center: AssemblyVector3; size: AssemblyVector3 }> = [
    { id: 'cooler-base', center: [-42, 87, -96], size: [44, 44, 12] },
    { id: 'cooler-support-left', center: [-78, 87, -59], size: [8, 64, 62] },
    { id: 'cooler-support-right', center: [-6, 87, -59], size: [8, 64, 62] },
    { id: 'cooler-tower', center: [-42, 87, 12], size: [120, 120, 80] }
  ];
  return boxesMm.map(({ id, center, size }) =>
    aabbFromCenterSize(id, millimetersVectorToSceneUnits(center), millimetersVectorToSceneUnits(size))
  );
}

function aabbFromCenterSize(
  id: string,
  center: AssemblyVector3,
  size: AssemblyVector3
): AssemblyAabb {
  return {
    id,
    min: [center[0] - size[0] / 2, center[1] - size[1] / 2, center[2] - size[2] / 2],
    max: [center[0] + size[0] / 2, center[1] + size[1] / 2, center[2] + size[2] / 2]
  };
}

function unionAabbs(id: string, volumes: readonly AssemblyAabb[]): AssemblyAabb {
  return {
    id,
    min: [
      Math.min(...volumes.map((volume) => volume.min[0])),
      Math.min(...volumes.map((volume) => volume.min[1])),
      Math.min(...volumes.map((volume) => volume.min[2]))
    ],
    max: [
      Math.max(...volumes.map((volume) => volume.max[0])),
      Math.max(...volumes.map((volume) => volume.max[1])),
      Math.max(...volumes.map((volume) => volume.max[2]))
    ]
  };
}

function aabbCorners(volume: AssemblyAabb): AssemblyVector3[] {
  return [
    [volume.min[0], volume.min[1], volume.min[2]],
    [volume.min[0], volume.min[1], volume.max[2]],
    [volume.min[0], volume.max[1], volume.min[2]],
    [volume.min[0], volume.max[1], volume.max[2]],
    [volume.max[0], volume.min[1], volume.min[2]],
    [volume.max[0], volume.min[1], volume.max[2]],
    [volume.max[0], volume.max[1], volume.min[2]],
    [volume.max[0], volume.max[1], volume.max[2]]
  ];
}

const OCCLUDER_ORDER: PartCategory[] = ['GPU', 'COOLER'];
