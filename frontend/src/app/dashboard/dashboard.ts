import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin } from 'rxjs';
import { Api, Dashboard, Reservation, SpaceStatus, Vehicle, dollars, errorMessage } from '../api';

const REFRESH_MS = 15_000;

@Component({
  selector: 'app-dashboard',
  imports: [FormsModule],
  templateUrl: './dashboard.html',
})
export class DashboardPage {
  private readonly api = inject(Api);

  protected readonly balanceCents = signal<number | null>(null);
  protected readonly vehicles = signal<Vehicle[]>([]);
  protected readonly board = signal<Dashboard | null>(null);
  protected readonly reservations = signal<Reservation[]>([]);
  protected readonly selectedVehicleId = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly message = signal<{ kind: 'success' | 'error'; text: string } | null>(null);

  protected depositAmount: number | null = null;
  protected newPlate = '';

  /**
   * One key per intended deposit: kept when a deposit fails (network error, 503) so clicking again retries the
   * same deposit and the server applies it at most once; replaced after a success or when the amount changes.
   */
  private pendingDeposit: { amount: number; key: string } | null = null;

  protected readonly availableCount = computed(() => this.board()?.spaces.filter((s) => !s.reserved).length ?? 0);
  protected readonly dollars = dollars;

  constructor() {
    this.refreshAll();
    const timer = setInterval(() => this.refreshSpaces(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  refreshAll(): void {
    forkJoin({
      wallet: this.api.wallet(),
      vehicles: this.api.vehicles(),
      board: this.api.spaces(),
      reservations: this.api.myReservations(),
    }).subscribe({
      next: ({ wallet, vehicles, board, reservations }) => {
        this.balanceCents.set(wallet.balanceCents);
        this.setVehicles(vehicles);
        this.board.set(board);
        this.reservations.set(reservations);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  refreshSpaces(): void {
    this.api.spaces().subscribe({ next: (board) => this.board.set(board), error: () => {} });
  }

  deposit(): void {
    const amount = Number(this.depositAmount);
    if (!amount || amount <= 0) {
      this.fail('Enter an amount greater than 0.');
      return;
    }
    if (this.pendingDeposit?.amount !== amount) {
      this.pendingDeposit = { amount, key: crypto.randomUUID() };
    }
    this.run(this.api.deposit(amount, this.pendingDeposit.key), (wallet) => {
      this.pendingDeposit = null;
      this.balanceCents.set(wallet.balanceCents);
      this.depositAmount = null;
      this.ok(`Deposited ${dollars(Math.round(amount * 100))}.`);
    });
  }

  addVehicle(): void {
    if (!this.newPlate.trim()) {
      this.fail('Enter a license plate.');
      return;
    }
    this.run(this.api.addVehicle(this.newPlate), (vehicle) => {
      this.newPlate = '';
      this.setVehicles([...this.vehicles(), vehicle]);
      this.selectedVehicleId.set(vehicle.id);
      this.ok(`Vehicle ${vehicle.plate} registered.`);
    });
  }

  reserve(space: SpaceStatus): void {
    const board = this.board();
    const vehicle = this.vehicles().find((v) => v.id === this.selectedVehicleId());
    if (!board || space.reserved || this.busy()) {
      return;
    }
    if (!board.open) {
      this.fail(`Reservations for ${board.date} open at ${board.opensAt} (${board.timezone}).`);
      return;
    }
    if (!vehicle) {
      this.fail('Register a vehicle first.');
      return;
    }
    if (!confirm(`Reserve space ${space.id} on ${board.date} for ${vehicle.plate}? $10.00 will be deducted.`)) {
      return;
    }
    this.run(this.api.reserve(space.id, vehicle.id), (r) => {
      this.ok(`Space ${r.spaceId} reserved for ${r.plate} on ${r.date}. ${dollars(r.priceCents)} deducted.`);
      this.refreshAll();
    }, () => this.refreshAll());
  }

  private setVehicles(vehicles: Vehicle[]): void {
    this.vehicles.set(vehicles);
    if (this.selectedVehicleId() === null && vehicles.length > 0) {
      this.selectedVehicleId.set(vehicles[0].id);
    }
  }

  private run<T>(call: Observable<T>, onSuccess: (value: T) => void, onError?: () => void): void {
    this.busy.set(true);
    this.message.set(null);
    call.subscribe({
      next: (value) => {
        this.busy.set(false);
        onSuccess(value);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.fail(err);
        onError?.();
      },
    });
  }

  private ok(text: string): void {
    this.message.set({ kind: 'success', text });
  }

  private fail(err: unknown): void {
    this.message.set({ kind: 'error', text: typeof err === 'string' ? err : errorMessage(err) });
  }
}
