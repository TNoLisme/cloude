import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  Select,
  Table,
  Descriptions,
} from "antd";
import { page, queryString, type Schema } from "../../api/client";
import { session } from "../../stores/session";
import {
  ErrorPanel,
  Heading,
  Panel,
  Status,
  date,
} from "../../components/shared";
import { utcFilter } from "../transfers/HistoryPages";
type Row = Schema<"AuditEvent"> | Schema<"RiskFlag">;
const detailLabels: Record<string, string> = {
  eventId: "Mã sự kiện",
  eventType: "Sự kiện",
  actorId: "Người thực hiện",
  targetType: "Loại đối tượng",
  targetId: "Mã đối tượng",
  outcome: "Kết quả",
  occurredAt: "Thời gian · giờ Việt Nam",
  correlationId: "Mã đối chiếu",
  summary: "Tóm tắt",
  flagId: "Mã cảnh báo",
  transferId: "Mã giao dịch",
  ruleId: "Quy tắc",
  ruleVersion: "Phiên bản",
  reason: "Lý do cảnh báo",
  detectedAt: "Thời gian · giờ Việt Nam",
};
export function ReadOnlyPage({ mode }: { mode: "audit" | "risk" }) {
  const audit = mode === "audit";
  const user = session((s) => s.user);
  const [filters, setFilters] = useState<Record<string, string | undefined>>(
    {},
  );
  const [selected, setSelected] = useState<Row | null>(null);
  const query = useInfiniteQuery({
    queryKey: [user?.userId, mode, filters],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      page<Row>(
        `${audit ? "/audit-events" : "/operator/risk-flags"}${queryString({ ...filters, limit: "20", cursor: pageParam })}`,
        signal,
        (v) =>
          (audit
            ? [
                "eventId",
                "eventType",
                "targetType",
                "occurredAt",
                "outcome",
                "summary",
              ]
            : [
                "flagId",
                "transferId",
                "ruleId",
                "ruleVersion",
                "reason",
                "detectedAt",
              ]
          ).every((k) => typeof v[k] === "string"),
      ),
    getNextPageParam: (last) => last.nextCursor || undefined,
  });
  const rows = query.data?.pages.flatMap((p) => p.items) || [];
  const unique = [
    ...new Map(
      rows.map((row) => [
        audit
          ? (row as Schema<"AuditEvent">).eventId
          : (row as Schema<"RiskFlag">).flagId,
        row,
      ]),
    ).values(),
  ];
  const columns = audit
    ? [
        {
          title: "Thời gian · giờ Việt Nam",
          dataIndex: "occurredAt",
          render: (v: string) => date(v),
        },
        { title: "Sự kiện", dataIndex: "eventType" },
        {
          title: "Người thực hiện",
          dataIndex: "actorId",
          render: (v: string) => v || "—",
        },
        { title: "Đối tượng", dataIndex: "targetType" },
        {
          title: "Kết quả",
          dataIndex: "outcome",
          render: (v: string) => <Status value={v} />,
        },
        { title: "Tóm tắt", dataIndex: "summary" },
      ]
    : [
        {
          title: "Thời gian · giờ Việt Nam",
          dataIndex: "detectedAt",
          render: (v: string) => date(v),
        },
        { title: "Quy tắc", dataIndex: "ruleId" },
        { title: "Phiên bản", dataIndex: "ruleVersion" },
        { title: "Giao dịch", dataIndex: "transferId" },
        { title: "Lý do cảnh báo", dataIndex: "reason" },
      ];
  return (
    <>
      <Heading
        title={audit ? "Nhật ký kiểm toán" : "Cờ rủi ro"}
        sub="Thông tin chỉ đọc theo quyền được cấp."
      />
      {!audit && (
        <Alert
          className="flow-alert"
          type="info"
          showIcon
          message="Cờ rủi ro là cảnh báo theo quy tắc, không phải kết luận gian lận."
          description="Đánh giá sau giao dịch theo cơ chế best-effort; chưa có cờ không chứng minh mọi giao dịch đã được kiểm tra."
        />
      )}
      <Panel>
        <Form
          layout="vertical"
          className="filters"
          onFinish={(v) =>
            setFilters({
              ...(audit
                ? { eventType: v.kind, actorId: v.id }
                : { ruleId: v.kind, transferId: v.id }),
              from: utcFilter(v.from),
              to: utcFilter(v.to),
            })
          }
        >
          <Form.Item
            name="kind"
            label={audit ? "Loại sự kiện" : "Quy tắc"}
            rules={[{ max: 80 }]}
          >
            {audit ? (
              <Input placeholder="Nhập loại sự kiện" maxLength={80} />
            ) : (
              <Select
                allowClear
                placeholder="Tất cả quy tắc"
                options={["LARGE_TRANSFER", "HIGH_FREQUENCY"].map((value) => ({
                  value,
                  label: value,
                }))}
              />
            )}
          </Form.Item>
          <Form.Item
            name="id"
            label={audit ? "Actor ID" : "Transfer ID"}
            rules={[
              {
                pattern:
                  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
                message: "Nhập UUID hợp lệ.",
              },
            ]}
          >
            <Input placeholder="UUID (tùy chọn)" />
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
                        new Error("Thời gian kết thúc phải sau bắt đầu."),
                      );
                },
              }),
            ]}
          >
            <Input type="datetime-local" />
          </Form.Item>
          <Button type="primary" htmlType="submit">
            Lọc
          </Button>
          <Button onClick={() => void query.refetch()}>Cập nhật</Button>
        </Form>
        <ErrorPanel
          error={query.error}
          retry={() =>
            void (query.isFetchNextPageError
              ? query.fetchNextPage()
              : query.refetch())
          }
        />
        <div className="table-region">
          <Table
            rowKey={audit ? "eventId" : "flagId"}
            columns={[
              ...columns,
              {
                title: "Chi tiết",
                key: "details",
                render: (_: unknown, row: Row) => (
                  <Button
                    type="link"
                    size="small"
                    onClick={() => setSelected(row)}
                  >
                    Xem
                  </Button>
                ),
              },
            ]}
            dataSource={unique}
            loading={query.isPending}
            pagination={false}
            scroll={{ x: audit ? 1200 : 1000 }}
            locale={{ emptyText: "Chưa có dữ liệu phù hợp." }}
          />
        </div>
        {query.hasNextPage && (
          <div className="panel-bottom">
            <Button
              loading={query.isFetchingNextPage}
              onClick={() => void query.fetchNextPage()}
            >
              Tải thêm
            </Button>
          </div>
        )}
      </Panel>
      <Drawer
        title={audit ? "Chi tiết sự kiện" : "Chi tiết cờ rủi ro"}
        open={!!selected}
        onClose={() => setSelected(null)}
        width={520}
      >
        <Descriptions
          column={1}
          items={
            selected
              ? Object.entries(selected)
                  .filter(([key]) => key in detailLabels)
                  .map(([key, value]) => ({
                    key,
                    label: detailLabels[key],
                    children:
                      (key === "occurredAt" || key === "detectedAt") &&
                      typeof value === "string" ? (
                        date(value)
                      ) : key === "outcome" && typeof value === "string" ? (
                        <Status value={value} />
                      ) : typeof value === "string" ? (
                        value
                      ) : value == null ? (
                        "—"
                      ) : (
                        String(value)
                      ),
                  }))
              : []
          }
        />
      </Drawer>
    </>
  );
}
