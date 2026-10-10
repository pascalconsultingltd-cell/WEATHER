"""Reads the latest water temperatures from the City of London's Hampstead Heath page and writes lido.json.

The page is a plain table, newest reading first: Date | Lido | Ladies' Pond | Men's Pond | Mixed Pond.
Dates have no year ("Monday 05 October"), so the year is inferred: the most recent one that is not in the future.

The Lido is only published about weekly, so an estimate for today is added: starting from the last reading,
each later day moves the water RATE of the way towards that day's average daytime air temperature (+ OFFSET).
RATE and OFFSET were fitted on the readings since April 2024 (about 0.7 C average error against the next reading).

Readings seen in person (the board at the Lido is updated daily) can be added to scripts/lido_manual.json as
{"date": "YYYY-MM-DD", "lido": 14}; the newest reading, published or manual, is the one the estimate starts from.
"""
import datetime
import html
import json
import os
import re
import sys
import urllib.request

URL = ('https://www.cityoflondon.gov.uk/things-to-do/green-spaces/hampstead-heath/'
       'activities-at-hampstead-heath/swimming-at-hampstead-heath/water-temperatures')
FORECAST = ('https://api.open-meteo.com/v1/forecast?latitude=51.553&longitude=-0.142&hourly=temperature_2m,is_day'
            '&current=temperature_2m&past_days=92&forecast_days=1&timezone=Europe%2FLondon')
RATE, OFFSET = 0.17, 0.1
KEYS = {'lido': 'lido', "ladies' pond": 'ladies', "men's pond": 'mens', 'mixed pond': 'mixed'}


def cells(row):
    return [html.unescape(re.sub(r'<[^>]+>', '', c)).replace('\u2019', "'").strip()
            for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.S)]


def estimate(reading, reading_date):
    """Returns (estimate for today, today's date in London), walking forward from the last reading."""
    j = json.load(urllib.request.urlopen(FORECAST, timeout=30))
    today = j['current']['time'][:10]
    days = {}
    for t, temp, is_day in zip(j['hourly']['time'], j['hourly']['temperature_2m'], j['hourly']['is_day']):
        if temp is not None and is_day:
            days.setdefault(t[:10], []).append(temp)       # today's later hours are forecast values
    water = float(reading)
    for day in sorted(days):
        if reading_date < day <= today:
            water += RATE * (sum(days[day]) / len(days[day]) + OFFSET - water)
    return round(water, 1), today


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
        manual = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lido_manual.json')
        if os.path.exists(manual):
            with open(manual, encoding='utf-8') as f:
                seen = max(json.load(f) or [{'date': ''}], key=lambda r: r['date'])
            if seen['date'] >= out['date'] and seen.get('lido') is not None:
                out['date'], out['lido'], out['manual'] = seen['date'], seen['lido'], True
        try:
            out['estimate'], out['estimate_date'] = estimate(out['lido'], out['date'])
        except Exception as e:                # the reading is still worth saving without it
            print('no estimate:', e)
        with open('lido.json', 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, indent=2)
            f.write('\n')
        print(out)
        return
    sys.exit('no Lido reading found in the table')


if __name__ == '__main__':
    main()
