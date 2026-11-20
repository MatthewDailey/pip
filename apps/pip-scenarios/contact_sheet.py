"""Lays the scenario screenshots from the last run (.proof/scenarios/summary.json) out as one labelled contact sheet,
.proof/scenarios/contact-sheet.png: one phone per scenario, captioned with its id, what the mock expects and what Pip
did. Run it through `turbo run scenarios:sheet`.
"""

import json
import sys
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
PROOF = ROOT / ".proof/scenarios"
COLS, THUMB_W = 6, 300
CAPTION_H, PAD = 150, 24
COLORS = {"quiet": "#2F7A55", "generic": "#9A6408", "warns": "#9A6408"}


def font(size: int, bold: bool = False):
    for name in (["Helvetica.ttc", "Arial Bold.ttf"] if bold else ["Helvetica.ttc", "Arial.ttf"]):
        for base in ("/System/Library/Fonts", "/System/Library/Fonts/Supplemental", "/Library/Fonts"):
            p = Path(base) / name
            if p.exists():
                return ImageFont.truetype(str(p), size, index=1 if bold and name.endswith(".ttc") else 0)
    return ImageFont.load_default()


def main() -> None:
    summary = json.loads((PROOF / "summary.json").read_text())
    results = [r for r in summary["results"] if r.get("screenshot")]
    if not results:
        sys.exit("No screenshots in the last run; run turbo run scenarios first.")
    first = Image.open(ROOT / results[0]["screenshot"])
    thumb_h = round(first.height * THUMB_W / first.width)
    rows = -(-len(results) // COLS)
    sheet = Image.new("RGB", (PAD + COLS * (THUMB_W + PAD), 90 + rows * (thumb_h + CAPTION_H + PAD)), "#FBF6F2")
    d = ImageDraw.Draw(sheet)
    d.text((PAD, 24), f"Pip scenarios: {len(results)} moments from the re-skin mocks, live on the emulator", fill="#2A1714", font=font(30, True))
    small, tiny, bold = font(17), font(15), font(18, True)
    for i, r in enumerate(results):
        x = PAD + (i % COLS) * (THUMB_W + PAD)
        y = 90 + (i // COLS) * (thumb_h + CAPTION_H + PAD)
        shot = Image.open(ROOT / r["screenshot"]).convert("RGB").resize((THUMB_W, thumb_h))
        sheet.paste(shot, (x, y))
        d.rectangle((x, y, x + THUMB_W - 1, y + thumb_h - 1), outline="#E9DCD4", width=2)
        cy = y + thumb_h + 8
        d.text((x, cy), r["id"], fill="#2A1714", font=bold)
        d.text((x, cy + 24), f"{r['fidelity']} · expects {r['expect']['level']}", fill="#6E5550", font=tiny)
        color = next((c for k, c in COLORS.items() if r["verdict"].startswith(k)), "#CF3328")
        lines = [(t, color) for t in textwrap.wrap(f"Pip today: {r['verdict']}", 34)[:2]]
        lines += [(t, "#6E5550") for t in textwrap.wrap(f"Mock: {r['expect']['pip']}", 36)[:2]]
        for j, (line, fill) in enumerate(lines):
            d.text((x, cy + 46 + j * 21), line, fill=fill, font=small)
    out = PROOF / "contact-sheet.png"
    sheet.save(out, optimize=True)
    print(f"{out.relative_to(ROOT)} ({sheet.width}x{sheet.height}, {len(results)} scenarios)")


if __name__ == "__main__":
    main()
