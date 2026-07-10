from dataclasses import dataclass
from datetime import date
from decimal import Decimal
import json
from pathlib import Path

@dataclass(frozen=True)
class InvoiceData:
    invoice_number: str
    supplier_name: str
    invoice_date: date
    currency: str
    total_net: Decimal
    total_gross: Decimal

def write_ground_truth(data: InvoiceData, pdf_path: Path) -> Path:
    """Schreibt die erwarteten Werte neben das PDF als <name>.expected.json."""
    gt_path = pdf_path.with_suffix(".expected.json")
    payload = {
        "invoice_number": data.invoice_number,
        "supplier_name": data.supplier_name,
        "invoice_date": data.invoice_date.isoformat(),
        "currency": data.currency,
        "total_net": str(data.total_net),
        "total_gross": str(data.total_gross),
    }
    gt_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    return gt_path
