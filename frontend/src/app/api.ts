import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AuthResponse {
  token: string;
  username: string;
}

export interface Wallet {
  balanceCents: number;
}

export interface Vehicle {
  id: number;
  plate: string;
}

export interface SpaceStatus {
  id: number;
  reserved: boolean;
  plateSuffix: string | null;
  mine: boolean;
}

export interface Dashboard {
  date: string;
  open: boolean;
  opensAt: string;
  timezone: string;
  spaces: SpaceStatus[];
}

export interface Reservation {
  id: number;
  spaceId: number;
  date: string;
  plate: string;
  priceCents: number;
}

/** Thin client for the real Spring Boot API (same origin in production, proxied by ng serve locally). */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  register(username: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/auth/register', { username, password });
  }

  login(username: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/auth/login', { username, password });
  }

  wallet(): Observable<Wallet> {
    return this.http.get<Wallet>('/api/wallet');
  }

  /** Reuse the same idempotencyKey when retrying the same deposit: the server applies it once. */
  deposit(amount: number, idempotencyKey: string): Observable<Wallet> {
    return this.http.post<Wallet>('/api/wallet/deposits', { amount }, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  vehicles(): Observable<Vehicle[]> {
    return this.http.get<Vehicle[]>('/api/vehicles');
  }

  addVehicle(plate: string): Observable<Vehicle> {
    return this.http.post<Vehicle>('/api/vehicles', { plate });
  }

  spaces(): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/spaces');
  }

  reserve(spaceId: number, vehicleId: number): Observable<Reservation> {
    return this.http.post<Reservation>('/api/reservations', { spaceId, vehicleId });
  }

  myReservations(): Observable<Reservation[]> {
    return this.http.get<Reservation[]>('/api/reservations/me');
  }
}

/** Human-readable message from the backend's ProblemDetail ("detail"), with safe fallbacks. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'Cannot reach the server. Please try again.';
    }
    const detail = err.error?.detail;
    return typeof detail === 'string' && detail ? detail : `Request failed (${err.status}).`;
  }
  return 'Unexpected error.';
}

export function dollars(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}
