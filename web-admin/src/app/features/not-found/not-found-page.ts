import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Catch-all for unknown routes. */
@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  template: `
    <div class="mx-auto mt-16 max-w-md rounded-lg border border-slate-200 bg-white p-6 text-center">
      <h1 class="text-xl font-semibold">Page not found</h1>
      <p class="mt-2 text-sm text-slate-600">The page you requested does not exist.</p>
      <a routerLink="/" class="mt-4 inline-block rounded bg-slate-900 px-4 py-2 text-sm text-white">
        Go to dashboard
      </a>
    </div>
  `,
})
export class NotFoundPage {}
