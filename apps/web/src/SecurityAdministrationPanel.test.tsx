import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import type { MvpGateway, SecuritySnapshot } from "./api";
import { SecurityAdministrationPanel } from "./SecurityAdministrationPanel";

const actor = "22222222-2222-7222-8222-222222222222";
const snapshot: SecuritySnapshot = {
  users: [{ userId: actor, displayName: "管理者", issuer: "https://idp.example", subject: "admin", userStatus: "active", tenantId: "tenant-1", roleKey: "group_admin", membershipStatus: "active", validFrom: "2026-01-01T00:00:00Z", validUntil: null, fieldGroupIds: [], authorizationVersion: 3 }],
  roles: [{ roleKey: "worker", roleLabel: "作業者", capabilities: ["journal:write"] }], breakGlassGrants: [], privacyRequests: [],
  changeRequests: [{ requestId: "request-1", changeType: "user_revoke", targetUserId: "33333333-3333-7333-8333-333333333333", requestedBy: actor, requestedAt: "2026-08-15T00:00:00Z", requestExpiresAt: "2026-08-16T00:00:00Z", reason: "退職に伴う利用停止申請", ticketRef: "SEC-1", beforeState: { roleKey: "worker" }, proposedState: {}, status: "pending" }],
};

test("shows before/after audit and prevents the requester from self-approving", async () => {
  const api = {
    getSecurityAdministration: vi.fn(async () => snapshot), getPesticideMasterReviews: vi.fn(async () => ({ reviews: [] })),
    requestSecurityChange: vi.fn(), decideSecurityChange: vi.fn(), createPrivacyRequest: vi.fn(), transitionPrivacyRequest: vi.fn(), requestPesticideMasterReview: vi.fn(), decidePesticideMasterReview: vi.fn(),
  } as unknown as MvpGateway;
  render(<SecurityAdministrationPanel api={api} contextId="context-1" csrfToken="csrf-1" actorUserId={actor} capabilities={["security:manage"]} online setNotice={vi.fn()}/>);
  expect(await screen.findByText("退職に伴う利用停止申請")).toBeInTheDocument();
  await userEvent.click(screen.getByText("変更前後を比較"));
  expect(screen.getByText(/roleKey/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "別管理者として承認" })).toBeDisabled();
  expect(screen.getByText("申請者本人は承認できません。")).toBeInTheDocument();
});

test("submits tenant role, field-group scope and expiry as a pending change", async () => {
  const requestSecurityChange = vi.fn(async () => ({ requestId: "request-2", status: "pending" }));
  const api = { getSecurityAdministration: vi.fn(async () => ({ ...snapshot, changeRequests: [] })), getPesticideMasterReviews: vi.fn(async () => ({ reviews: [] })), requestSecurityChange } as unknown as MvpGateway;
  render(<SecurityAdministrationPanel api={api} contextId="context-1" csrfToken="csrf-1" actorUserId={actor} capabilities={["security:manage"]} online setNotice={vi.fn()}/>);
  await screen.findByText("現在の利用者"); const user = userEvent.setup();
  await user.selectOptions(screen.getByLabelText("操作"), "user_change");
  await user.type(screen.getByLabelText("利用者ID"), "33333333-3333-7333-8333-333333333333");
  await user.type(screen.getByLabelText("field-group scope"), "44444444-4444-7444-8444-444444444444");
  await user.type(screen.getAllByLabelText("ticket")[0], "SEC-2");
  await user.type(screen.getByLabelText("申請理由"), "担当圃場と利用期限を変更します");
  await user.click(screen.getByRole("button", { name: "二人承認へ申請" }));
  await waitFor(() => expect(requestSecurityChange).toHaveBeenCalledWith("context-1", "csrf-1", expect.objectContaining({ changeType: "user_change", proposedState: expect.objectContaining({ fieldGroupIds: ["44444444-4444-7444-8444-444444444444"] }) })));
});
