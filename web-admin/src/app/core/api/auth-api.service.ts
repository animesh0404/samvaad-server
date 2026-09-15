import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { LoginRequest, LoginResponse } from './models';

/**
 * Raw authentication API. Uses HttpBackend directly so login/refresh/logout
 * never pass through the auth interceptor (avoids cycles and prevents
 * attaching stale tokens to credential calls).
 */
@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly base = environment.apiBaseUrl;

  login(body: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/api/auth/login`, body);
  }

  refresh(refreshToken: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/api/auth/refresh`, { refreshToken });
  }

  logout(accessToken: string): Observable<string> {
    return this.http.post(`${this.base}/api/auth/logout`, null, {
      headers: { Authorization: `Bearer ${accessToken}` },
      responseType: 'text',
    });
  }
}
