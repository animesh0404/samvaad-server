import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateUserRequest,
  EmailUpdatePayload,
  PasswordUpdatePayload,
  UserDto,
  UserProfileDto,
  UserProfileUpdatePayload,
} from './models';

/** Typed client for the existing ADMIN user/provisioning endpoints. */
@Injectable({ providedIn: 'root' })
export class UsersApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  listUsers(): Observable<UserDto[]> {
    return this.http.get<UserDto[]>(`${this.base}/api/users`);
  }

  createUser(body: CreateUserRequest): Observable<UserDto> {
    return this.http.post<UserDto>(`${this.base}/api/users`, body);
  }

  getUser(userId: string): Observable<UserDto> {
    return this.http.get<UserDto>(`${this.base}/api/users/${userId}`);
  }

  deleteUser(userId: string): Observable<string> {
    return this.http.delete(`${this.base}/api/users/${userId}`, { responseType: 'text' });
  }

  getUserProfile(userId: string): Observable<UserProfileDto> {
    return this.http.get<UserProfileDto>(`${this.base}/api/users/${userId}/profile`);
  }

  updateProfile(userId: string, body: UserProfileUpdatePayload): Observable<UserProfileDto> {
    return this.http.patch<UserProfileDto>(`${this.base}/api/users/${userId}/profile`, body);
  }

  changeEmail(userId: string, body: EmailUpdatePayload): Observable<UserDto> {
    return this.http.patch<UserDto>(`${this.base}/api/users/${userId}/email`, body);
  }

  changePassword(userId: string, body: PasswordUpdatePayload): Observable<UserDto> {
    return this.http.patch<UserDto>(`${this.base}/api/users/${userId}/password`, body);
  }
}
