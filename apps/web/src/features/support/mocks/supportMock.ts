import type { TicketRow } from '../types';

export const tickets: TicketRow[] = [
  { id: '00000000-0000-4000-8000-000000006001', user: 'user@example.com', symptom: '게임 중 프레임 급락', status: 'OPEN', cause: 'GPU 온도 과열 가능성', confidence: 'MEDIUM' },
  { id: '00000000-0000-4000-8000-000000006002', user: 'dev@example.com', symptom: 'IDE 실행 시 메모리 부족', status: 'IN_PROGRESS', cause: 'RAM 사용률 92% 반복', confidence: 'HIGH' }
];
