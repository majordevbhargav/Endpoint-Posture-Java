// Lightweight helper alongside lib/api.ts's token storage.
// Keeps the logged-in user's identity (from the real /auth/login response)
// so the topbar can show a real name/role instead of a placeholder.

export interface CurrentUser {
  username: string;
  role: string;
}

const USER_KEY = "vece_user";

export function setCurrentUser(user: CurrentUser) {
  if (typeof window === "undefined") return;
  sessionStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function getCurrentUser(): CurrentUser | null {
  if (typeof window === "undefined") return null;
  const raw = sessionStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as CurrentUser;
  } catch {
    return null;
  }
}

export function clearCurrentUser() {
  if (typeof window === "undefined") return;
  sessionStorage.removeItem(USER_KEY);
}

export function initials(name: string): string {
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}