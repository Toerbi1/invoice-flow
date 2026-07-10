from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas

from models import InvoiceData

PAGE_W, PAGE_H = A4


def _draw_lines(c: canvas.Canvas, lines: list[str], x: float, start_y: float, line_gap: float) -> None:
    y = start_y
    for line in lines:
        c.drawString(x, y, line)
        y -= line_gap

def render_layout_a(data: InvoiceData, pdf_path: Path) -> None:
    c = canvas.Canvas(str(pdf_path), pagesize=A4)

    x = 25 * mm
    y = PAGE_H - 30 * mm

    c.setFont("Helvetica-Bold", 18)
    c.drawString(x, y, "RECHNUNG")

    c.setFont("Helvetica", 11)
    lines = [
        f"Lieferant: {data.supplier_name}",
        f"Rechnungsnummer: {data.invoice_number}",
        f"Rechnungsdatum: {data.invoice_date.strftime('%d.%m.%Y')}",
        f"Nettobetrag: {data.total_net} {data.currency}",
        f"Gesamtbetrag: {data.total_gross} {data.currency}",
    ]

    _draw_lines(c, lines, x, y - 15 * mm, 8 * mm)

    c.showPage()
    c.save()


def render_layout_b(data: InvoiceData, pdf_path: Path) -> None:
    c = canvas.Canvas(str(pdf_path), pagesize=A4)

    x = 25 * mm
    y = PAGE_H - 30 * mm

    c.setFont("Helvetica-Bold", 18)
    c.drawString(x, y, "INVOICE")

    c.setFont("Helvetica", 11)
    lines = [
        f"Supplier: {data.supplier_name}",
        f"Invoice No. {data.invoice_number}",
        f"Date: {data.invoice_date.isoformat()}",
        f"Subtotal: {data.total_net:,.2f} {data.currency}",
        f"Total: {data.total_gross:,.2f} {data.currency}",
    ]

    _draw_lines(c, lines, x, y - 15 * mm, 8 * mm)

    c.showPage()
    c.save()
