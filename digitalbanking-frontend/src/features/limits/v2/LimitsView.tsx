import { Alert, Button, Descriptions, Select, Spin } from "antd";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { z } from "zod";
import { Heading, Panel, date } from "../../../components/shared";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { customerApi } from "../../accounts/v2/customer-api";
import { formatMoney, isMoney } from "../../../shared/lib/money";
import { isApiId } from "../../../shared/lib/id";

const money = z.string().refine(isMoney);
const id = z.string().refine(isApiId);
const instant = z.iso.datetime({ offset: true });
const item = z.object({
  limitId: id,
  scope: z.enum(["CUSTOMER", "ACCOUNT"]),
  accountId: id.nullable().optional(),
  perTransactionLimit: money,
  dailyLimit: money,
  currency: z.literal("VND"),
  usedToday: money,
  remainingDaily: money,
  effectiveFrom: instant,
  effectiveTo: instant.nullable().optional(),
  version: z.number().int().min(1),
});
const responseSchema = z.object({
  customerId: id,
  currency: z.literal("VND"),
  usageDate: z.iso.date(),
  effectivePerTransactionLimit: money.nullable().optional(),
  effectiveRemainingDaily: money.nullable().optional(),
  items: z.array(item),
  asOf: instant,
});

export function LimitsView({
  client,
  userId,
  scenario,
}: {
  client: ApiClient;
  userId: string;
  scenario: string;
}) {
  const [accountId, setAccountId] = useState("");
  const accounts = useQuery({
    queryKey: [userId, "limit-accounts"],
    queryFn: ({ signal }) => customerApi.accounts(client, signal),
    retry: false,
  });
  const limits = useQuery({
    queryKey: [userId, "limits", accountId, scenario],
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams();
      if (accountId) query.set("accountId", accountId);
      const response = await client.request("/limits", { query, signal });
      const parsed = responseSchema.safeParse(response.data);
      if (response.status !== 200 || !parsed.success)
        throw new ApiError("INVALID_RESPONSE", response);
      return parsed.data;
    },
    retry: false,
  });
  const format = (value: string | null | undefined) =>
    value == null ? "Không có dữ liệu" : formatMoney(value);
  return (
    <section aria-label="Hạn mức của tôi">
      <Heading
        title="Hạn mức của tôi"
        sub="Hạn mức hiện tại và số còn lại trong ngày theo dữ liệu trả về."
      />
      <label htmlFor="limit-account">Tài khoản</label>
      <Select
        id="limit-account"
        aria-label="Lọc hạn mức theo tài khoản"
        value={accountId}
        style={{
          width: "100%",
          maxWidth: 340,
          display: "block",
          marginBottom: 20,
        }}
        onChange={setAccountId}
        loading={accounts.isPending}
        options={[
          { value: "", label: "Tất cả hạn mức" },
          ...(accounts.data?.items.map((a) => ({
            value: a.accountId,
            label: a.accountNumberMasked,
          })) ?? []),
        ]}
      />
      {accounts.isError && (
        <Alert
          type="warning"
          showIcon
          message="Không tải được danh sách tài khoản để lọc."
        />
      )}
      {limits.isPending && (
        <div role="status">
          <Spin /> Đang tải hạn mức
        </div>
      )}
      {limits.isError && (
        <Alert
          type="error"
          showIcon
          role="alert"
          message="Không thể tải hạn mức. Vui lòng thử lại."
          action={
            <Button onClick={() => void limits.refetch()}>Thử lại</Button>
          }
        />
      )}
      {limits.data && (
        <>
          <Panel title="Hạn mức hiệu lực">
            <Descriptions
              column={1}
              colon={false}
              items={[
                {
                  key: "per",
                  label: "Mỗi giao dịch",
                  children: format(limits.data.effectivePerTransactionLimit),
                },
                {
                  key: "remaining",
                  label: "Còn lại trong ngày",
                  children: format(limits.data.effectiveRemainingDaily),
                },
                {
                  key: "usage",
                  label: "Ngày sử dụng",
                  children: limits.data.usageDate,
                },
                {
                  key: "asOf",
                  label: "Cập nhật",
                  children: date(limits.data.asOf),
                },
              ]}
            />
          </Panel>
          <h2>Cấu hình hạn mức</h2>
          {limits.data.items.length === 0 ? (
            <p>Chưa có cấu hình hạn mức.</p>
          ) : (
            limits.data.items.map((limit) => (
              <Panel
                key={limit.limitId}
                title={
                  limit.scope === "CUSTOMER"
                    ? "Hạn mức khách hàng"
                    : "Hạn mức tài khoản"
                }
              >
                <Descriptions
                  column={1}
                  colon={false}
                  items={[
                    ...(limit.accountId
                      ? [
                          {
                            key: "account",
                            label: "Mã tài khoản",
                            children: limit.accountId,
                          },
                        ]
                      : []),
                    {
                      key: "per",
                      label: "Mỗi giao dịch",
                      children: formatMoney(limit.perTransactionLimit),
                    },
                    {
                      key: "daily",
                      label: "Trong ngày",
                      children: formatMoney(limit.dailyLimit),
                    },
                    {
                      key: "used",
                      label: "Đã dùng hôm nay",
                      children: formatMoney(limit.usedToday),
                    },
                    {
                      key: "remaining",
                      label: "Còn lại trong ngày",
                      children: formatMoney(limit.remainingDaily),
                    },
                    {
                      key: "from",
                      label: "Hiệu lực từ",
                      children: date(limit.effectiveFrom),
                    },
                    ...(limit.effectiveTo
                      ? [
                          {
                            key: "to",
                            label: "Hiệu lực đến",
                            children: date(limit.effectiveTo),
                          },
                        ]
                      : []),
                  ]}
                />
              </Panel>
            ))
          )}
        </>
      )}
    </section>
  );
}
