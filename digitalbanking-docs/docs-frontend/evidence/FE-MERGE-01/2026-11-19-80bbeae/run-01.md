# Phiếu FE-MERGE-01 / run-01 — đưa các task mock lên main

## 1. Định danh và đầu vào

19/11/2026 Asia/Bangkok. Người dùng yêu cầu trong chat: “hãy commit và push lên nhánh main theo các task đã làm cho tôi”. FE bắt đầu `frontend@b9ba604` với working tree dirty; `main` sau fetch ở `d1efbc6`. Đây là yêu cầu Git, không phải xác nhận UI hoặc tích hợp API BE.

## 2. Kiểm tra sẵn sàng

Đã tạo commit theo task: FE-DOC-03 `ec4fe9e`, FE-FND-03-ICONS `48e2afc`, FE-02-01 `cbd3399`, FE-04-01-MOCK `194c53f`, FE-05-01-MOCK `80bbeae`. Các task mock giữ trạng thái review/đang làm như tracker. Main có 18 commit riêng, đã fast-forward local main đến `d1efbc6` rồi merge frontend. Xung đột `.gitignore` và `AGENTS.md` giữ cả quy tắc chung/FE; file backend `modules/02-identity-and-auth.md` đã được main chuyển cấu trúc, giữ xóa file cũ. Không sửa BE runtime.

## 3. Thay đổi và kịch bản

Merge đưa source FE/mock/docs/evidence vào main, không bật mock ở production, không đổi API BE hoặc đóng GAP. Push chờ gate và merge commit.

## 4. Đối chiếu hoàn thành

Trên cây merge local: `eslint . --max-warnings=0`, `tsc -b`, `node scripts/check-api.mjs`, `prettier --check ...`, `vitest run` (21 files/171 tests) và `vite build` đều exit 0. `git diff --cached --check` exit 0. Browser FE-05 vẫn chưa kiểm chứng do timeout, như phiếu task. Chưa chạy BE integration hoặc FET đầy đủ; không coi task cha hoàn thành.

## 5. Tài liệu và review

Nguồn trạng thái ở tracker và phiếu từng task. Yêu cầu push main là xác nhận mốc Git cụ thể từ người dùng ngày 19/11; không suy ra duyệt UI. Chưa có PR/review BE hoặc xác nhận runtime v2.

## 6. Kết quả lần chạy

Đang chờ tạo merge commit và push main; đối chiếu kết quả Git thực tế ở phản hồi cuối.
