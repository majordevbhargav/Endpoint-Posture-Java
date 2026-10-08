import { describe, it, expect } from "vitest";
import { isTokenValid } from "./session";

describe("session isTokenValid", () => {
  function makeJwt(payload: object): string {
    const header = btoa(JSON.stringify({ alg: "HS256", typ: "JWT" }));
    const body = btoa(JSON.stringify(payload));
    return `${header}.${body}.mock-signature`;
  }

  it("returns false for null or empty tokens", () => {
    expect(isTokenValid(null)).toBe(false);
    expect(isTokenValid("")).toBe(false);
  });

  it("returns false for malformed tokens", () => {
    expect(isTokenValid("invalid.token")).toBe(false);
    expect(isTokenValid("completely-invalid")).toBe(false);
  });

  it("returns true for unexpired tokens", () => {
    const futureExp = Math.floor(Date.now() / 1000) + 3600; // 1 hour from now
    const token = makeJwt({ sub: "admin", role: "ADMIN", exp: futureExp });
    expect(isTokenValid(token)).toBe(true);
  });

  it("returns false for expired tokens", () => {
    const pastExp = Math.floor(Date.now() / 1000) - 60; // 1 minute ago
    const token = makeJwt({ sub: "admin", role: "ADMIN", exp: pastExp });
    expect(isTokenValid(token)).toBe(false);
  });

  it("returns true when exp is omitted", () => {
    const token = makeJwt({ sub: "service-account" });
    expect(isTokenValid(token)).toBe(true);
  });
});
