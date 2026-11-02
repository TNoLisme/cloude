import sys
from pathlib import Path

from openapi_spec_validator import validate_spec
from yaml import safe_load


def main() -> int:
    contract = Path(__file__).resolve().parents[2] / "contracts" / "openapi.yaml"
    try:
        with contract.open(encoding="utf-8") as source:
            spec = safe_load(source)
        validate_spec(spec)
        operation_ids = []
        for path_item in spec.get("paths", {}).values():
            for method, operation in path_item.items():
                if method.lower() not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                    continue
                operation_id = operation.get("operationId")
                if not operation_id:
                    raise ValueError(f"Missing operationId for {method.upper()} operation")
                operation_ids.append(operation_id)
        duplicates = sorted({item for item in operation_ids if operation_ids.count(item) > 1})
        if duplicates:
            raise ValueError(f"Duplicate operationIds: {', '.join(duplicates)}")
    except Exception as error:
        print(f"OpenAPI validation failed: {contract}: {error}", file=sys.stderr)
        return 1

    print(f"OpenAPI validation passed: {contract} ({len(operation_ids)} operations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
