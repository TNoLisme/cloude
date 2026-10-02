# Backend MVP Phase 01

Java 21 Spring Boot backend foundation. Run from this directory:

```powershell
mvn test
mvn package
python -m pip install -r requirements-openapi.txt
python scripts/validate-openapi.py
```

Configure PostgreSQL through `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Flyway applies the V1 identity/customer/account/audit schema. The public health route is `/api/v1/health`.
