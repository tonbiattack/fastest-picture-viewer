import re
import sys

# 端末から取得した uiautomator の XML を UTF-8 で読み、要素を一覧表示する補助
path = sys.argv[1]
pattern = sys.argv[2] if len(sys.argv) > 2 else None

with open(path, encoding="utf-8") as f:
    data = f.read()

for m in re.finditer(r"<node[^>]*>", data):
    tag = m.group(0)
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    bounds = re.search(r'bounds="([^"]*)"', tag)
    label = (text.group(1) if text else "") or ""
    description = (desc.group(1) if desc else "") or ""
    if not label and not description:
        continue
    line = f"[{label}] [{description}] {bounds.group(1) if bounds else ''}"
    if pattern is None or pattern in label or pattern in description:
        print(line)
