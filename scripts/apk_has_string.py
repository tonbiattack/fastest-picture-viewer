import zipfile
import sys

# APK 内の classes*.dex に、指定した文字列が含まれるか確認する補助
# 使い方: python -X utf8 scripts/apk_has_string.py <apk> "検索語"
apk = sys.argv[1]
needle = sys.argv[2].encode("utf-8")

with zipfile.ZipFile(apk) as z:
    names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
    found = False
    for name in names:
        data = z.read(name)
        if needle in data:
            print(f"found in {name}")
            found = True
    if not found:
        print("not found")
