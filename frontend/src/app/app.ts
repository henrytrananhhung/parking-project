import { Component, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { Auth } from './auth';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {
  protected readonly auth = inject(Auth);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
