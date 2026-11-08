
### Critical Requirements

- **Strong transactional consistency**: Tính đồng nhất giao dịch mạnh mẽ.
    
- **Idempotent transfer operations**: Thao tác chuyển tiền có tính trùng lặp an toàn (Idempotency).
    
- **Authentication + strong authorization**: Xác thực và phân quyền chặt chẽ.
    
- **Immutable / auditable history**: Lịch sử không thể thay đổi / có thể kiểm toán.
    
- **Encryption in transit & at rest**: Mã hóa dữ liệu khi truyền tải và khi lưu trữ.
    
- **High traceability**: Khả năng truy xuất / định vết cao.
    

### Failure Scenarios (Các kịch bản lỗi cần xử lý)

```
Debit succeeds     -> Credit fails
Client timeout     -> Request is retried
Service crashes during transaction
```

### Suggested AI / Data Feature

- Rule-based hoặc ML-assisted anomaly / fraud flagging (Cảnh báo bất thường / gian lận dựa trên luật hoặc hỗ trợ từ ML).
    

### Required Demonstrations (Các phần demo bắt buộc)

- **Concurrent transfer test**: Kiểm thử chuyển tiền đồng thời.
    
- **Duplicate request protection**: Bảo vệ chống gửi trùng yêu cầu.
    
- **Failure / recovery scenario**: Kịch bản lỗi và khôi phục.
    
- **Security & audit review**: Đánh giá bảo mật và kiểm toán.
    
- **Load test with latency / error metrics**: Kiểm thử tải đi kèm các chỉ số độ trễ và tỷ lệ lỗi.