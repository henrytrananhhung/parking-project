import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthResponse } from './api';

const TOKEN_KEY = 'parking.token';
const USER_KEY = 'parking.username';

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // Storage unavailable (private mode): the session simply lasts until reload.
  }
}

/** Stateless JWT session kept in the browser. */
@Injectable({ providedIn: 'root' })
export class Auth {
  readonly token = signal<string | null>(read(TOKEN_KEY));
  readonly username = signal<string | null>(read(USER_KEY));
  readonly loggedIn = computed(() => !!this.token());

  setSession(response: AuthResponse): void {
    this.token.set(response.token);
    this.username.set(response.username);
    write(TOKEN_KEY, response.token);
    write(USER_KEY, response.username);
  }

  logout(): void {
    this.token.set(null);
    this.username.set(null);
    write(TOKEN_KEY, null);
    write(USER_KEY, null);
  }
}

/** Adds the bearer token to API calls; an expired/invalid token (401) ends the session. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const router = inject(Router);
  const token = auth.token();
  const isApi = req.url.startsWith('/api/');
  const request = token && isApi ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(request).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && isApi && !req.url.startsWith('/api/auth/')) {
        auth.logout();
        void router.navigate(['/login']);
      }
      return throwError(() => err);
    }),
  );
};

export const authGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  return auth.loggedIn() ? true : inject(Router).createUrlTree(['/login']);
};
