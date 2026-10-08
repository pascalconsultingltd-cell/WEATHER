"""Reads the latest water temperatures from the City of London's Hampstead Heath page and writes lido.json.

The page is a plain table, newest reading first: Date | Lido | Ladies' Pond | Men's Pond | Mixed Pond.
Dates have no year ("Monday 05 October"), so the year is inferred: the most recent one that is not in the future.
"""
import datetime
import html
import json
import re
import sys
import urllib.request

URL = ('https://www.cityoflondon.gov.uk/things-to-do/green-spaces/hampstead-heath/'
       'activities-at-hampstead-heath/swimming-at-hampstead-heath/water-temperatures')
KEYS = {'lido': 'lido', "ladies' pond": 'ladies', "men's pond": 'mens', 'mixed pond': 'mixed'}


def cells(row):
    return [html.unescape(re.sub(r'<[^>]+>', '', c)).replace('\u2019', "'").strip()
            for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.S)]


def main():
    req = urllib.request.Request(URL, headers={'User-Agent': 'Mozilla/5.0 (weather chart; daily check)'})
    page = urllib.request.urlopen(req, timeout=30).read().decode('utf-8', 'replace')
    table = re.search(r'<table.*?</table>', page, re.S).group(0)
    rows = [cells(r) for r in re.findall(r'<tr.*?</tr>', table, re.S)]
    head = [h.lower() for h in rows[0]]
    col = {KEYS[h]: i for i, h in enumerate(head) if h in KEYS}
    today = datetime.date.today()
    for row in rows[1:]:
        m = re.match(r'(\d+)\s*°?\s*C?$', row[col['lido']]) if len(row) > col['lido'] else None
        if not m:
            continue                      # "-" means no reading for the Lido that day
        day, month = re.search(r'(\d{1,2})\s+([A-Za-z]+)', row[0]).groups()
        date = datetime.datetime.strptime('%s %s %d' % (day, month, today.year), '%d %B %Y').date()
        if date > today + datetime.timedelta(days=2):
            date = date.replace(year=today.year - 1)
        out = {'date': date.isoformat(), 'source': URL}
        for key, i in col.items():
            v = re.match(r'(\d+)', row[i]) if i < len(row) else None
            out[key] = int(v.group(1)) if v else None
        with open('lido.json', 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, indent=2)
            f.write('\n')
        print(out)
        return
    sys.exit('no Lido reading found in the table')


if __name__ == '__main__':
    main()
