import { useEffect, useRef, useState, type ComponentRef, type ReactNode } from 'react';
import { Edges, Html, OrbitControls, useCursor } from '@react-three/drei';
import { Canvas, useFrame, useThree, type ThreeEvent } from '@react-three/fiber';
import { DoubleSide, Group, MathUtils } from 'three';
import type { PartCategory } from '../../../quote/aiSelection';
import type { QuoteDraftItem } from '../../types';
import {
  ASSEMBLY_CATEGORIES,
  ASSEMBLY_GEOMETRY,
  ASSEMBLY_PART_CONFIG,
  assemblyVisualCounts,
  type AssemblyPose
} from './assembly3dConfig';
import {
  ASSEMBLY_OCCLUDER_OPACITY,
  ASSEMBLY_SELECTION_OUTLINE_COLOR,
  DEFAULT_ASSEMBLY_CAMERA,
  OPENDB_COMMON_PROFILE,
  resolveAssemblyOccluders,
  type AssemblyVector3
} from './assembly3dProfile';

type AssemblyStatus = 'PASS' | 'WARN' | 'FAIL';

export type ThreeDAssemblySceneProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  detachedCategories: ReadonlySet<PartCategory>;
  aiFocusCategories: PartCategory[];
  statusByCategory: ReadonlyMap<string, AssemblyStatus>;
  reducedMotion: boolean;
  cameraResetKey: number;
  onSelectCategory: (category: PartCategory) => void;
};

type PartVisual = {
  selected: boolean;
  dimmed: boolean;
  occluded: boolean;
  aiFocused: boolean;
  status: AssemblyStatus;
  baseColor: string;
  detailColor: string;
};

const CATEGORY_LABEL: Record<PartCategory, string> = {
  CPU: 'CPU',
  MOTHERBOARD: '메인보드',
  RAM: 'RAM',
  GPU: 'GPU',
  STORAGE: 'M.2 SSD',
  PSU: '파워',
  CASE: '케이스',
  COOLER: '쿨러'
};

const STATUS_LABEL: Record<AssemblyStatus, string> = {
  PASS: '호환 가능',
  WARN: '간섭 주의',
  FAIL: '장착 불가'
};

export function ThreeDAssemblyScene({
  items,
  selectedCategory,
  detachedCategories,
  aiFocusCategories,
  statusByCategory,
  reducedMotion,
  cameraResetKey,
  onSelectCategory
}: ThreeDAssemblySceneProps) {
  const counts = assemblyVisualCounts(items);
  const visibleCategories = ASSEMBLY_CATEGORIES.filter((category) => counts[category] > 0);
  const hasAiFocus = aiFocusCategories.length > 0;
  const aiFocusSet = new Set(aiFocusCategories);
  const occludingCategories = resolveAssemblyOccluders(
    selectedCategory,
    aiFocusCategories,
    detachedCategories
  );
  const occludingSet = new Set(occludingCategories);
  const statusOutlines = visibleCategories
    .filter((category) => (statusByCategory.get(category) ?? 'PASS') !== 'PASS')
    .map((category) => `${category}:${statusByCategory.get(category)}`)
    .join(',');
  const selectedBaseColor = selectedCategory
    ? ASSEMBLY_PART_CONFIG[selectedCategory].baseColor
    : '';

  return (
    <Canvas
      data-testid="assembly-3d-canvas"
      data-layout-profile={OPENDB_COMMON_PROFILE.id}
      data-visible-categories={visibleCategories.join(',')}
      data-ram-count={counts.RAM}
      data-storage-count={counts.STORAGE}
      data-selected-category={selectedCategory ?? ''}
      data-selected-base-color={selectedBaseColor}
      data-selection-outline-color={ASSEMBLY_SELECTION_OUTLINE_COLOR}
      data-status-outline-categories={statusOutlines}
      data-occluding-categories={occludingCategories.join(',')}
      data-occluder-opacity={ASSEMBLY_OCCLUDER_OPACITY}
      data-camera-position={DEFAULT_ASSEMBLY_CAMERA.position.join(',')}
      data-camera-target={DEFAULT_ASSEMBLY_CAMERA.target.join(',')}
      data-camera-fov={DEFAULT_ASSEMBLY_CAMERA.fov}
      data-scene-background="#eef2f6"
      aria-label="선택한 PC 부품 3D 조립 장면"
      aria-describedby="assembly-3d-help"
      camera={{
        position: DEFAULT_ASSEMBLY_CAMERA.position,
        fov: DEFAULT_ASSEMBLY_CAMERA.fov,
        near: DEFAULT_ASSEMBLY_CAMERA.near,
        far: DEFAULT_ASSEMBLY_CAMERA.far
      }}
      dpr={[1, 1.5]}
      gl={{ antialias: true, alpha: false, powerPreference: 'high-performance' }}
      style={{ width: '100%', height: '100%', minHeight: 360, background: '#eef2f6' }}
    >
      <color attach="background" args={['#eef2f6']} />
      <hemisphereLight color="#f7fafc" groundColor="#56616d" intensity={1.05} />
      <directionalLight position={[7, 10, 11]} intensity={1.45} color="#fffaf0" />
      <directionalLight position={[-8, 3, 7]} intensity={0.52} color="#dbeafe" />
      <directionalLight position={[2, 6, -10]} intensity={0.72} color="#ffffff" />

      {visibleCategories.map((category) => {
        const config = ASSEMBLY_PART_CONFIG[category];
        const visual: PartVisual = {
          selected: selectedCategory === category,
          dimmed: hasAiFocus && !aiFocusSet.has(category),
          occluded: occludingSet.has(category),
          aiFocused: aiFocusSet.has(category),
          status: statusByCategory.get(category) ?? 'PASS',
          baseColor: config.baseColor,
          detailColor: config.detailColor
        };

        if (category === 'CASE') {
          return (
            <CaseModel
              key={category}
              detached={detachedCategories.has(category)}
              reducedMotion={reducedMotion}
              visual={visual}
              onSelect={() => onSelectCategory(category)}
            />
          );
        }

        return (
          <AnimatedPart
            key={category}
            category={category}
            detached={detachedCategories.has(category)}
            reducedMotion={reducedMotion}
            onSelect={() => onSelectCategory(category)}
          >
            <PartModel category={category} count={counts[category]} visual={visual} />
            <CategoryFeedback category={category} count={counts[category]} visual={visual} />
          </AnimatedPart>
        );
      })}

      <ResettableOrbitControls resetKey={cameraResetKey} reducedMotion={reducedMotion} />
    </Canvas>
  );
}

function ResettableOrbitControls({
  resetKey,
  reducedMotion
}: {
  resetKey: number;
  reducedMotion: boolean;
}) {
  const controlsRef = useRef<ComponentRef<typeof OrbitControls>>(null);
  const { camera } = useThree();

  useEffect(() => {
    camera.position.set(...DEFAULT_ASSEMBLY_CAMERA.position);
    camera.up.set(0, 1, 0);
    camera.lookAt(...DEFAULT_ASSEMBLY_CAMERA.target);
    if (controlsRef.current) {
      controlsRef.current.target.set(...DEFAULT_ASSEMBLY_CAMERA.target);
      controlsRef.current.update();
    }
  }, [camera, resetKey]);

  return (
    <OrbitControls
      ref={controlsRef}
      enablePan={false}
      enableDamping={!reducedMotion}
      dampingFactor={0.08}
      minDistance={8}
      maxDistance={25}
      minPolarAngle={Math.PI * 0.16}
      maxPolarAngle={Math.PI * 0.78}
    />
  );
}

function AnimatedPart({
  category,
  detached,
  reducedMotion,
  onSelect,
  children
}: {
  category: PartCategory;
  detached: boolean;
  reducedMotion: boolean;
  onSelect: () => void;
  children: ReactNode;
}) {
  const config = ASSEMBLY_PART_CONFIG[category];
  return (
    <SelectablePart onSelect={onSelect}>
      <AnimatedPose
        installedPose={config.installedPose}
        detachedPose={config.detachedPose}
        detached={detached}
        reducedMotion={reducedMotion}
      >
        {children}
      </AnimatedPose>
    </SelectablePart>
  );
}

function AnimatedPose({
  installedPose,
  detachedPose,
  detached,
  reducedMotion,
  children
}: {
  installedPose: AssemblyPose;
  detachedPose: AssemblyPose;
  detached: boolean;
  reducedMotion: boolean;
  children: ReactNode;
}) {
  const groupRef = useRef<Group>(null);
  const initialPose = detached && reducedMotion ? detachedPose : installedPose;

  useFrame((_, frameDelta) => {
    const group = groupRef.current;
    if (!group) return;
    const target = detached ? detachedPose : installedPose;

    if (reducedMotion) {
      applyPose(group, target);
      return;
    }

    const delta = Math.min(frameDelta, 0.05);
    group.position.set(
      MathUtils.damp(group.position.x, target.position[0], 12, delta),
      MathUtils.damp(group.position.y, target.position[1], 12, delta),
      MathUtils.damp(group.position.z, target.position[2], 12, delta)
    );
    group.rotation.set(
      MathUtils.damp(group.rotation.x, target.rotation[0], 12, delta),
      MathUtils.damp(group.rotation.y, target.rotation[1], 12, delta),
      MathUtils.damp(group.rotation.z, target.rotation[2], 12, delta)
    );
  });

  return (
    <group ref={groupRef} position={[...initialPose.position]} rotation={[...initialPose.rotation]}>
      {children}
    </group>
  );
}

function applyPose(group: Group, pose: AssemblyPose) {
  group.position.set(...pose.position);
  group.rotation.set(...pose.rotation);
}

function SelectablePart({ children, onSelect }: { children: ReactNode; onSelect: () => void }) {
  const [hovered, setHovered] = useState(false);
  useCursor(hovered);

  const select = (event: ThreeEvent<MouseEvent>) => {
    event.stopPropagation();
    onSelect();
  };

  return (
    <group
      onClick={select}
      onPointerOver={(event) => {
        event.stopPropagation();
        setHovered(true);
      }}
      onPointerOut={() => setHovered(false)}
    >
      {children}
    </group>
  );
}

function PartMaterial({
  visual,
  detail = false,
  color,
  opacity = 1,
  metalness = 0.18,
  roughness = 0.58
}: {
  visual: PartVisual;
  detail?: boolean;
  color?: string;
  opacity?: number;
  metalness?: number;
  roughness?: number;
}) {
  const actualOpacity = opacity * (visual.occluded
    ? ASSEMBLY_OCCLUDER_OPACITY
    : visual.dimmed
      ? 0.28
      : 1);
  const baseColor = color ?? (detail ? visual.detailColor : visual.baseColor);

  return (
    <meshStandardMaterial
      color={baseColor}
      emissive={visual.aiFocused && !visual.occluded ? baseColor : '#111827'}
      emissiveIntensity={visual.aiFocused && !visual.occluded ? 0.09 : 0}
      metalness={metalness}
      roughness={roughness}
      transparent={actualOpacity < 1}
      opacity={actualOpacity}
      depthWrite={actualOpacity >= 0.95}
    />
  );
}

function PartModel({ category, count, visual }: { category: PartCategory; count: number; visual: PartVisual }) {
  switch (category) {
    case 'MOTHERBOARD':
      return <MotherboardModel visual={visual} />;
    case 'CPU':
      return <CpuModel visual={visual} />;
    case 'RAM':
      return <RamModel visual={visual} count={count} />;
    case 'GPU':
      return <GpuModel visual={visual} />;
    case 'STORAGE':
      return <StorageModel visual={visual} count={count} />;
    case 'PSU':
      return <PsuModel visual={visual} />;
    case 'COOLER':
      return <CoolerModel visual={visual} />;
    case 'CASE':
      return null;
  }
}

function CaseModel({
  detached,
  reducedMotion,
  visual,
  onSelect
}: {
  detached: boolean;
  reducedMotion: boolean;
  visual: PartVisual;
  onSelect: () => void;
}) {
  const geometry = ASSEMBLY_GEOMETRY.CASE;
  const panelConfig = ASSEMBLY_PART_CONFIG.CASE;
  const halfWidth = geometry.width / 2;
  const halfHeight = geometry.height / 2;
  const halfDepth = geometry.depth / 2;
  const rail = geometry.frameThickness;
  const panelZ = halfDepth + geometry.panelThickness / 2;

  return (
    <SelectablePart onSelect={onSelect}>
      <group>
        <mesh position={[0, 0, -halfDepth + geometry.panelThickness / 2]}>
          <boxGeometry args={[geometry.width, geometry.height, geometry.panelThickness]} />
          <PartMaterial visual={visual} />
        </mesh>
        <mesh position={[0, halfHeight - rail / 2, 0]}>
          <boxGeometry args={[geometry.width, rail, geometry.depth]} />
          <PartMaterial visual={visual} detail />
        </mesh>
        <mesh position={[0, -halfHeight + rail / 2, 0]}>
          <boxGeometry args={[geometry.width, rail, geometry.depth]} />
          <PartMaterial visual={visual} detail />
        </mesh>
        {[-1, 1].flatMap((xSign) => [-1, 1].map((zSign) => (
          <mesh
            key={`${xSign}-${zSign}`}
            position={[
              xSign * (halfWidth - rail / 2),
              0,
              zSign * (halfDepth - rail / 2)
            ]}
          >
            <boxGeometry args={[rail, geometry.height - rail * 2, rail]} />
            <PartMaterial visual={visual} />
          </mesh>
        )))}
        <AnimatedPose
          installedPose={panelConfig.installedPose}
          detachedPose={panelConfig.detachedPose}
          detached={detached}
          reducedMotion={reducedMotion}
        >
          <mesh position={[0, 0, panelZ]} raycast={() => undefined} renderOrder={5}>
            <boxGeometry args={[
              geometry.width - rail * 1.4,
              geometry.height - rail * 1.4,
              geometry.panelThickness
            ]} />
            <meshPhysicalMaterial
              color="#cbd5e1"
              transparent
              opacity={visual.dimmed ? 0.025 : 0.08}
              roughness={0.2}
              metalness={0}
              depthWrite={false}
              side={DoubleSide}
            />
          </mesh>
          {[
            [0, halfHeight - rail, panelZ] as AssemblyVector3,
            [0, -halfHeight + rail, panelZ] as AssemblyVector3
          ].map((position, index) => (
            <mesh key={index} position={[...position]} raycast={() => undefined}>
              <boxGeometry args={[geometry.width - rail, rail / 2, rail / 2]} />
              <PartMaterial visual={visual} detail />
            </mesh>
          ))}
        </AnimatedPose>
        <CategoryFeedback category="CASE" count={1} visual={visual} />
      </group>
    </SelectablePart>
  );
}

function MotherboardModel({ visual }: { visual: PartVisual }) {
  const geometry = ASSEMBLY_GEOMETRY.MOTHERBOARD;
  return (
    <group>
      <mesh>
        <boxGeometry args={[geometry.width, geometry.height, geometry.renderDepth]} />
        <PartMaterial visual={visual} />
      </mesh>
      <mesh position={[-1.05, 1, 0.09]}>
        <boxGeometry args={[0.82, 0.82, 0.11]} />
        <PartMaterial visual={visual} color="#94a3b8" metalness={0.5} />
      </mesh>
      <mesh position={[-1.05, 1, 0.16]}>
        <boxGeometry args={[0.66, 0.66, 0.04]} />
        <PartMaterial visual={visual} color="#26303a" />
      </mesh>
      {[-1.58, -1.24, -0.9].map((x) => (
        <mesh key={`vrm-${x}`} position={[x, 1.9, 0.13]}>
          <boxGeometry args={[0.22, 0.72, 0.18]} />
          <PartMaterial visual={visual} detail metalness={0.4} />
        </mesh>
      ))}
      {[0.9, 1.1, 1.3, 1.5].map((x) => (
        <mesh key={`dimm-${x}`} position={[x, 0.55, 0.12]}>
          <boxGeometry args={[0.07, 2.45, 0.12]} />
          <PartMaterial visual={visual} color="#101820" />
        </mesh>
      ))}
      {geometry.pcieOffsetYs.map((y) => (
        <mesh key={`pcie-${y}`} position={[geometry.pcieOffsetX, y, geometry.pcieOffsetZ]}>
          <boxGeometry args={[geometry.pcieWidth, geometry.pcieHeight, geometry.pcieDepth]} />
          <PartMaterial visual={visual} color="#aab4c0" metalness={0.45} />
        </mesh>
      ))}
      {[0.333, -0.117].map((y) => (
        <group key={`m2-${y}`} position={[-0.5, y, 0.11]}>
          <mesh>
            <boxGeometry args={[1.45, 0.42, 0.045]} />
            <PartMaterial visual={visual} color="#0b2524" />
          </mesh>
          <mesh position={[0.68, 0, 0.04]}>
            <cylinderGeometry args={[0.04, 0.04, 0.04, 12]} />
            <PartMaterial visual={visual} color="#c8a94b" metalness={0.65} />
          </mesh>
        </group>
      ))}
      <mesh position={[1.25, -1.55, 0.18]}>
        <boxGeometry args={[0.72, 0.72, 0.24]} />
        <PartMaterial visual={visual} detail metalness={0.38} />
      </mesh>
    </group>
  );
}

function CpuModel({ visual }: { visual: PartVisual }) {
  const geometry = ASSEMBLY_GEOMETRY.CPU;
  return (
    <group>
      <mesh>
        <boxGeometry args={[geometry.bodyWidth, geometry.bodyHeight, geometry.bodyDepth]} />
        <PartMaterial visual={visual} metalness={0.62} roughness={0.32} />
      </mesh>
      <mesh position={[0, 0, geometry.undersideOffsetZ]}>
        <boxGeometry args={[
          geometry.undersideWidth,
          geometry.undersideHeight,
          geometry.undersideDepth
        ]} />
        <PartMaterial visual={visual} detail metalness={0.5} />
      </mesh>
      <mesh position={[0, 0, geometry.bodyDepth / 2 + 0.012]}>
        <boxGeometry args={[geometry.bodyWidth * 0.68, geometry.bodyHeight * 0.08, 0.012]} />
        <PartMaterial visual={visual} color="#e5e7eb" />
      </mesh>
    </group>
  );
}

function RamModel({ visual, count }: { visual: PartVisual; count: number }) {
  const geometry = ASSEMBLY_GEOMETRY.RAM;
  const boundWidth = geometry.moduleWidth + geometry.moduleSpacing * Math.max(0, count - 1);
  return (
    <group>
      {Array.from({ length: count }, (_, index) => {
        const x = (index - (count - 1) / 2) * geometry.moduleSpacing;
        return (
          <group key={index} position={[x, 0, 0]}>
            <mesh>
              <boxGeometry args={[geometry.moduleWidth, geometry.moduleHeight, geometry.moduleDepth]} />
              <PartMaterial visual={visual} />
            </mesh>
            {[-0.7, -0.22, 0.26, 0.74].map((y) => (
              <mesh key={y} position={[0, y, geometry.moduleDepth / 2 + 0.012]}>
                <boxGeometry args={[geometry.moduleWidth * 0.82, 0.26, 0.024]} />
                <PartMaterial visual={visual} color="#111827" />
              </mesh>
            ))}
            <mesh position={[0, -geometry.moduleHeight / 2 + 0.08, 0]}>
              <boxGeometry args={[geometry.moduleWidth * 0.88, 0.16, geometry.moduleDepth * 0.92]} />
              <PartMaterial visual={visual} detail metalness={0.65} />
            </mesh>
          </group>
        );
      })}
      <TransparentHitbox size={[Math.max(0.48, boundWidth), geometry.moduleHeight + 0.18, Math.max(0.72, geometry.moduleDepth)]} />
    </group>
  );
}

function GpuModel({ visual }: { visual: PartVisual }) {
  const geometry = ASSEMBLY_GEOMETRY.GPU;
  const fanRadius = Math.min(geometry.height * 0.34, geometry.width / 7.5);
  const fanXs = [-geometry.width * 0.29, 0, geometry.width * 0.29];

  if (visual.occluded) {
    return (
      <OccludedEnvelope
        size={[geometry.width, geometry.height, geometry.depth]}
        color={visual.baseColor}
      />
    );
  }

  return (
    <group>
      <mesh>
        <boxGeometry args={[geometry.width, geometry.height, geometry.depth]} />
        <PartMaterial visual={visual} />
      </mesh>
      <mesh position={[0, 0, geometry.depth / 2 + 0.025]}>
        <boxGeometry args={[geometry.width * 0.94, geometry.height * 0.84, 0.05]} />
        <PartMaterial visual={visual} color="#161d25" />
      </mesh>
      {fanXs.map((x) => (
        <group key={x} position={[x, 0, geometry.depth / 2 + 0.07]} rotation={[Math.PI / 2, 0, 0]}>
          <mesh>
            <cylinderGeometry args={[fanRadius, fanRadius, 0.08, 24]} />
            <PartMaterial visual={visual} detail metalness={0.3} />
          </mesh>
          <mesh position={[0, 0.05, 0]}>
            <cylinderGeometry args={[fanRadius * 0.28, fanRadius * 0.28, 0.1, 18]} />
            <PartMaterial visual={visual} color="#111827" />
          </mesh>
          {Array.from({ length: 6 }, (_, index) => (
            <mesh key={index} rotation={[0, index * Math.PI / 3, 0]} position={[0, 0.105, fanRadius * 0.5]}>
              <boxGeometry args={[fanRadius * 0.19, 0.025, fanRadius * 0.72]} />
              <PartMaterial visual={visual} color="#202a35" />
            </mesh>
          ))}
        </group>
      ))}
      <mesh position={[-geometry.width / 2 + 0.05, 0, 0]}>
        <boxGeometry args={[0.1, geometry.height * 1.08, geometry.depth * 1.22]} />
        <PartMaterial visual={visual} color="#9ca3af" metalness={0.72} />
      </mesh>
      <mesh position={[0, -geometry.height / 2 + 0.04, -geometry.depth * 0.18]}>
        <boxGeometry args={[geometry.width * 0.72, 0.08, geometry.depth * 0.42]} />
        <PartMaterial visual={visual} color="#c69a31" metalness={0.48} />
      </mesh>
    </group>
  );
}

function StorageModel({ visual, count }: { visual: PartVisual; count: number }) {
  const geometry = ASSEMBLY_GEOMETRY.STORAGE;
  const totalHeight = geometry.height + geometry.instanceSpacing * Math.max(0, count - 1);
  const centerY = -geometry.instanceSpacing * Math.max(0, count - 1) / 2;
  return (
    <group>
      {Array.from({ length: count }, (_, index) => (
        <group key={index} position={[0, -index * geometry.instanceSpacing, 0]}>
          <mesh>
            <boxGeometry args={[geometry.width, geometry.height, geometry.boardDepth]} />
            <PartMaterial visual={visual} />
          </mesh>
          {[-0.38, 0.02, 0.38].map((x) => (
            <mesh key={x} position={[x, 0, geometry.chipOffsetZ]}>
              <boxGeometry args={[geometry.chipWidth, geometry.chipHeight, geometry.chipDepth]} />
              <PartMaterial visual={visual} color="#101820" />
            </mesh>
          ))}
          <mesh position={[-geometry.width / 2 + 0.12, 0, geometry.chipOffsetZ * 0.7]}>
            <boxGeometry args={[0.21, geometry.height * 0.86, geometry.chipDepth * 0.55]} />
            <PartMaterial visual={visual} color="#d5ac42" metalness={0.55} />
          </mesh>
          <mesh position={[geometry.width / 2 - 0.08, 0, geometry.chipOffsetZ]} rotation={[Math.PI / 2, 0, 0]}>
            <cylinderGeometry args={[0.045, 0.045, 0.035, 14]} />
            <PartMaterial visual={visual} color="#cbd5e1" metalness={0.65} />
          </mesh>
        </group>
      ))}
      <group position={[0, centerY, 0]}>
        <TransparentHitbox size={[geometry.width + 0.16, Math.max(0.48, totalHeight), 0.28]} />
      </group>
    </group>
  );
}

function PsuModel({ visual }: { visual: PartVisual }) {
  const geometry = ASSEMBLY_GEOMETRY.PSU;
  const frontZ = geometry.depth / 2 + 0.018;
  return (
    <group>
      <mesh>
        <boxGeometry args={[geometry.width, geometry.height, geometry.depth]} />
        <PartMaterial visual={visual} />
      </mesh>
      <mesh position={[0, 0, frontZ]}>
        <torusGeometry args={[geometry.height * 0.31, 0.035, 8, 32]} />
        <PartMaterial visual={visual} detail metalness={0.7} />
      </mesh>
      {Array.from({ length: 6 }, (_, index) => (
        <mesh key={index} position={[0, 0, frontZ + 0.012]} rotation={[0, 0, index * Math.PI / 6]}>
          <boxGeometry args={[geometry.height * 0.66, 0.025, 0.025]} />
          <PartMaterial visual={visual} detail metalness={0.7} />
        </mesh>
      ))}
      <mesh position={[0, 0, frontZ + 0.026]} rotation={[Math.PI / 2, 0, 0]}>
        <cylinderGeometry args={[0.13, 0.13, 0.055, 18]} />
        <PartMaterial visual={visual} color="#121820" />
      </mesh>
      {[-0.64, -0.2, 0.24, 0.68].map((x) => (
        <mesh key={x} position={[x, -geometry.height * 0.32, -geometry.depth / 2 - 0.01]}>
          <boxGeometry args={[0.24, 0.17, 0.045]} />
          <PartMaterial visual={visual} color="#111827" />
        </mesh>
      ))}
    </group>
  );
}

function CoolerModel({ visual }: { visual: PartVisual }) {
  const geometry = ASSEMBLY_GEOMETRY.COOLER;
  const [baseWidth, baseHeight, baseDepth] = geometry.baseSize;
  const [towerWidth, towerHeight, towerDepth] = geometry.towerSize;

  if (visual.occluded) {
    return (
      <group>
        <OccludedEnvelope
          size={[baseWidth, baseHeight, baseDepth]}
          position={[0, 0, -1.183]}
          color={visual.detailColor}
        />
        <OccludedEnvelope
          size={[towerWidth, towerHeight, towerDepth]}
          position={[0, 0, geometry.towerOffsetZ]}
          color={visual.baseColor}
        />
      </group>
    );
  }

  return (
    <group>
      <mesh position={[0, 0, -1.183]}>
        <boxGeometry args={[baseWidth, baseHeight, baseDepth]} />
        <PartMaterial visual={visual} detail metalness={0.65} />
      </mesh>
      {[-geometry.supportOffsetX, geometry.supportOffsetX].map((x) => (
        <mesh key={x} position={[x, 0, geometry.supportOffsetZ]}>
          <boxGeometry args={[0.133, geometry.supportHeight, geometry.supportDepth]} />
          <PartMaterial visual={visual} detail metalness={0.62} />
        </mesh>
      ))}
      {Array.from({ length: 8 }, (_, index) => {
        const z = geometry.towerOffsetZ - towerDepth / 2 + 0.08 + index * ((towerDepth - 0.16) / 7);
        return (
          <mesh key={index} position={[0, 0, z]}>
            <boxGeometry args={[towerWidth, towerHeight, 0.055]} />
            <PartMaterial visual={visual} metalness={0.55} roughness={0.36} />
          </mesh>
        );
      })}
      <group position={[0, 0, geometry.towerOffsetZ + towerDepth / 2 + 0.08]} rotation={[Math.PI / 2, 0, 0]}>
        <mesh>
          <cylinderGeometry args={[0.76, 0.76, 0.16, 24]} />
          <PartMaterial visual={visual} color="#27313c" />
        </mesh>
        <mesh position={[0, 0.1, 0]}>
          <cylinderGeometry args={[0.18, 0.18, 0.18, 18]} />
          <PartMaterial visual={visual} detail />
        </mesh>
        {Array.from({ length: 7 }, (_, index) => (
          <mesh key={index} rotation={[0, index * Math.PI / 3.5, 0]} position={[0, 0.115, 0.4]}>
            <boxGeometry args={[0.18, 0.025, 0.68]} />
            <PartMaterial visual={visual} color="#566270" />
          </mesh>
        ))}
      </group>
    </group>
  );
}

function OccludedEnvelope({
  size,
  position = [0, 0, 0],
  color
}: {
  size: AssemblyVector3;
  position?: AssemblyVector3;
  color: string;
}) {
  return (
    <mesh position={[...position]}>
      <boxGeometry args={[...size]} />
      <meshStandardMaterial
        color={color}
        transparent
        opacity={ASSEMBLY_OCCLUDER_OPACITY}
        depthWrite={false}
        roughness={0.58}
        metalness={0.18}
      />
    </mesh>
  );
}

function TransparentHitbox({ size }: { size: AssemblyVector3 }) {
  return (
    <mesh>
      <boxGeometry args={[...size]} />
      <meshBasicMaterial transparent opacity={0} depthWrite={false} colorWrite={false} />
    </mesh>
  );
}

function CategoryFeedback({
  category,
  count,
  visual
}: {
  category: PartCategory;
  count: number;
  visual: PartVisual;
}) {
  const { center, size } = feedbackBounds(category, count);
  const statusColor = visual.status === 'FAIL'
    ? '#ef3f3f'
    : visual.status === 'WARN'
      ? '#f59e0b'
      : null;
  const showLabel = visual.selected || visual.status !== 'PASS';
  const labelPosition: AssemblyVector3 = [
    center[0],
    center[1] + size[1] / 2 + 0.24,
    center[2] + size[2] / 2
  ];

  return (
    <group>
      {statusColor ? (
        <OutlineBox center={center} size={size} color={statusColor} scale={1.025} />
      ) : null}
      {visual.selected ? (
        <OutlineBox
          center={center}
          size={size}
          color={ASSEMBLY_SELECTION_OUTLINE_COLOR}
          scale={1.065}
        />
      ) : null}
      {showLabel ? (
        <Html position={labelPosition} center distanceFactor={9} style={{ pointerEvents: 'none' }}>
          <span
            role="status"
            aria-label={`${CATEGORY_LABEL[category]} ${visual.selected ? '선택됨' : ''} ${STATUS_LABEL[visual.status]}`.trim()}
            className={`whitespace-nowrap rounded-md border bg-white/95 px-2 py-1 text-[10px] font-black shadow-sm ${
              visual.status === 'FAIL'
                ? 'border-red-300 text-red-700'
                : visual.status === 'WARN'
                  ? 'border-amber-300 text-amber-800'
                  : 'border-blue-300 text-blue-700'
            }`}
          >
            {CATEGORY_LABEL[category]} · {visual.selected ? '선택됨 · ' : ''}{STATUS_LABEL[visual.status]}
          </span>
        </Html>
      ) : null}
    </group>
  );
}

function OutlineBox({
  center,
  size,
  color,
  scale
}: {
  center: AssemblyVector3;
  size: AssemblyVector3;
  color: string;
  scale: number;
}) {
  return (
    <mesh position={[...center]} scale={scale} renderOrder={20}>
      <boxGeometry args={[...size]} />
      <meshBasicMaterial transparent opacity={0} depthWrite={false} colorWrite={false} />
      <Edges color={color} threshold={10} />
    </mesh>
  );
}

function feedbackBounds(category: PartCategory, count: number): { center: AssemblyVector3; size: AssemblyVector3 } {
  switch (category) {
    case 'CASE': {
      const geometry = ASSEMBLY_GEOMETRY.CASE;
      return { center: [0, 0, 0], size: [geometry.width, geometry.height, geometry.depth] };
    }
    case 'MOTHERBOARD': {
      const geometry = ASSEMBLY_GEOMETRY.MOTHERBOARD;
      return { center: [0, 0, 0], size: [geometry.width, geometry.height, geometry.renderDepth + 0.16] };
    }
    case 'CPU': {
      const geometry = ASSEMBLY_GEOMETRY.CPU;
      return { center: [0, 0, 0], size: [geometry.bodyWidth, geometry.bodyHeight, geometry.bodyDepth + 0.04] };
    }
    case 'RAM': {
      const geometry = ASSEMBLY_GEOMETRY.RAM;
      return {
        center: [0, 0, 0],
        size: [
          geometry.moduleWidth + geometry.moduleSpacing * Math.max(0, count - 1),
          geometry.moduleHeight,
          geometry.moduleDepth
        ]
      };
    }
    case 'GPU': {
      const geometry = ASSEMBLY_GEOMETRY.GPU;
      return { center: [0, 0, 0], size: [geometry.width, geometry.height, geometry.depth] };
    }
    case 'STORAGE': {
      const geometry = ASSEMBLY_GEOMETRY.STORAGE;
      return {
        center: [0, -geometry.instanceSpacing * Math.max(0, count - 1) / 2, 0],
        size: [
          geometry.width,
          geometry.height + geometry.instanceSpacing * Math.max(0, count - 1),
          Math.max(geometry.boardDepth + geometry.chipDepth * 2, 0.12)
        ]
      };
    }
    case 'PSU': {
      const geometry = ASSEMBLY_GEOMETRY.PSU;
      return { center: [0, 0, 0], size: [geometry.width, geometry.height, geometry.depth] };
    }
    case 'COOLER': {
      const geometry = ASSEMBLY_GEOMETRY.COOLER;
      return { center: [0, 0, 0], size: [geometry.width, geometry.height, geometry.depth] };
    }
  }
}
