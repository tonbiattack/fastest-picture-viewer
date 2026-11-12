import re
import sys

# uiautomator XML から、指定した文字列を含む要素の座標を出力する
# 使い方: python -X utf8 scripts/ui_find.py captures/x.xml "タグ"
path = sys.argv[1]
query = sys.argv[2] if len(sys.argv) > 2 else None

with open(path, encoding="utf-8") as f:
    data = f.read()

for m in re.finditer(r"<node[^>]*>", data):
    tag = m.group(0)
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    bounds = re.search(r'bounds="([^"]*)"', tag)
    label = text.group(1) if text else ""
    description = desc.group(1) if desc else ""
    hit = query is None or query in label or query in description
    if not hit:
        continue
    if not label and not description:
        continue
    box = bounds.group(1) if bounds else ""
    nums = re.findall(r"\d+", box)
    center = ""
    if len(nums) == 4:
        cx = (int(nums[0]) + int(nums[2])) // 2
        cy = (int(nums[1]) + int(nums[3])) // 2
        center = f"{cx},{cy}"
    print(f"{label}\t{description}\t{box}\t{center}")
