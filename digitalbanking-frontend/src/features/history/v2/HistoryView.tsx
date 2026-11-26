import { Alert, Button, Descriptions, Select, Spin } from "antd";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { ApiError, type ApiClient } from "../../../shared/api/client";
import { historyApi } from "./history-api";
import type { createV1HistoryReadApi } from "../v1/history-read-api";
import type { createV1CustomerReadApi } from "../../accounts/v1/customer-read-api";
import { customerApi } from "../../accounts/v2/customer-api";
import { formatMoney } from "../../../shared/lib/money";
import { isMoney } from "../../../shared/lib/money";
import { Heading, Panel, date } from "../../../components/shared";
import styles from "./HistoryView.module.css";

const statusText: Record<string, string> = {
  PENDING: "Đang xử lý",
  COMPLETED: "Hoàn tất",
  FAILED: "Thất bại",
};
const directionText: Record<string, string> = {
  DEBIT: "Tiền ra",
  CREDIT: "Tiền vào",
  INTERNAL: "Nội bộ",
};
const label = (value: string, dictionary: Record<string, string>) =>
  Object.hasOwn(dictionary, value) ? dictionary[value] : "Chưa hỗ trợ";
export function HistoryView({
  client,
  userId,
  mode,
  reference,
  scenario,
  v1History,
  v1Customer,
}: {
  client: ApiClient;
  userId: string;
  mode: "authPreview" | "customerPreview" | "v1";
  reference?: string;
  scenario?: string;
  v1History?: ReturnType<typeof createV1HistoryReadApi>;
  v1Customer?: ReturnType<typeof createV1CustomerReadApi>;
}) {
  const location = useLocation(),
    navigate = useNavigate();
  const params = new URLSearchParams(location.search);
  const status = ["PENDING", "COMPLETED", "FAILED"].includes(
    params.get("status") ?? "",
  )
    ? (params.get("status") ?? "")
    : "";
  const accountId = params.get("accountId") ?? "";
  const direction = ["DEBIT", "CREDIT", "INTERNAL"].includes(
    params.get("direction") ?? "",
  )
    ? (params.get("direction") ?? "")
    : "";
  const from = params.get("from") ?? "",
    to = params.get("to") ?? "";
  const minAmount = params.get("minAmount") ?? "",
    maxAmount = params.get("maxAmount") ?? "";
  const localDay = (value: string) =>
    value && !Number.isNaN(Date.parse(value))
      ? new Date(Date.parse(value) + 7 * 60 * 60_000).toISOString().slice(0, 10)
      : "";
  const [draft, setDraft] = useState({
    accountId,
    direction,
    from: localDay(from),
    to: localDay(to),
    minAmount,
    maxAmount,
  });
  const [filterError, setFilterError] = useState("");
  const rawPage = params.get("page") ?? "0";
  const page =
    /^(0|[1-9][0-9]*)$/.test(rawPage) && Number.isSafeInteger(Number(rawPage))
      ? Number(rawPage)
      : 0;
  const goList = (
    nextStatus = status,
    nextPage = page,
    overrides?: Record<string, string>,
  ) => {
    const query = new URLSearchParams(
      mode === "v1" ? undefined : { [mode]: "v2" },
    );
    if (nextStatus) query.set("status", nextStatus);
    if (nextPage) query.set("page", String(nextPage));
    for (const key of [
      "accountId",
      "direction",
      "from",
      "to",
      "minAmount",
      "maxAmount",
    ]) {
      const value = overrides ? overrides[key] : params.get(key);
      if (value) query.set(key, value);
    }
    void navigate(`/customer/history?${query}`);
  };
  const accountList = useQuery({
    queryKey: [mode, userId, "history-accounts"],
    queryFn: ({ signal }) =>
      v1Customer
        ? v1Customer.accounts(signal)
        : customerApi.accounts(client, signal),
    enabled: !reference,
    retry: false,
  });
  const applyFilters = () => {
    if (draft.direction && !draft.accountId)
      return setFilterError("Chọn tài khoản khi lọc chiều giao dịch.");
    if (
      (draft.minAmount && !isMoney(draft.minAmount)) ||
      (draft.maxAmount && !isMoney(draft.maxAmount))
    )
      return setFilterError("Số tiền phải có hai chữ số thập phân.");
    if (
      draft.minAmount &&
      draft.maxAmount &&
      BigInt(draft.minAmount.replace(".", "")) >
        BigInt(draft.maxAmount.replace(".", ""))
    )
      return setFilterError("Số tiền tối thiểu phải không lớn hơn tối đa.");
    if (draft.from && draft.to && draft.from >= draft.to)
      return setFilterError("Ngày đến (không bao gồm) phải sau ngày từ.");
    setFilterError("");
    const atStart = (value: string) =>
      value ? new Date(`${value}T00:00:00+07:00`).toISOString() : "";
    goList(status, 0, {
      ...draft,
      from: atStart(draft.from),
      to: atStart(draft.to),
    });
  };
  const list = useQuery({
    queryKey: [
      mode,
      userId,
      "history",
      status,
      accountId,
      direction,
      from,
      to,
      minAmount,
      maxAmount,
      page,
      scenario,
    ],
    queryFn: ({ signal }) =>
      v1History
        ? v1History.list(
            {
              page,
              status: (status || undefined) as
                | "PENDING"
                | "COMPLETED"
                | "FAILED"
                | undefined,
              accountId: accountId || undefined,
              direction: (direction || undefined) as
                | "DEBIT"
                | "CREDIT"
                | "INTERNAL"
                | undefined,
              from: from || undefined,
              to: to || undefined,
              minAmount: minAmount || undefined,
              maxAmount: maxAmount || undefined,
            },
            signal,
          )
        : historyApi.list(
            client,
            {
              page,
              status: status || undefined,
              accountId: accountId || undefined,
              direction: direction || undefined,
              from: from || undefined,
              to: to || undefined,
              minAmount: minAmount || undefined,
              maxAmount: maxAmount || undefined,
            },
            signal,
          ),
    enabled: !reference,
    retry: false,
  });
  const detail = useQuery({
    queryKey: [mode, userId, "transaction", reference, scenario],
    queryFn: ({ signal }) =>
      v1History
        ? v1History.detail(reference!, signal)
        : historyApi.detail(client, reference!, signal),
    enabled: Boolean(reference),
    retry: false,
  });
  const current = reference ? detail : list;
  if (
    !reference &&
    list.data &&
    page > 0 &&
    page >= list.data.page.totalPages
  ) {
    const corrected = new URLSearchParams(location.search);
    corrected.set("page", String(Math.max(0, list.data.page.totalPages - 1)));
    return <Navigate to={`/customer/history?${corrected}`} replace />;
  }
  return (
    <>
      <Heading
        title={reference ? "Chi tiết giao dịch" : "Lịch sử giao dịch"}
        sub={
          mode === "v1"
            ? "Lịch sử giao dịch của bạn."
            : "Dữ liệu giao dịch mô phỏng của bạn."
        }
      />
      {reference ? (
        <Button onClick={() => goList(status, page)}>Về lịch sử</Button>
      ) : (
        <div className={styles.filters}>
          <label htmlFor="history-status">Trạng thái</label>
          <Select
            id="history-status"
            aria-label="Trạng thái"
            value={status}
            options={[
              { value: "", label: "Tất cả" },
              { value: "PENDING", label: "Đang xử lý" },
              { value: "COMPLETED", label: "Hoàn tất" },
              { value: "FAILED", label: "Thất bại" },
            ]}
            onChange={(value) => goList(value, 0)}
          />
          <label htmlFor="history-account">Tài khoản</label>
          <select
            id="history-account"
            value={draft.accountId}
            onChange={(event) =>
              setDraft({
                ...draft,
                accountId: event.target.value,
                direction: "",
              })
            }
          >
            <option value="">Tất cả</option>
            {accountList.data?.items.map((account) => (
              <option key={account.accountId} value={account.accountId}>
                {account.accountNumberMasked}
              </option>
            ))}
          </select>
          <label htmlFor="history-direction">Chiều giao dịch</label>
          <select
            id="history-direction"
            value={draft.direction}
            disabled={!draft.accountId}
            onChange={(event) =>
              setDraft({ ...draft, direction: event.target.value })
            }
          >
            <option value="">Tất cả</option>
            <option value="DEBIT">Tiền ra</option>
            <option value="CREDIT">Tiền vào</option>
            <option value="INTERNAL">Nội bộ</option>
          </select>
          <label htmlFor="history-from">Từ ngày</label>
          <input
            id="history-from"
            type="date"
            value={draft.from}
            onChange={(event) =>
              setDraft({ ...draft, from: event.target.value })
            }
          />
          <label htmlFor="history-to">Đến ngày (không bao gồm)</label>
          <input
            id="history-to"
            type="date"
            value={draft.to}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
          />
          <label htmlFor="history-min">Số tiền tối thiểu</label>
          <input
            id="history-min"
            inputMode="decimal"
            placeholder="0.00"
            value={draft.minAmount}
            onChange={(event) =>
              setDraft({ ...draft, minAmount: event.target.value })
            }
          />
          <label htmlFor="history-max">Số tiền tối đa</label>
          <input
            id="history-max"
            inputMode="decimal"
            placeholder="0.00"
            value={draft.maxAmount}
            onChange={(event) =>
              setDraft({ ...draft, maxAmount: event.target.value })
            }
          />
          <Button onClick={applyFilters}>Lọc</Button>
          {filterError && <span role="alert">{filterError}</span>}
        </div>
      )}
      {current.isPending && (
        <div role="status">
          <Spin /> Đang tải giao dịch
        </div>
      )}
      {current.isError && (
        <Alert
          type="error"
          showIcon
          message={
            current.error instanceof ApiError &&
            current.error.meta.status === 403
              ? "Bạn không có quyền xem giao dịch này."
              : current.error instanceof ApiError &&
                  current.error.meta.status === 404
                ? "Không tìm thấy giao dịch."
                : reference
                  ? "Không thể tải chi tiết giao dịch."
                  : "Không thể tải lịch sử giao dịch."
          }
          action={
            <Button onClick={() => void current.refetch()}>Thử lại</Button>
          }
        />
      )}
      {!reference && list.data && (
        <Panel
          title="Giao dịch của tôi"
          sub={`Cập nhật: ${date(list.data.asOf)}`}
        >
          {list.data.items.length === 0 ? (
            <p>Chưa có giao dịch phù hợp.</p>
          ) : (
            <div className={styles.items}>
              {list.data.items.map((item) => (
                <button
                  className={styles.item}
                  type="button"
                  key={item.reference}
                  onClick={() =>
                    void navigate(
                      `/customer/history/${item.reference}${location.search}`,
                    )
                  }
                >
                  <span>
                    <strong>{item.counterparty.displayName}</strong>
                    <small>
                      {item.counterparty.accountNumberMasked} ·{" "}
                      {date(item.createdAt)}
                    </small>
                  </span>
                  <span>
                    <strong>{formatMoney(item.amount)}</strong>
                    <small>
                      {label(item.direction, directionText)} ·{" "}
                      {label(item.status, statusText)}
                    </small>
                  </span>
                </button>
              ))}
            </div>
          )}
          <div className={styles.paging}>
            <Button
              disabled={list.data.page.first}
              onClick={() => goList(status, page - 1)}
            >
              Trang trước
            </Button>
            <span>
              Trang {list.data.page.number + 1}/
              {Math.max(1, list.data.page.totalPages)}
            </span>
            <Button
              disabled={list.data.page.last}
              onClick={() => goList(status, page + 1)}
            >
              Trang sau
            </Button>
          </div>
        </Panel>
      )}
      {reference && detail.data && (
        <Panel narrow title="Thông tin giao dịch">
          <p className={styles.amount}>{formatMoney(detail.data.amount)}</p>
          <Descriptions
            column={1}
            colon={false}
            items={[
              {
                key: "reference",
                label: "Mã giao dịch",
                children: detail.data.reference,
              },
              {
                key: "direction",
                label: "Chiều giao dịch",
                children: label(detail.data.direction, directionText),
              },
              {
                key: "status",
                label: "Trạng thái",
                children: label(detail.data.status, statusText),
              },
              {
                key: "counterparty",
                label: "Đối tác",
                children: detail.data.counterparty.displayName,
              },
              {
                key: "number",
                label: "Tài khoản đối tác",
                children: detail.data.counterparty.accountNumberMasked,
              },
              {
                key: "description",
                label: "Nội dung",
                children: detail.data.description || "—",
              },
              {
                key: "created",
                label: "Tạo lúc",
                children: date(detail.data.createdAt),
              },
            ]}
          />
        </Panel>
      )}
    </>
  );
}

export function RecentTransactions({
  client,
  userId,
  mode,
}: {
  client: ApiClient;
  userId: string;
  mode: "authPreview" | "customerPreview";
}) {
  const navigate = useNavigate();
  const recent = useQuery({
    queryKey: [userId, "recent-transactions"],
    queryFn: ({ signal }) =>
      historyApi.list(
        client,
        { page: 0, size: 5, status: "COMPLETED" },
        signal,
      ),
    retry: false,
  });
  return (
    <Panel title="Giao dịch gần đây">
      {recent.isPending && (
        <div role="status">
          <Spin /> Đang tải giao dịch gần đây
        </div>
      )}
      {recent.isError && (
        <Alert
          type="error"
          message="Không thể tải giao dịch gần đây."
          action={
            <Button onClick={() => void recent.refetch()}>Thử lại</Button>
          }
        />
      )}
      {recent.data &&
        (recent.data.items.length ? (
          <div className={styles.items}>
            {recent.data.items.map((item) => (
              <button
                className={styles.item}
                key={item.reference}
                type="button"
                onClick={() =>
                  void navigate(
                    `/customer/history/${item.reference}?${mode}=v2`,
                  )
                }
              >
                <span>
                  <strong>{item.counterparty.displayName}</strong>
                  <small>{date(item.createdAt)}</small>
                </span>
                <strong>{formatMoney(item.amount)}</strong>
              </button>
            ))}
          </div>
        ) : (
          <p>Chưa có giao dịch gần đây.</p>
        ))}
      <Button onClick={() => void navigate(`/customer/history?${mode}=v2`)}>
        Xem lịch sử
      </Button>
    </Panel>
  );
}
