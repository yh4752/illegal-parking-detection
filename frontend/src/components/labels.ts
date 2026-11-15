import type { IncidentState } from '../api/types';
export const states: Record<IncidentState, string> = {
  OBSERVING: '차량 관측 중',
  STOP_CONFIRMED: '정차 확인',
  GUIDING: '이동 안내 중',
  GUIDED: '이동 안내 완료',
  LONG_UNRESOLVED: '장기 미이동',
  HELD: '판단 보류',
  CLOSED_EXIT: '차량 이동 · 종료',
  CLOSED_MANUAL: '관리자 종료',
};
export const camera: Record<string, string> = {
  OK: '정상',
  SUSPECT: '관측 불확실',
  BLOCKED: '시야 가림',
  NO_FRAME: '영상 수신 없음',
  RECONFIGURE: '설정 필요',
};
export const bodies: Record<string, string> = {
  CAR: '승용차',
  TRUCK: '화물차',
  BUS: '버스',
  UNKNOWN: '미분류',
};
export const uses: Record<string, string> = {
  UNKNOWN: '용도 미확인',
  TAXI: '택시',
  DELIVERY: '택배',
  SCHOOL: '학원·통학',
  OTHER: '기타',
};
export function date(value: string | null | undefined) {
  if (!value) return '확인 안 됨';
  const d = new Date(value);
  return Number.isNaN(d.getTime())
    ? '확인 안 됨'
    : new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(d);
}
export function duration(ms: number | null | undefined) {
  if (ms == null) return '확인 안 됨';
  const n = Math.floor(ms / 1000);
  return `${Math.floor(n / 60)}분 ${String(n % 60).padStart(2, '0')}초`;
}
export function stateTone(value: string): 'neutral' | 'good' | 'warn' | 'danger' {
  return value.startsWith('CLOSED')
    ? 'neutral'
    : value === 'HELD'
      ? 'warn'
      : value === 'LONG_UNRESOLVED'
        ? 'danger'
        : 'good';
}
export const demoZone = import.meta.env.VITE_DEMO_ZONE_ID || 'zone-01';

export const reasons: Record<string, string> = {
  FRAME_STALE: '오래된 영상',
  FRAME_GAP: '영상 끊김',
  INVALID_FRAME: '영상 이상',
  OBSERVATION_MISSING: '관측 누락',
  OCCLUSION_SUSPECT: '가림 의심',
  IMAGE_DARK: '영상이 어두움',
  IMAGE_BRIGHT: '영상이 너무 밝음',
  IMAGE_BLUR: '영상 흐림',
  BACKGROUND_UNRELIABLE: '배경 판별 불확실',
  CAMERA_SHIFT: '카메라 위치 변화',
  CALIBRATION_REQUIRED: '구역 보정 필요',
  IDENTITY_UNCERTAIN: '차량 식별 불확실',
  MULTIPLE_TARGETS: '여러 차량 관측',
  ROI_BOUNDARY: '구역 경계에 위치',
  VEHICLE_MOVING: '차량 이동 중',
  OUTPUT_FAULT: '안내 장치 이상',
  CLOCK_UNTRUSTED: '장치 시각 신뢰 불가',
};
