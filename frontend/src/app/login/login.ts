import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, errorMessage } from '../api';
import { Auth } from '../auth';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
})
export class LoginPage {
  private readonly api = inject(Api);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected username = '';
  protected password = '';

  constructor() {
    if (this.auth.loggedIn()) {
      void this.router.navigate(['/']);
    }
  }

  toggleMode(): void {
    this.mode.set(this.mode() === 'login' ? 'register' : 'login');
    this.error.set(null);
  }

  submit(): void {
    if (!this.username.trim() || !this.password) {
      this.error.set('Please enter a username and password.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const call = this.mode() === 'login'
      ? this.api.login(this.username, this.password)
      : this.api.register(this.username, this.password);
    call.subscribe({
      next: (response) => {
        this.password = '';
        this.auth.setSession(response);
        void this.router.navigate(['/']);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
