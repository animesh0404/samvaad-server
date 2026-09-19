import { Component } from '@angular/core';
import { provideRouter } from '@angular/router';

@Component({ selector: 'app-spec-blank', template: '' })
export class SpecBlankComponent {}

/**
 * Test router that accepts any navigation target. Component and service
 * specs exercise router.navigate() without caring about real route config;
 * guard specs cover the route-protection decisions.
 */
export function provideSpecRouter() {
  return provideRouter([{ path: '**', component: SpecBlankComponent }]);
}
