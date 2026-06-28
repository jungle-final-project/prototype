import type { BuildSummary } from '../types';

export const categories = ['AI 추천', '셀프 견적', 'CPU', '메인보드', 'RAM', 'GPU', 'SSD', '파워', '케이스', '목표가 알림', 'PC Agent', 'AS 접수'];

export const builds: BuildSummary[] = [
  { id: '00000000-0000-4000-8000-000000002001', name: 'QHD 게임 균형형', price: 1980000, confidence: 'MEDIUM', warning: 'PSU 여유율 확인 필요', useCase: 'QHD gaming' },
  { id: '00000000-0000-4000-8000-000000002002', name: '개발 + 게임 혼합형', price: 2120000, confidence: 'HIGH', warning: 'RAM 32GB 권장', useCase: 'dev + gaming' },
  { id: '00000000-0000-4000-8000-000000002003', name: 'AI 실습 입문형', price: 1620000, confidence: 'MEDIUM', warning: 'VRAM 한계 가능성', useCase: 'AI practice' }
];
