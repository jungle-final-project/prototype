import { API_BASE_URL } from '../../lib/api';

const ALLOWED_PROFILE_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const PROFILE_IMAGE_MAX_BYTES = 1024 * 1024;

export const PROFILE_IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp';
export const PROFILE_IMAGE_HELP_TEXT = 'JPG, PNG, WebP 중 최대 1MB';

export function resolveProfileImageSrc(value?: string | null) {
  const src = value?.trim();
  if (!src) return '';
  if (src.startsWith('/api/')) {
    return `${API_BASE_URL.replace(/\/$/, '')}${src}`;
  }
  return src;
}

export function profileImagePayloadValue(value: string) {
  const src = value.trim();
  return src.startsWith('data:image/') ? undefined : src;
}

export function validateProfileImageFile(file: File) {
  if (!ALLOWED_PROFILE_IMAGE_TYPES.has(file.type)) {
    throw new Error('JPG, PNG, WebP 이미지만 사용할 수 있습니다.');
  }
  if (file.size > PROFILE_IMAGE_MAX_BYTES) {
    throw new Error('프로필 이미지는 1MB 이하만 사용할 수 있습니다.');
  }
}
