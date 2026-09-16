#!/usr/bin/env python3
import json
import re
from pathlib import Path
import requests
from bs4 import BeautifulSoup

URLS = [
    "https://www.endoexpert.ru/dokumenty-i-prikazy/rubrikatorkr/",
    "https://www.endoexpert.ru/dokumenty-i-prikazy/rubrikatorkr/?itape=1053",
]
OUT = Path("app/src/main/assets/seed_catalog.json")
MIN_COUNT = 500


def clean(s: str) -> str:
    return re.sub(r"\s+", " ", s or "").strip(" \t\r\n-–—:;,. ")


def version(cid: str) -> int:
    try:
        return int(cid.rsplit("_", 1)[1])
    except Exception:
        return 0


def extract_title(element, id_match):
    candidates = []
    for a in element.find_all("a"):
        t = clean(a.get_text(" ", strip=True))
        low = t.lower()
        if len(t) < 3:
            continue
        if low in {"скачать", "подробнее", "открыть", "источник"}:
            continue
        if "клинические рекомендации минздрава" in low:
            continue
        candidates.append(t)
    if candidates:
        return max(candidates, key=len)
    text = clean(element.get_text(" ", strip=True))
    prefix = clean(text[: id_match.start()])
    return re.sub(r"^[•·*\-\d.)\s]+", "", prefix)


def fetch_catalog(url: str):
    r = requests.get(
        url,
        timeout=60,
        headers={
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) KR-Monitor-Build/1.0",
            "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.7",
        },
    )
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    records = {}
    id_re = re.compile(r"\bID\s*[:№]?\s*(\d+_\d+)\b", re.I)
    date_re = re.compile(r"размещено\s+\d{2}\.\d{2}\.\d{4}", re.I)
    for element in soup.find_all(["li", "tr"]):
        text = clean(element.get_text(" ", strip=True))
        if not text or len(text) > 2500:
            continue
        m = id_re.search(text)
        if not m or not date_re.search(text):
            continue
        cid = m.group(1)
        title = extract_title(element, m)
        if len(title) < 3 or title.lower().startswith("id "):
            continue
        base = cid.split("_", 1)[0]
        prev = records.get(base)
        if prev is None or version(cid) > version(prev["id"]):
            records[base] = {"id": cid, "title": title}
    return records


def main():
    last_error = None
    for url in URLS:
        try:
            records = fetch_catalog(url)
            print(f"{url}: parsed {len(records)} records")
            if len(records) >= MIN_COUNT:
                items = sorted(records.values(), key=lambda x: x["title"].casefold())
                OUT.parent.mkdir(parents=True, exist_ok=True)
                OUT.write_text(json.dumps(items, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
                print(f"Wrote {len(items)} records to {OUT}")
                return
            last_error = RuntimeError(f"only {len(records)} records parsed")
        except Exception as exc:
            print(f"Source failed: {url}: {exc}")
            last_error = exc
    raise SystemExit(f"Could not build safe seed catalog: {last_error}")


if __name__ == "__main__":
    main()
