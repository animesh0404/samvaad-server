/**
 * Thin hand-mirrored TypeScript interfaces for the Samvaad server DTOs.
 * Field names and shapes must match the backend contracts; the server
 * remains authoritative and these mirrors carry no behavior.
 */

export type UserRole = 'ADMIN' | 'USER';

export type ClientPlatform = 'DESKTOP' | 'ANDROID' | 'IOS' | 'WEB' | 'TUI';

export interface UserDto {
  userId: string;
  username: string;
  email: string | null;
  role: UserRole;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  /** Optional. The backend creates users without an admin-settable role. */
  email?: string | null;
}

export interface LoginRequest {
  identifier: string;
  password: string;
  /** Admin UI always logs in as WEB and never sends installationId (ADR 0010). */
  clientPlatform: ClientPlatform;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  sessionId: string;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface UserProfileDto {
  userId: string;
  displayName: string | null;
  bio: string | null;
  avatarUrl: string | null;
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  statusMessage: string | null;
}
