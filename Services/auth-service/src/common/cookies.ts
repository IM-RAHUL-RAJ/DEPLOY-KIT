import { CookieOptions, Response } from 'express';

export interface RefreshCookieConfig {
  name: string;
  secure: boolean;
  sameSite: 'lax' | 'strict' | 'none';
}

export function refreshCookieOptions(
  config: RefreshCookieConfig,
  maxAgeSeconds: number,
): CookieOptions {
  return {
    httpOnly: true,
    secure: config.secure,
    sameSite: config.sameSite,
    path: '/',
    maxAge: maxAgeSeconds * 1000,
  };
}

export function setRefreshCookie(
  res: Response,
  token: string,
  config: RefreshCookieConfig,
  maxAgeSeconds: number,
): void {
  clearRefreshCookie(res, config);
  res.cookie(config.name, token, refreshCookieOptions(config, maxAgeSeconds));
}

export function clearRefreshCookie(res: Response, config: RefreshCookieConfig): void {
  res.clearCookie(config.name, {
    httpOnly: true,
    secure: config.secure,
    sameSite: config.sameSite,
    path: '/',
  });
}