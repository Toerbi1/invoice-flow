from datetime import date
from decimal import Decimal
from pathlib import Path

from generator import render_layout_a, render_layout_b
from models import InvoiceData, write_ground_truth

OUTPUT = Path(__file__).parent / "output"

SAMPLES = [
    (
        render_layout_a,
        InvoiceData(
            "RE-2026-0815",
            "ACME GmbH",
            date(2026, 6, 30),
            "EUR",
            Decimal("1000.00"),
            Decimal("1190.00"),
        ),
    ),
    (
        render_layout_b,
        InvoiceData(
            "INV-2026-0042",
            "Globex Ltd",
            date(2026, 7, 3),
            "EUR",
            Decimal("2500.00"),
            Decimal("2975.00"),
        ),
    ),
]


def main() -> None:
    OUTPUT.mkdir(exist_ok=True)

    for i, (render, data) in enumerate(SAMPLES, start=1):
        pdf_path = OUTPUT / f"invoice_{i:04d}.pdf"
        render(data, pdf_path)
        write_ground_truth(data, pdf_path)
        print("wrote", pdf_path.name)


if __name__ == "__main__":
    main()
