import re
import sys

# uiautomator XML から checkable/checked の状態を持つ要素を一覧表示する
# 使い方: python -X utf8 scripts/ui_switches.py captures/d5.xml
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    data = f.read()

for m in re.finditer(r"<node[^>]*>", data):
    tag = m.group(0)

    def attr(name):
        found = re.search(rf'{name}="([^"]*)"', tag)
        return found.group(1) if found else ""

    if attr("checkable") == "true":
        print(
            "checkable=true",
            "checked=" + attr("checked"),
            "class=" + attr("class"),
            "text=" + attr("text"),
            "desc=" + attr("content-desc"),
            "bounds=" + attr("bounds"),
        )
