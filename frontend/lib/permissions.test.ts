import { describe, it, expect, vi, beforeEach } from "vitest";

// Mock the auth module so can() doesn't need sessionStorage
vi.mock("./auth", () => ({
  getCurrentUser: vi.fn(),
}));

import { getCurrentUser } from "./auth";
import { can } from "./permissions";

const mockGetCurrentUser = getCurrentUser as ReturnType<typeof vi.fn>;

describe("permissions can()", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns false when no user is logged in", () => {
    mockGetCurrentUser.mockReturnValue(null);
    expect(can("enqueue")).toBe(false);
    expect(can("restrict")).toBe(false);
    expect(can("editPolicy")).toBe(false);
  });

  it("ADMIN can do everything", () => {
    mockGetCurrentUser.mockReturnValue({ username: "admin", role: "ADMIN" });
    expect(can("enqueue")).toBe(true);
    expect(can("sharePosture")).toBe(true);
    expect(can("restrict")).toBe(true);
    expect(can("editPolicy")).toBe(true);
    expect(can("manageUsers")).toBe(true);
    expect(can("uploadWarranty")).toBe(true);
  });

  it("OPERATOR can enqueue, sharePosture, restrict but not editPolicy or manageUsers", () => {
    mockGetCurrentUser.mockReturnValue({ username: "ops", role: "OPERATOR" });
    expect(can("enqueue")).toBe(true);
    expect(can("sharePosture")).toBe(true);
    expect(can("restrict")).toBe(true);
    expect(can("editPolicy")).toBe(false);
    expect(can("manageUsers")).toBe(false);
    expect(can("uploadWarranty")).toBe(false);
  });

  it("ANALYST can enqueue and sharePosture but not restrict", () => {
    mockGetCurrentUser.mockReturnValue({ username: "analyst", role: "ANALYST" });
    expect(can("enqueue")).toBe(true);
    expect(can("sharePosture")).toBe(true);
    expect(can("restrict")).toBe(false);
    expect(can("editPolicy")).toBe(false);
  });

  it("VIEWER cannot do any write actions", () => {
    mockGetCurrentUser.mockReturnValue({ username: "viewer", role: "VIEWER" });
    expect(can("enqueue")).toBe(false);
    expect(can("sharePosture")).toBe(false);
    expect(can("restrict")).toBe(false);
    expect(can("editPolicy")).toBe(false);
    expect(can("manageUsers")).toBe(false);
    expect(can("uploadWarranty")).toBe(false);
  });

  it("role comparison is case-insensitive", () => {
    mockGetCurrentUser.mockReturnValue({ username: "user", role: "admin" });
    expect(can("editPolicy")).toBe(true);
  });
});
