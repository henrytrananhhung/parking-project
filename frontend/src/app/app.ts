import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterOutlet } from '@angular/router';

interface Health {
  status: string;
  components?: Record<string, { status: string }>;
}

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly http = inject(HttpClient);

  protected readonly appStatus = signal('checking…');
  protected readonly dbStatus = signal('checking…');

  constructor() {
    this.http.get<Health>('/actuator/health').subscribe({
      next: (h) => {
        this.appStatus.set(h.status);
        this.dbStatus.set(h.components?.['db']?.status ?? 'unknown');
      },
      error: () => {
        this.appStatus.set('unreachable');
        this.dbStatus.set('unknown');
      },
    });
  }
}
