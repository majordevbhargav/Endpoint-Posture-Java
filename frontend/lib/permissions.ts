import { getCurrentUser } from "@/lib/auth";

export type Role = "ADMIN" | "OPERATOR" | "ANALYST" | "VIEWER";
export type Action =
  | "enqueue"
  | "sharePosture"
  | "restrict"
  | "editPolicy"
  | "manageUsers"
  | "uploadWarranty";

// UI convenience only. The backend @PreAuthorize rules are the real control.
const ALLOWED: Record<Action, Role[]> = {
  enqueue: ["ADMIN", "OPERATOR", "ANALYST"],
  sharePosture: ["ADMIN", "OPERATOR", "ANALYST"],
  restrict: ["ADMIN", "OPERATOR"],
  editPolicy: ["ADMIN"],
  manageUsers: ["ADMIN"],
  uploadWarranty: ["ADMIN"],
};

export function can(action: Action): boolean {
  const role = getCurrentUser()?.role?.toUpperCase() as Role | undefined;
  return !!role && ALLOWED[action].includes(role);
}

export const DENIED_HINT = "Your role does not allow this action";