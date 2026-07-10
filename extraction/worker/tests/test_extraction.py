import json
import sys
from decimal import Decimal
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extraction import extract_from_pdf, normalize_amount, parse_date

REPO_ROOT = Path(__file__).resolve().parents[3]
GEN_OUTPUT = REPO_ROOT / "tools" / "invoice-generator" / "output"
PDFS = sorted(GEN_OUTPUT.glob("*.pdf")) if GEN_OUTPUT.exists() else []


@pytest.mark.parametrize("raw,expected", [
    ("1000.00", Decimal("1000.00")),
    ("1.190,00", Decimal("1190.00")),
    ("1,190.00", Decimal("1190.00")),
    ("1190,00", Decimal("1190.00")),
    ("1190", Decimal("1190")),
    ("Gesamtbetrag: 2.975,00 EUR", Decimal("2975.00")),
    ("Total: 2,500.00 EUR", Decimal("2500.00")),
])
def test_normalize_amount(raw, expected):
    assert normalize_amount(raw) == expected


@pytest.mark.parametrize("raw,iso", [
    ("30.06.2026", "2026-06-30"),
    ("2026-07-03", "2026-07-03"),
    ("Rechnungsdatum: 03.07.2026", "2026-07-03"),
])
def test_parse_date(raw, iso):
    assert parse_date(raw).isoformat() == iso


@pytest.mark.skipif(not PDFS, reason="run tools/invoice-generator first")
@pytest.mark.parametrize("pdf", PDFS, ids=lambda p: p.name)
def test_extraction_matches_ground_truth(pdf):
    expected = json.loads(pdf.with_suffix(".expected.json").read_text(encoding="utf-8"))
    got = extract_from_pdf(pdf).to_payload()

    assert got["supplierName"]["value"] == expected["supplier_name"]
    assert got["invoiceNumber"]["value"] == expected["invoice_number"]
    assert got["invoiceDate"]["value"] == expected["invoice_date"]
    assert got["currency"]["value"] == expected["currency"]
    assert Decimal(got["totalNet"]["value"]) == Decimal(expected["total_net"])
    assert Decimal(got["totalGross"]["value"]) == Decimal(expected["total_gross"])
    assert got["invoiceNumber"]["confidence"] == 1.0
