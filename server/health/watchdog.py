#!/usr/bin/env python3
"""Watches the watcher: pings your phone when the MovieHub server itself needs you.

Run every 30 minutes by the moviehub-watchdog systemd timer (installed by server/bootstrap/container.sh).
It never fixes anything; it only tells you, in plain words, with the command that fixes it.

Problems it reports
  - the health check has not finished for HEALTH_MAX_HOURS (default 3): runner or Forgejo stuck
  - forgejo, forgejo-runner or moviehub-gate is not running
  - the disk is more than 90 % full
  - the last auto-fix attempt said Codex needs logging in again (auto_fix.py leaves a marker file)
Each problem is sent once, repeated every 12 hours while it lasts, and followed by "fixed" when it
clears. Every Monday morning it sends one short "all good" (or a list of what is not).

Settings (environment, from /etc/moviehub-watchdog.env): NTFY_URL, STATE_FILE
(default /srv/moviehub/health/state.json), WATCHDOG_FILE (default /srv/moviehub/health/watchdog.json),
HEALTH_MAX_HOURS (default 3).
"""
import datetime
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

SERVICES = ('forgejo', 'forgejo-runner', 'moviehub-gate')
REPEAT_SECONDS = 12 * 3600
CODEX_MARKER = Path('/srv/moviehub/health/codex-login-needed')

FIXES = {
    'health': 'Health check has not run for {hours} hours. On the server: systemctl restart forgejo forgejo-runner',
    'service': '{name} is not running. On the server: systemctl restart {name}',
    'disk': 'Disk is {percent}% full. Old evidence can go: rm -rf /srv/moviehub/health/evidence/*',
    'codex': 'Codex needs logging in again. On the server: runuser -u moviehub -- codex login',
}


def problems(now, state_file, max_hours, service_active, disk_percent, codex_marker_exists):
    """Current problems as {key: message}. Pure, so it can be tested."""
    found = {}
    try:
        age = now - os.path.getmtime(state_file)
    except OSError:
        age = None
    if age is None or age > max_hours * 3600:
        hours = 'many' if age is None else int(age // 3600)
        found['health'] = FIXES['health'].format(hours=hours)
    for name in SERVICES:
        if not service_active(name):
            found['service:' + name] = FIXES['service'].format(name=name)
    if disk_percent > 90:
        found['disk'] = FIXES['disk'].format(percent=disk_percent)
    if codex_marker_exists:
        found['codex'] = FIXES['codex']
    return found


def decide(memory, found, now, weekly_due):
    """Returns (messages to send, new memory). memory: {key: last_sent_time}."""
    send = []
    new = {}
    for key, message in found.items():
        last = memory.get(key)
        if last is None or now - last >= REPEAT_SECONDS:
            send.append(('MovieHub server needs you', message))
            new[key] = now
        else:
            new[key] = last
    for key in memory:
        if key not in found and not key.startswith('_'):
            send.append(('MovieHub server: fixed', key.replace('service:', '') + ' is OK again.'))
    if weekly_due:
        if found:
            send.append(('MovieHub weekly check', '%d problem(s): %s' % (len(found), '; '.join(found.values()))))
        else:
            send.append(('MovieHub weekly check', 'All good: checks running, services up, disk fine.'))
        new['_weekly'] = now
    elif '_weekly' in memory:
        new['_weekly'] = memory['_weekly']
    return send, new


def weekly_due(now, memory):
    """Monday 09:00-09:59 local time, at most once per 6 days."""
    local = datetime.datetime.fromtimestamp(now)
    return local.weekday() == 0 and local.hour == 9 and now - memory.get('_weekly', 0) > 6 * 86400


def notify(url, title, message):
    if not url:
        print('%s: %s' % (title, message))
        return
    request = urllib.request.Request(url, data=message.encode(), method='POST', headers={'Title': title})
    try:
        urllib.request.urlopen(request, timeout=15).close()
    except Exception as error:
        sys.stderr.write('Notification failed: %s\n' % error)


def main():
    env = os.environ
    memory_file = Path(env.get('WATCHDOG_FILE', '/srv/moviehub/health/watchdog.json'))
    memory = json.loads(memory_file.read_text()) if memory_file.is_file() else {}
    now = time.time()
    usage = shutil.disk_usage('/')
    found = problems(
        now,
        env.get('STATE_FILE', '/srv/moviehub/health/state.json'),
        float(env.get('HEALTH_MAX_HOURS', '3')),
        lambda name: subprocess.run(['systemctl', 'is-active', '--quiet', name]).returncode == 0,
        int(usage.used * 100 / usage.total),
        CODEX_MARKER.exists(),
    )
    send, new = decide(memory, found, now, weekly_due(now, memory))
    for title, message in send:
        notify(env.get('NTFY_URL', ''), title, message)
    memory_file.parent.mkdir(parents=True, exist_ok=True)
    memory_file.write_text(json.dumps(new))
    print(json.dumps({'problems': list(found), 'sent': len(send)}))


if __name__ == '__main__':
    main()
