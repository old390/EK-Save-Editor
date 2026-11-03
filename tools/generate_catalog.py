#!/usr/bin/env python3
"""Build a verified, offline Exiled Kingdoms item catalog from the 2021 Steam discussion.
No user's save data is sent over the network. Only during release builds, not at runtime.
"""
import re
import sys
from pathlib import Path
from urllib.request import Request, urlopen
from html.parser import HTMLParser

URL = 'https://steamcommunity.com/app/788270/discussions/0/2515771867786715062/?l=english'
TYPES = r'armor_(?:arms|head|chest|feet|legs)|SHIELD|WEAPON|POTION|SCROLL|general|ring|belt|cloak|necklace'
ROW = re.compile(rf'^\s*(\d{{2,6}})\s+(.+?)\s+({TYPES})\s*$', re.I)

class PageText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.lines=[]
        self.buffer=[]
        self.ignored=0
    def flush(self):
        if self.buffer:
            self.lines.append(''.join(self.buffer).strip())
            self.buffer=[]
    def handle_starttag(self, tag, attrs):
        if tag in ('script','style'):
            self.ignored+=1
        if tag in ('br', 'p', 'pre', 'li'):
            self.flush()
    def handle_endtag(self, tag):
        if tag in ('script','style') and self.ignored:
            self.ignored-=1
        if tag in ('p', 'div', 'pre', 'li'):
            self.flush()
    def handle_data(self, data):
        if not self.ignored:
            for i, part in enumerate(data.replace('\r','').split('\n')):
                if i: self.flush()
                self.buffer.append(part)

request = Request(URL, headers={'User-Agent':'Mozilla/5.0 (compatible; EKSaveEditor/0.4; research use)', 'Accept-Language':'en-US,en;q=0.9'})
with urlopen(request, timeout=35) as response:
    html = response.read().decode('utf-8', 'replace')
p = PageText()
p.feed(html)
p.flush()
rows={}
for line in p.lines:
    candidate=line.replace('\xa0',' ').strip()
    match=ROW.fullmatch(candidate)
    if match:
        item_id=int(match.group(1))
        name=' '.join(match.group(2).split())
        category=match.group(3)
        rows[item_id]=(name,category)
# Fail closed: never pretend a bot challenge or partial page is the full catalog.
potions = sum(1 for _,category in rows.values() if category.upper() == 'POTION')
if len(rows) < 520 or potions < 40 or 554 not in rows or 5070 not in rows:
    print(f'Steam list incomplete: total={len(rows)}, potions={potions}, body={len(html)} bytes; examples={p.lines[:12]}',file=sys.stderr)
    sys.exit(1)
file=Path('app/src/main/assets/ek_items.tsv')
file.parent.mkdir(parents=True, exist_ok=True)
file.write_text('# Community item list: Steam 23 Sep 2021. May not cover later game versions.\n' +
    ''.join(f'{id}\t{name}\t{category}\n' for id,(name,category) in sorted(rows.items())),encoding='utf-8')
print(f'Catalog validated: {len(rows)} items, {potions} potions/consumables; saved {file}')
