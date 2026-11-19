import { Button, Descriptions, Tag } from "antd";
import { Panel, date } from "../../../components/shared";
import { FeatureIcon } from "../../../components/icons";
import { formatMoney, isMoney } from "../../../shared/lib/money";
import type { components } from "../../../shared/api/generated/v2/openapi";

type Profile = Omit<components["schemas"]["CustomerProfile"], "status"> & {
  status: string;
};
type Account = Omit<components["schemas"]["AccountSummary"], "status"> & {
  status: string;
};
type Detail = Omit<
  components["schemas"]["AccountDetail"],
  "status" | "owner"
> & {
  status: string;
  owner: Omit<components["schemas"]["CustomerSummary"], "status"> & {
    status: string;
  };
};
const balance = (value: string) =>
  isMoney(value) ? formatMoney(value) : "Không có dữ liệu";
const labels: Record<string, string> = {
  ACTIVE: "Đang hoạt động",
  LOCKED: "Đã khóa",
  CLOSED: "Đã đóng",
  INACTIVE: "Ngừng hoạt động",
};
const label = (value: string) =>
  Object.hasOwn(labels, value) ? labels[value] : "Trạng thái chưa hỗ trợ";

export function CustomerProfilePanel({ profile }: { profile: Profile }) {
  return (
    <Panel narrow title="Thông tin của bạn">
      <Descriptions
        column={1}
        colon={false}
        items={[
          { key: "name", label: "Họ và tên", children: profile.fullName },
          {
            key: "phone",
            label: "Điện thoại",
            children: profile.phoneMasked ?? "Chưa có thông tin",
          },
          {
            key: "email",
            label: "Email",
            children: profile.emailMasked ?? "Chưa có thông tin",
          },
          {
            key: "status",
            label: "Trạng thái",
            children: <Tag>{label(profile.status)}</Tag>,
          },
          {
            key: "created",
            label: "Ngày tham gia",
            children: date(profile.createdAt),
          },
        ]}
      />
    </Panel>
  );
}
export function CustomerAccountCard({
  account,
  onDetails,
}: {
  account: Account;
  onDetails?: () => void;
}) {
  return (
    <Panel title="Tài khoản VND">
      <div className="balance-card">
        <div className="balance-top">
          <span className="account-label">
            <FeatureIcon name="account" size="sm" />{" "}
            {account.accountNumberMasked}
          </span>
        </div>
        <p className="muted small">Số dư khả dụng</p>
        <p className="balance-value">{balance(account.availableBalance)}</p>
        <p className="muted small">Cập nhật: {date(account.asOf)}</p>
        {onDetails && (
          <Button className="spaced" onClick={onDetails}>
            Xem chi tiết tài khoản
          </Button>
        )}
      </div>
    </Panel>
  );
}
export function CustomerAccountDetailPanel({ account }: { account: Detail }) {
  return (
    <Panel narrow title="Tài khoản VND">
      <p className="balance-value">{balance(account.availableBalance)}</p>
      <Descriptions
        column={1}
        colon={false}
        items={[
          {
            key: "owner",
            label: "Chủ tài khoản",
            children: account.owner.fullName,
          },
          {
            key: "number",
            label: "Số tài khoản",
            children: account.accountNumberMasked,
          },
          { key: "currency", label: "Loại tiền", children: account.currency },
          {
            key: "status",
            label: "Trạng thái",
            children: <Tag>{label(account.status)}</Tag>,
          },
          {
            key: "created",
            label: "Ngày mở",
            children: date(account.createdAt),
          },
          { key: "asof", label: "Cập nhật", children: date(account.asOf) },
        ]}
      />
    </Panel>
  );
}
