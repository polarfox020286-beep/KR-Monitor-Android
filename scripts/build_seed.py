#!/usr/bin/env python3
import json
import re
from pathlib import Path
import requests

URL = "https://apicr.minzdrav.gov.ru/api.ashx?op=GetJsonClinrecsFilterV2"
OUT = Path("app/src/main/assets/seed_catalog.json")
MIN_COUNT = 500
PAGE_SIZE = 2000


ICD_RE = re.compile(r"(?<![A-Z0-9])([A-Z][0-9]{2}(?:\.[0-9A-Z]{1,2})?)(?![A-Z0-9])", re.I)


def extract_mkb(value) -> str:
    if value is None:
        return ""
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    found = []
    seen = set()
    for code in ICD_RE.findall(text.upper()):
        if code not in seen:
            seen.add(code)
            found.append(code)
    return ", ".join(found)


def version(cid: str) -> int:
    try:
        return int(cid.rsplit("_", 1)[1])
    except Exception:
        return 0


def request_page(page: int):
    body = {
        "filters": [
            {
                "fieldName": "status",
                "filterType": 1,
                "filterValueType": 2,
                "value1": 0,
                "value2": "",
                "values": [],
            }
        ],
        "sortOption": {"fieldName": "publishdate", "sortType": 2},
        "pageSize": PAGE_SIZE,
        "currentPage": page,
        "useANDoperator": True,
        "columns": [],
    }
    r = requests.post(
        URL,
        json=body,
        timeout=60,
        headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android) KR-Monitor-Build/1.3",
            "Referer": "https://cr.minzdrav.gov.ru/clin-rec",
            "Origin": "https://cr.minzdrav.gov.ru",
            "Accept": "application/json, text/plain, */*",
        },
    )
    r.raise_for_status()
    obj = r.json()
    data = obj.get("Data") or obj.get("data") or []
    total = obj.get("TotalRecords", obj.get("totalRecords", len(data)))
    if not isinstance(data, list):
        raise RuntimeError("Official registry returned Data in an unexpected format")
    return data, int(total)


def main():
    records = {}
    page = 1
    loaded = 0
    total = 10**9
    while loaded < total:
        data, total = request_page(page)
        if total < MIN_COUNT:
            raise SystemExit(f"Official registry returned only {total} records")
        for row in data:
            cid = str(row.get("CodeVersion") or row.get("codeversion") or "").strip()
            if "_" not in cid:
                code = row.get("Code") or row.get("code")
                ver = row.get("Version") or row.get("version")
                if code and ver:
                    cid = f"{code}_{ver}"
            title = str(row.get("Name") or row.get("name") or "").strip()
            mkb = extract_mkb(row.get("Mkbs") or row.get("mkbs"))
            if not cid or "_" not in cid or len(title) < 3:
                continue
            base = cid.split("_", 1)[0]
            prev = records.get(base)
            if prev is None or version(cid) > version(prev["id"]):
                records[base] = {"id": cid, "title": title, "mkb": mkb}
        loaded += len(data)
        if not data or loaded >= total:
            break
        page += 1
        if page > 20:
            raise SystemExit("Too many pages returned by the official registry")

    if len(records) < MIN_COUNT:
        raise SystemExit(f"Only {len(records)} safe records parsed from official registry")

    items = sorted(records.values(), key=lambda x: x["title"].casefold())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Official Minzdrav registry: {total} rows; wrote {len(items)} current KRs to {OUT}")


if __name__ == "__main__":
    main()
