import { useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Button, Form, Input, Select, Table, Descriptions, Alert } from "antd";
import { ArrowDownOutlined, ArrowUpOutlined } from "@ant-design/icons";
import { Link, useParams } from "react-router-dom";
import {
  get,
  page,
  queryString,
  transferCheck,
  type Schema,
} from "../../api/client";
import { session } from "../../stores/session";
import {
  EmptyState,
  ErrorPanel,
  Heading,
  Loading,
  Money,
  Panel,
  Status,
  date,
} from "../../components/shared";
import { TransferOtp } from "./TransferPage";

export const transferRowCheck = (v: Record<string, unknown>) =>
  [
    "transferId",
    "direction",
    "status",
    "counterpartyAccountMasked",
    "counterpartyDisplayName",
    "amount",
    "createdAt",
  ].every((k) => typeof v[k] === "string") && /^\d+$/.test(v.amount as string);
export function utcFilter(value?: string) {
  return value ? new Date(`${value}:00+07:00`).toISOString() : undefined;
}
export function HistoryList({
  recent = false,
  filters = {},
}: {
  recent?: boolean;
  filters?: Record<string, string | undefined>;
}) {
  const user = session((s) => s.user);
  const query = useInfiniteQuery({
    queryKey: [user?.userId, "history", recent, filters],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      page<Schema<"TransferListItem">>(
        `/transfers${queryString({ limit: recent ? "5" : "20", ...filters, status: recent ? "COMPLETED" : filters.status, cursor: pageParam })}`,
        signal,
        transferRowCheck,
      ),
    getNextPageParam: (last) => last.nextCursor || undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items) || [];
  const unique = [
    ...new Map(rows.map((row) => [row.transferId, row])).values(),
  ];
  const columns = [
    {
      title: "Giao dịch / Đối ứng",
      dataIndex: "counterpartyDisplayName",
      render: (_: unknown, row: Schema<"TransferListItem">) => (
        <div className="direction">
          <span
            className={`direction-icon ${row.direction === "INCOMING" ? "incoming" : ""}`}
          >
            {row.direction === "INCOMING" ? (
              <ArrowDownOutlined />
            ) : (
              <ArrowUpOutlined />
            )}
          </span>
          <span>
            <Link to={`/customer/transfers/${row.transferId}`}>
              {row.counterpartyDisplayName}
            </Link>
            <small>
              {row.direction === "INCOMING" ? "Tiền vào" : "Tiền ra"} ·{" "}
              {row.counterpartyAccountMasked}
            </small>
          </span>
        </div>
      ),
    },
    {
      title: "Thời gian · giờ Việt Nam",
      dataIndex: "createdAt",
      render: (v: string) => date(v),
    },
    {
      title: "Số tiền",
      dataIndex: "amount",
      align: "right" as const,
      render: (v: string, row: Schema<"TransferListItem">) => (
        <span className={row.direction === "INCOMING" ? "money-in" : ""}>
          {row.direction === "INCOMING" ? "+" : "−"}
          <Money value={v} />
        </span>
      ),
    },
    {
      title: "Trạng thái",
      dataIndex: "status",
      render: (v: string) => <Status value={v} />,
    },
  ];
  return (
    <>
      <ErrorPanel
        error={query.error}
        retry={() =>
          void (query.isFetchNextPageError
            ? query.fetchNextPage()
            : query.refetch())
        }
      />
      {query.isPending ? (
        <Loading />
      ) : !unique.length && !query.error ? (
        <EmptyState text="Chưa có giao dịch phù hợp." />
      ) : (
        <>
          <div className="desktop-transactions table-region">
            <Table
              rowKey="transferId"
              columns={columns}
              dataSource={unique}
              pagination={false}
              scroll={{ x: 650 }}
            />
          </div>
          <div className="mobile-transactions">
            {unique.map((row) => (
              <Link
                key={row.transferId}
                className="transaction-card"
                to={`/customer/transfers/${row.transferId}`}
              >
                <div className="direction">
                  <span
                    className={`direction-icon ${row.direction === "INCOMING" ? "incoming" : ""}`}
                  >
                    {row.direction === "INCOMING" ? (
                      <ArrowDownOutlined />
                    ) : (
                      <ArrowUpOutlined />
                    )}
                  </span>
                  <span>
                    <strong>{row.counterpartyDisplayName}</strong>
                    <small>
                      {row.direction === "INCOMING" ? "Tiền vào" : "Tiền ra"} ·{" "}
                      {date(row.createdAt)}
                    </small>
                  </span>
                </div>
                <div className="transaction-value">
                  <span
                    className={row.direction === "INCOMING" ? "money-in" : ""}
                  >
                    {row.direction === "INCOMING" ? "+" : "−"}
                    <Money value={row.amount} />
                  </span>
                  <Status value={row.status} />
                </div>
              </Link>
            ))}
          </div>
        </>
      )}
      {!recent && query.hasNextPage && (
        <div className="panel-bottom">
          <Button
            loading={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            Tải thêm
          </Button>
        </div>
      )}
    </>
  );
}
export function HistoryPage() {
  const [filters, setFilters] = useState<Record<string, string | undefined>>(
    {},
  );
  return (
    <>
      <Heading
        title="Lịch sử giao dịch"
        sub="Giao dịch và trạng thái được cập nhật từ hệ thống."
      />
      <Panel>
        <Form
          layout="vertical"
          className="filters"
          onFinish={(v) =>
            setFilters({
              status: v.status,
              from: utcFilter(v.from),
              to: utcFilter(v.to),
            })
          }
        >
          <Form.Item name="status" label="Trạng thái">
            <Select
              allowClear
              placeholder="Tất cả trạng thái"
              options={["COMPLETED", "AWAITING_OTP", "EXPIRED", "FAILED"].map(
                (value) => ({ value, label: value }),
              )}
            />
          </Form.Item>
          <Form.Item name="from" label="Từ · giờ Việt Nam">
            <Input type="datetime-local" />
          </Form.Item>
          <Form.Item
            name="to"
            label="Đến (không bao gồm)"
            dependencies={["from"]}
            rules={[
              ({ getFieldValue }) => ({
                validator(_, value) {
                  return !value ||
                    !getFieldValue("from") ||
                    value > getFieldValue("from")
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(
                          "Thời gian kết thúc phải sau thời gian bắt đầu.",
                        ),
                      );
                },
              }),
            ]}
          >
            <Input type="datetime-local" />
          </Form.Item>
          <Button htmlType="submit" type="primary">
            Lọc
          </Button>
        </Form>
        <HistoryList filters={filters} />
      </Panel>
    </>
  );
}
export function TransferDetailPage() {
  const { transferId } = useParams();
  const user = session((s) => s.user);
  const query = useQuery({
    queryKey: [user?.userId, "transfer", transferId],
    queryFn: ({ signal }) =>
      get<Schema<"Transfer">>(
        `/transfers/${encodeURIComponent(transferId || "")}`,
        signal,
        transferCheck,
      ),
  });
  const accounts = useQuery({
    queryKey: [user?.userId, "detail-owner-accounts"],
    queryFn: ({ signal }) => page<Schema<"Account">>("/accounts", signal),
  });
  const row = query.data;
  const ownsSource =
    !!row &&
    !!accounts.data?.items.some((a) => a.accountId === row.sourceAccountId);
  return (
    <>
      <Heading
        title="Chi tiết giao dịch"
        sub="Chỉ trạng thái COMPLETED mới thay đổi số dư."
      />
      <ErrorPanel error={query.error} retry={() => void query.refetch()} />
      {query.isPending ? (
        <Loading />
      ) : (
        row && (
          <Panel narrow title="Thông tin giao dịch">
            <div className="receipt-amount">
              <Money value={row.amount} />
              <Status value={row.status} />
            </div>
            <Descriptions
              column={1}
              items={[
                { key: "id", label: "Mã giao dịch", children: row.transferId },
                { key: "memo", label: "Lời nhắn", children: row.memo || "—" },
                {
                  key: "date",
                  label: "Thời gian tạo",
                  children: date(row.createdAt),
                },
                {
                  key: "done",
                  label: "Hoàn tất",
                  children: date(row.completedAt),
                },
              ]}
            />
            {row.status === "FAILED" && (
              <Alert
                className="flow-alert"
                showIcon
                type="error"
                message="Giao dịch thất bại"
                description={`Mã lý do: ${row.failureCode || "Không xác định"}. Không thể nhập OTP lại cho giao dịch này.`}
              />
            )}
            {row.status === "EXPIRED" && (
              <Alert
                className="flow-alert"
                type="warning"
                showIcon
                message="Giao dịch đã hết hạn. Tiền chưa được chuyển."
              />
            )}
            {row.status === "AWAITING_OTP" && ownsSource && (
              <TransferOtp
                transfer={row}
                onUpdate={() => void query.refetch()}
              />
            )}
            {!["AWAITING_OTP", "COMPLETED", "EXPIRED", "FAILED"].includes(
              row.status,
            ) && (
              <Alert
                type="info"
                message="Trạng thái mới từ hệ thống. Hãy cập nhật để kiểm tra."
              />
            )}
            <div className="actions spaced">
              <Button onClick={() => void query.refetch()}>
                Kiểm tra trạng thái
              </Button>
              <Link to="/customer/transfers">Về lịch sử</Link>
            </div>
          </Panel>
        )
      )}
    </>
  );
}
