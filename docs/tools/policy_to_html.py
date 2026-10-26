"""Builds docs/privacy-policy.html from docs/privacy-policy.md.

Usage: python -I docs/tools/policy_to_html.py
Handles the small Markdown subset the policy uses: headings, paragraphs,
bullet/numbered lists, tables, bold, inline code, links and rules.
Blockquotes (the draft notes) are dropped from the HTML.
"""
import html
import re
from pathlib import Path

DOCS = Path(__file__).resolve().parent.parent
SRC = DOCS / "privacy-policy.md"
OUT = DOCS / "privacy-policy.html"


def inline(text: str) -> str:
    text = html.escape(text, quote=False)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", r'<a href="\2">\1</a>', text)
    return text


def convert(md: str) -> str:
    out, para, lines = [], [], md.splitlines()
    i = 0

    def flush():
        if para:
            out.append("<p>" + inline(" ".join(para)) + "</p>")
            para.clear()

    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            flush(); i += 1; continue
        if line.startswith(">"):
            flush(); i += 1; continue
        if line.strip() == "---":
            flush(); out.append("<hr>"); i += 1; continue
        m = re.match(r"(#{1,4}) (.*)", line)
        if m:
            flush()
            level = len(m.group(1))
            title = m.group(2)
            attrs = ""
            if level == 2 and re.search(r"[ऀ-ॿ]", title):
                lang = "mr" if "धोरण" in title else "hi"
                attrs = f' id="{lang}" lang="{lang}"'
            out.append(f"<h{level}{attrs}>{inline(title)}</h{level}>")
            i += 1; continue
        if line.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            head, body = rows[0], [r for r in rows[1:] if not set("".join(r)) <= set("-: ")]
            out.append("<div class=\"table\"><table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>")
            for r in body:
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
            out.append("</tbody></table></div>")
            continue
        m = re.match(r"(\s*)(- |\d+\. )(.*)", line)
        if m:
            flush()
            tag = "ol" if m.group(2)[0].isdigit() else "ul"
            out.append(f"<{tag}>")
            while i < len(lines):
                m = re.match(r"\s*(- |\d+\. )(.*)", lines[i])
                if not m:
                    break
                out.append("<li>" + inline(m.group(2)) + "</li>")
                i += 1
            out.append(f"</{tag}>")
            continue
        para.append(line.strip()); i += 1
    flush()
    return "\n".join(out)


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NoDialer Privacy Policy</title>
<meta name="description" content="Privacy policy for the NoDialer phone app for Android.">
<style>
:root {{ --bg:#fafafa; --fg:#151515; --muted:#5c5c5c; --line:#dddddd; --accent:#c8161d; --card:#ffffff; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#0d0d0d; --fg:#ececec; --muted:#a3a3a3; --line:#2c2c2c; --accent:#ff4a50; --card:#161616; }} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans","Noto Sans Devanagari",sans-serif; }}
main {{ max-width:760px; margin:0 auto; padding:32px 16px 64px; }}
nav {{ display:flex; gap:12px; font-size:14px; margin-bottom:24px; }}
a {{ color:var(--accent); }}
h1 {{ font-size:28px; line-height:1.2; margin:0 0 16px; letter-spacing:-0.01em; }}
h2 {{ font-size:20px; margin:36px 0 8px; }}
h3 {{ font-size:17px; margin:24px 0 6px; }}
code {{ font:13px ui-monospace,Consolas,monospace; background:var(--card); border:1px solid var(--line); border-radius:4px; padding:1px 4px; }}
hr {{ border:0; border-top:1px solid var(--line); margin:48px 0; }}
.table {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; font-size:14px; background:var(--card); }}
th, td {{ border:1px solid var(--line); padding:8px 10px; text-align:left; vertical-align:top; }}
th {{ font-weight:600; }}
li {{ margin:4px 0; }}
</style>
</head>
<body>
<main>
<nav><a href="#top">English</a><a href="#hi">हिन्दी</a><a href="#mr">मराठी</a></nav>
<div id="top"></div>
{body}
</main>
</body>
</html>
"""

if __name__ == "__main__":
    OUT.write_text(TEMPLATE.format(body=convert(SRC.read_text(encoding="utf-8"))), encoding="utf-8")
    print(f"wrote {OUT}")
