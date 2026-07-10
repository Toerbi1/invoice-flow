from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Optional

import pdfplumber


@dataclass
class Field:
    value: str
    confidence: float


@dataclass
class ExtractionResult:
    supplier_name: Optional[Field] = None
    invoice_number: Optional[Field] = None
    invoice_date: Optional[Field] = None
    currency: Optional[Field] = None
    total_net: Optional[Field] = None
    total_gross: Optional[Field] = None

    def to_payload(self) -> dict:
        def f(x: Optional[Field]):
            return {"value": x.value, "confidence": x.confidence} if x else None

        return {
            "supplierName": f(self.supplier_name),
            "invoiceNumber": f(self.invoice_number),
            "invoiceDate": f(self.invoice_date),
            "currency": f(self.currency),
            "totalNet": f(self.total_net),
            "totalGross": f(self.total_gross),
        }


SUPPLIER_KW = ["Lieferant", "Supplier", "Verkäufer", "Vendor"]
INVOICE_NO_KW = [
    "Rechnungsnummer",
    "Rechnungs-Nr",
    "Rechnungsnr",
    "Invoice Number",
    "Invoice No",
    "Invoice #",
]
DATE_KW = ["Rechnungsdatum", "Invoice Date", "Datum", "Date"]
NET_KW = ["Nettobetrag", "Subtotal", "Zwischensumme", "Net Amount", "Netto", "Net"]
GROSS_KW = [
    "Gesamtbetrag",
    "Bruttobetrag",
    "Rechnungsbetrag",
    "Total",
    "Gesamt",
    "Brutto",
]


def normalize_amount(raw: str) -> Optional[Decimal]:
    match = re.search(r"\d[\d.,]*", raw)

    if not match:
        return None

    number = match.group(0)

    has_dot = "." in number
    has_comma = "," in number

    if has_dot and has_comma:
        last_dot = number.rfind(".")
        last_comma = number.rfind(",")

        if last_dot > last_comma:
            number = number.replace(",", "")
        else:
            number = number.replace(".", "")
            number = number.replace(",", ".")
    elif has_comma:
        comma_index = number.rfind(",")
        digits_after_comma = len(number) - comma_index - 1

        if digits_after_comma == 2:
            number = number.replace(",", ".")
        else:
            number = number.replace(",", "")

    try:
        return Decimal(number)
    except InvalidOperation:
        return None


def parse_date(raw: str) -> Optional[date]:
    patterns = [
        (r"\b\d{2}\.\d{2}\.\d{4}\b", "%d.%m.%Y"),
        (r"\b\d{4}-\d{2}-\d{2}\b", "%Y-%m-%d"),
        (r"\b\d{2}/\d{2}/\d{4}\b", "%d/%m/%Y"),
    ]

    for pattern, fmt in patterns:
        match = re.search(pattern, raw)
        if not match:
            continue

        token = match.group(0)

        try:
            return datetime.strptime(token, fmt).date()
        except ValueError:
            continue

    return None


def _labeled(lines: list[str], keywords: list[str]) -> Optional[str]:
    for line in lines:
        for kw in keywords:
            match = re.search(r"\b" + re.escape(kw) + r"\b", line, re.IGNORECASE)

            if not match:
                continue

            value = line[match.end():]
            value = value.lstrip(" :.-")
            return value.strip() or None

    return None


def _extract_supplier(lines: list[str]) -> Optional[Field]:
    value = _labeled(lines, SUPPLIER_KW)

    if not value:
        return None

    return Field(value=value, confidence=1.0)


def _extract_invoice_number(lines: list[str], text: str) -> Optional[Field]:
    labeled_value = _labeled(lines, INVOICE_NO_KW)

    if labeled_value:
        match = re.search(r"\S+", labeled_value)
        if match:
            return Field(value=match.group(0), confidence=1.0)

    fallback = re.search(r"\b(?:RE|INV|IN|R)[-/]?\d{2,4}[-/]\d{2,5}\b", text)

    if fallback:
        return Field(value=fallback.group(0), confidence=0.6)

    return None


def _extract_date(lines: list[str], text: str) -> Optional[Field]:
    labeled_value = _labeled(lines, DATE_KW)

    if labeled_value:
        parsed = parse_date(labeled_value)
        if parsed:
            return Field(value=parsed.isoformat(), confidence=1.0)

    parsed = parse_date(text)

    if parsed:
        return Field(value=parsed.isoformat(), confidence=0.6)

    return None


def _extract_amount(lines: list[str], keywords: list[str]) -> Optional[Field]:
    labeled_value = _labeled(lines, keywords)

    if not labeled_value:
        return None

    amount = normalize_amount(labeled_value)

    if amount is None:
        return None

    return Field(value=str(amount), confidence=1.0)


def _extract_currency(text: str) -> Optional[Field]:
    code_match = re.search(r"\b(EUR|USD|GBP|CHF)\b", text, re.IGNORECASE)

    if code_match:
        return Field(value=code_match.group(1).upper(), confidence=1.0)

    symbol_map = {
        "€": "EUR",
        "$": "USD",
        "£": "GBP",
    }

    for symbol, code in symbol_map.items():
        if symbol in text:
            return Field(value=code, confidence=0.6)

    return None


def extract_fields(text: str) -> ExtractionResult:
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    return ExtractionResult(
        supplier_name=_extract_supplier(lines),
        invoice_number=_extract_invoice_number(lines, text),
        invoice_date=_extract_date(lines, text),
        currency=_extract_currency(text),
        total_net=_extract_amount(lines, NET_KW),
        total_gross=_extract_amount(lines, GROSS_KW),
    )


def extract_text_from_pdf(path) -> str:
    pdf_path = Path(path)

    with pdfplumber.open(pdf_path) as pdf:
        return "\n".join(page.extract_text() or "" for page in pdf.pages)


def extract_from_pdf(path) -> ExtractionResult:
    return extract_fields(extract_text_from_pdf(path))


if __name__ == "__main__":
    print(
        json.dumps(
            extract_from_pdf(sys.argv[1]).to_payload(),
            indent=2,
            ensure_ascii=False,
        )
    )
