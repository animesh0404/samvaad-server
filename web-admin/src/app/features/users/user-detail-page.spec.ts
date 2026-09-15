import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { provideSpecRouter } from '../../testing/spec-router';
import { describe, expect, it } from 'vitest';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { UserDto } from '../../core/api/models';
import { UserDetailPage } from './user-detail-page';

const USER: UserDto = { userId: 'u-7', username: 'dave', email: 'd@x.com', role: 'USER' };

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideSpecRouter(),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: new Map([['id', 'u-7']]) } },
      },
    ],
  });
  const backend = TestBed.inject(HttpTestingController);
  const fixture: ComponentFixture<UserDetailPage> = TestBed.createComponent(UserDetailPage);
  return { fixture, page: fixture.componentInstance, backend };
}

describe('UserDetailPage', () => {
  it('shows account fields and the profile when present', () => {
    const { fixture, backend } = setup();
    backend.expectOne('/api/users/u-7').flush(USER);
    backend.expectOne('/api/users/u-7/profile').flush({
      userId: 'u-7',
      displayName: 'Dave',
      bio: 'hello',
      avatarUrl: null,
      firstName: null,
      middleName: null,
      lastName: null,
      statusMessage: null,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('dave');
    expect(fixture.nativeElement.textContent).toContain('Dave');
    backend.verify();
  });

  it('tolerates a missing profile record', () => {
    const { fixture, backend } = setup();
    backend.expectOne('/api/users/u-7').flush(USER);
    backend
      .expectOne('/api/users/u-7/profile')
      .flush({ message: 'nope' }, { status: 404, statusText: 'N' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No profile record');
    backend.verify();
  });

  it('reports unknown users', () => {
    const { fixture, backend } = setup();
    backend
      .expectOne('/api/users/u-7')
      .flush({ message: 'User not found' }, { status: 404, statusText: 'N' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
      'User not found',
    );
    backend.verify();
  });

  it('deletes through the confirm flow', () => {
    const { fixture, page, backend } = setup();
    backend.expectOne('/api/users/u-7').flush(USER);
    backend
      .expectOne('/api/users/u-7/profile')
      .flush({ message: 'nope' }, { status: 404, statusText: 'N' });
    fixture.detectChanges();
    page['askDelete']();
    fixture.detectChanges();
    page['confirmDelete']();
    backend.expectOne('/api/users/u-7').flush('');
    backend.verify();
  });
});
