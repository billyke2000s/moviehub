"""Resume missing/incomplete checks without treating saved failures as playback passes."""
import json
import os
from pathlib import Path
import re
import sys


def latest_reports(root, sites):
    expected = set(sites)
    latest = {}
    runs = sorted((path for path in root.iterdir() if path.is_dir() and path.name.isdigit()),
                  key=lambda path: int(path.name)) if root.is_dir() else []
    for run in runs:
        for path in sorted(run.glob('*.json')):
            report = json.loads(path.read_text())
            site = report.get('site')
            if site not in expected:
                raise ValueError('Receipt does not belong to the enabled inventory')
            if path.name != site.replace(':', '-') + '.json':
                raise ValueError('Receipt path and website identity disagree')
            if not re.fullmatch(r'[0-9a-f]{40}', report.get('sourceCommit', '')):
                raise ValueError('Receipt lacks the actual tested source commit')
            if not report.get('evidenceRun', '').endswith('/actions/runs/' + run.name):
                raise ValueError('Receipt run identity disagrees with its directory')
            latest[site] = report
    return latest


def complete(report):
    media = report.get('media', {})
    return all(isinstance(media.get(kind), dict) and media[kind].get('complete') is True
               for kind in ('movies', 'series'))


def pending_sites(sites, reports, fresh=False):
    def current(report):
        if not complete(report):
            return False
        has_playback = any(media.get('candidatesTested', 0) > 0 for media in report['media'].values())
        return not has_playback or report.get('playbackAuditVersion') == 2
    return [site for site in sites if fresh or not current(reports.get(site, {}))]


def verify(sites, reports):
    if set(reports) != set(sites) or pending_sites(sites, reports):
        raise ValueError('Incomplete saved site coverage; unfinished checks must be resumed')
    if not all(reports[site].get('fullPlaybackVerified') is True for site in sites):
        raise ValueError('Checks saved; playback repairs/content review remain incomplete')


if __name__ == '__main__':
    root = Path('docs/site-audits/runs')
    if sys.argv[1:] == ['review']:
        sites = json.loads(os.environ['EXPECTED_SITES'])
        verify(sites, latest_reports(root, sites))
    else:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
        import moviehub_vod as vod
        sites = [row['id'] for row in json.loads(vod.search_providers())]
        if len(sites) != 74 or len(set(sites)) != 74:
            raise ValueError('Expected all 74 distinct enabled websites')
        if not all(re.fullmatch(r'(free99|scrubs|native):[a-z0-9_]+', site) for site in sites):
            raise ValueError('Invalid website ID')
        reports = latest_reports(root, sites)
        pending = pending_sites(sites, reports, os.environ.get('AUDIT_FRESH') == 'true')
        print('sites=' + json.dumps(sites))
        print('pending=' + json.dumps(pending))
        print('has_work=' + str(bool(pending)).lower())
        print('%d saved complete checks; %d checks queued; playback certification remains separate' %
              (sum(complete(row) for row in reports.values()), len(pending)), file=sys.stderr)
