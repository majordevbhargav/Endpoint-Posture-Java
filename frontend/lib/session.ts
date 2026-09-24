// Returns true unless the JWT is clearly expired/malformed.
export function isTokenValid(token: string | null): boolean {
  if (!token) return false;
  try {
    const part = token.split(".")[1];
    const json = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
    const payload = JSON.parse(json);
    return !payload.exp || payload.exp * 1000 > Date.now();
  } catch {
    return false;
  }
}
