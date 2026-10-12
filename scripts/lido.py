"""Reads the latest water temperatures from the City of London's Hampstead Heath page and writes lido.json,
with the Lido's open/closed status, today's opening hours and the sauna sessions from the neighbouring pages.

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
HOME = URL.rsplit('/', 1)[0]            # "Parliament Hill Lido - we are open as usual"
TIMES = HOME + '/swimming-opening-times'  # one table of sessions per season, and the sauna sessions
RATE, OFFSET = 0.17, 0.1
KEYS = {'lido': 'lido', "ladies' pond": 'ladies', "men's pond": 'mens', 'mixed pond': 'mixed'}


def cells(row):
    return [html.unescape(re.sub(r'<[^>]+>', '', c)).replace('\u2019', "'").strip()
            for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.S)]


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (weather chart; daily check)'})
    return urllib.request.urlopen(req, timeout=30).read().decode('utf-8', 'replace')


def facilities(today):
    """Returns {'status', 'hours': {'season', 'until', 'sessions': [{'name', 'open', 'last', 'out'}]}, 'sauna'} for today."""
    out = {}
    m = re.search(r'<h2[^>]*>\s*Parliament Hill Lido\s*-(.*?)</h2>', fetch(HOME), re.S)
    if m:
        out['status'] = ' '.join(html.unescape(re.sub(r'<[^>]+>', '', m.group(1))).split())
    page, sauna, season = fetch(TIMES), {}, None
    for t in re.finditer(r'<table.*?</table>', page, re.S):
        caption = ' '.join(html.unescape(re.sub(r'<[^>]+>', '', re.search(r'<caption[^>]*>(.*?)</caption>', t.group(0), re.S).group(1))).split())
        rows = [[' '.join(c.split()) for c in cells(r)] for r in re.findall(r'<tr.*?</tr>', t.group(0), re.S)]
        span = re.match(r'Lido opening times from (\d+ \w+ \d{4}) to (?:\w+day )?(\d+ \w+ \d{4})', caption)
        if caption.lower().startswith('sauna'):
            sauna = {r[0].lower(): r[1] for r in rows[1:]}
        if not span:
            continue
        first, last = [datetime.datetime.strptime(d, '%d %B %Y').date() for d in span.groups()]
        if not first <= today <= last:
            continue
        head, sessions = [h.lower() for h in rows[0]], []
        for r in rows[1:]:
            row = dict(zip(head, r))
            name = row.get('session', '').lstrip('* ')
            until = re.search(r'\(until (\d+ \w+)\)', name)       # a session that stops before the season does
            name = re.sub(r'\s*\(until.*?\)', '', name)
            if until and datetime.datetime.strptime('%s %d' % (until.group(1), last.year), '%d %B %Y').date() < today:
                continue
            if name not in [x['name'] for x in sessions]:     # the same session listed again is its later, shorter run
                sessions.append({'name': name, 'open': row.get('open'), 'last': row.get('last entry'), 'out': row.get('vacate water')})
        season = re.findall(r'<h3[^>]*>\s*(\w+)', page[:t.start()])[-1]
        out['hours'] = {'season': season, 'until': last.isoformat(), 'sessions': sessions}
    # the page gives the sauna by season without dates ("mid-October to May"), so the season is worked out here
    if season and season.lower() != 'summer' and today.month in (9, 10) and (today.month, today.day) < (10, 15):
        out['sauna'] = 'opens mid-October'
    elif season:
        key = 'summer' if season.lower() == 'summer' else 'early spring' if today.month in (3, 4) else 'autumn'
        out['sauna'] = next((v for k, v in sauna.items() if k.startswith(key)), None)
    return out


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
    page = fetch(URL)
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
        try:
            out.update(facilities(today))
        except Exception as e:
            print('no opening hours:', e)
        with open('lido.json', 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, indent=2)
            f.write('\n')
        print(out)
        return
    sys.exit('no Lido reading found in the table')


if __name__ == '__main__':
    main()
