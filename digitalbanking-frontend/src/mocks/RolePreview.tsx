import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Input, Select, Spin, Steps } from "antd";
import type { components } from "../shared/api/generated/v2/openapi";
import { Brand, Heading, Panel } from "../components/shared";
import { createApiClient, type ApiClient } from "../shared/api/client";
import { approvalApi } from "../features/approvals/v2/approval-api";
import {
  prepareApprovalFixture,
  selectApprovalScenario,
} from "./approval-handlers";
import styles from "../layouts/AppLayout.module.css";

type RoleChange = components["schemas"]["RoleChangeRequest"];
type Operation = RoleChange["operation"];
type Role = components["schemas"]["RoleCode"];
type Scenario = "default" | "empty" | "slow" | "error";
const operations: { value: Operation; label: string }[] = [
  { value: "ASSIGN_ROLE", label: "Gán vai trò" },
  { value: "REVOKE_ROLE", label: "Thu hồi vai trò" },
  { value: "GRANT_PRIVILEGE", label: "Cấp đặc quyền CHECKER" },
  { value: "REVOKE_PRIVILEGE", label: "Thu hồi đặc quyền CHECKER" },
];
const roleOptions: { value: Role; label: string }[] = [
  { value: "CUSTOMER", label: "CUSTOMER" },
  { value: "OPERATIONS", label: "OPERATIONS" },
  { value: "ADMIN", label: "ADMIN" },
  { value: "AUDITOR", label: "AUDITOR" },
];
const targetFixtureId = "4002";

export function rolePreviewDraft(
  operation: Operation,
  roleCode: Role,
  reason: string,
): RoleChange {
  const common = {
    targetUserId: targetFixtureId,
    ...(reason ? { reason } : {}),
  };
  switch (operation) {
    case "ASSIGN_ROLE":
      return { ...common, operation, roleCode };
    case "REVOKE_ROLE":
      return { ...common, operation, roleCode };
    case "GRANT_PRIVILEGE":
      return { ...common, operation, privilegeCode: "CHECKER" };
    case "REVOKE_PRIVILEGE":
      return { ...common, operation, privilegeCode: "CHECKER" };
  }
}

export function prepareRolePreview() {
  if (!import.meta.env.DEV && import.meta.env.MODE !== "test") return;
  prepareApprovalFixture();
}

function Content({ client }: { client: ApiClient }) {
  const [operation, setOperation] = useState<Operation>("ASSIGN_ROLE");
  const [roleCode, setRoleCode] = useState<Role>("OPERATIONS");
  const [reason, setReason] = useState("");
  const [review, setReview] = useState(false);
  const [scenario, setScenario] = useState<Scenario>("default");
  const isRole = operation === "ASSIGN_ROLE" || operation === "REVOKE_ROLE";
  const draft = rolePreviewDraft(operation, roleCode, reason);
  const approvals = useQuery({
    queryKey: ["role-preview-approvals", scenario],
    queryFn: ({ signal }) =>
      approvalApi.list(client, { page: 0, requestType: "ROLE_CHANGE" }, signal),
    retry: false,
  });
  const operationLabel =
    operations.find((item) => item.value === operation)?.label ?? operation;
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <Brand compact />
        <nav aria-label="Điều hướng preview quản trị">
          Quản trị vai trò · bản xem
        </nav>
        <p>Preview độc lập · dữ liệu tổng hợp</p>
      </aside>
      <div className={styles.workspace}>
        <header className={styles.topbar}>
          <Brand compact />
          <span>Quản trị vai trò và đặc quyền</span>
        </header>
        <main className={styles.main}>
          <Heading
            title="Quản trị vai trò"
            sub="Bản xem yêu cầu thay đổi quyền theo schema hiện có."
          />
          <Alert
            type="warning"
            showIcon
            message="Chờ contract tìm người dùng, quyền phiên và step-up. Bản xem không gửi yêu cầu thay đổi quyền."
            style={{ marginBottom: 20 }}
          />
          <Steps
            current={review ? 1 : 0}
            items={[
              { title: "Nhập yêu cầu" },
              { title: "Kiểm tra" },
              { title: "Gửi · chờ contract" },
            ]}
            style={{ maxWidth: 760, margin: "0 auto 24px" }}
          />
          <Panel title={review ? "Kiểm tra yêu cầu" : "Thông tin thay đổi"}>
            {review ? (
              <>
                <Descriptions
                  column={1}
                  items={[
                    {
                      key: "target",
                      label: "User ID mẫu",
                      children: draft.targetUserId,
                    },
                    {
                      key: "operation",
                      label: "Thao tác",
                      children: operationLabel,
                    },
                    {
                      key: "code",
                      label: isRole ? "Vai trò" : "Đặc quyền",
                      children: isRole ? roleCode : "CHECKER",
                    },
                    {
                      key: "reason",
                      label: "Lý do",
                      children: reason || "Không nhập",
                    },
                  ]}
                />
                <Button onClick={() => setReview(false)}>Sửa thông tin</Button>{" "}
                <Button type="primary" disabled>
                  Tạo yêu cầu
                </Button>
              </>
            ) : (
              <div style={{ maxWidth: 560, display: "grid", gap: 16 }}>
                <label htmlFor="role-target">User ID mẫu</label>
                <Input id="role-target" value={targetFixtureId} readOnly />
                <span>
                  Chọn người dùng thật sẽ có sau contract tìm kiếm user.
                </span>
                <label htmlFor="role-operation">Thao tác</label>
                <Select
                  id="role-operation"
                  aria-label="Thao tác"
                  value={operation}
                  options={operations}
                  onChange={(value: Operation) => setOperation(value)}
                />
                {isRole ? (
                  <>
                    <label htmlFor="role-code">Vai trò</label>
                    <Select
                      id="role-code"
                      aria-label="Vai trò"
                      value={roleCode}
                      options={roleOptions}
                      onChange={(value: Role) => setRoleCode(value)}
                    />
                  </>
                ) : (
                  <>
                    <label htmlFor="privilege-code">Đặc quyền</label>
                    <Input id="privilege-code" value="CHECKER" readOnly />
                  </>
                )}
                <label htmlFor="role-reason">Lý do (không bắt buộc)</label>
                <Input.TextArea
                  id="role-reason"
                  value={reason}
                  maxLength={500}
                  showCount
                  onChange={(event) => setReason(event.target.value)}
                />
                <Button type="primary" onClick={() => setReview(true)}>
                  Kiểm tra yêu cầu
                </Button>
              </div>
            )}
          </Panel>
          <Panel title="Yêu cầu đổi quyền hiện có">
            <label htmlFor="role-scenario">Kịch bản danh sách</label>
            <Select
              id="role-scenario"
              aria-label="Kịch bản danh sách"
              value={scenario}
              style={{ display: "block", width: 220, margin: "8px 0 20px" }}
              options={[
                { value: "default", label: "Dữ liệu mẫu" },
                { value: "empty", label: "Danh sách rỗng" },
                { value: "slow", label: "Tải chậm" },
                { value: "error", label: "Lỗi dịch vụ" },
              ]}
              onChange={(value: Scenario) => {
                selectApprovalScenario(value);
                setScenario(value);
              }}
            />
            {approvals.isPending && (
              <div role="status">
                <Spin /> Đang tải yêu cầu đổi quyền
              </div>
            )}
            {approvals.isError && (
              <Alert
                type="error"
                message="Không thể tải yêu cầu đổi quyền."
                action={
                  <Button onClick={() => void approvals.refetch()}>
                    Thử lại
                  </Button>
                }
              />
            )}
            {approvals.data &&
              (approvals.data.items.length ? (
                approvals.data.items.map((item) => (
                  <p key={item.approvalId}>
                    Yêu cầu #{item.approvalId} · {item.status} · User #
                    {item.targetId}
                  </p>
                ))
              ) : (
                <p>Chưa có yêu cầu đổi quyền.</p>
              ))}
            <a href="/staff/approvals?approvalPreview=v2&requestType=ROLE_CHANGE">
              Xem danh sách phê duyệt chỉ đọc
            </a>
          </Panel>
        </main>
      </div>
    </div>
  );
}

export function RolePreview() {
  const [client, setClient] = useState<ApiClient | null>(null);
  const allowed =
    (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCKS === "true") ||
    import.meta.env.MODE === "test";
  useEffect(() => {
    if (!allowed) return;
    const instance = createApiClient({ baseUrl: "/api/v2" });
    setClient(instance);
    return () => instance.dispose();
  }, [allowed]);
  if (!allowed) return null;
  return client ? (
    <Content client={client} />
  ) : (
    <p role="status">Đang chuẩn bị bản xem</p>
  );
}
