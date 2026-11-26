import { Routes } from '@angular/router';
import { authGuard } from './auth';
import { DashboardPage } from './dashboard/dashboard';
import { LoginPage } from './login/login';

export const routes: Routes = [
  { path: 'login', component: LoginPage },
  { path: '', component: DashboardPage, canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];
