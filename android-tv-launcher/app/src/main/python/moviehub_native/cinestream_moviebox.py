"""Anonymous MovieBox protocols from CineStream 487, CSX@13903aa (GPL-3.0-or-later)."""
import json
import re
from urllib.parse import urlencode
from moviehub_compat import client
from .cinestream_api import Base, get, post_json, item, DESKTOP_138, request, http_session, USER_AGENT


def unwrap(data):
    data = data.get('data', data)
    return data.get('data', data)


class MovieBox(Base):
    base_link = 'https://h5-api.aoneroom.com'

    def extract(self, q):
        url = self.base_link + '/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox'
        response = client.fetch(url, {'User-Agent': USER_AGENT}, session=http_session())
        token = json.loads(response.headers.get('x-user', '{}')).get('token')
        if not isinstance(token, str) or not token:
            return
        headers = dict(self.headers(), **{'User-Agent': DESKTOP_138, 'Referer': self.base_link,
                                         'Accept-Language': 'en-US,en;q=0.5', 'Authorization': 'Bearer ' + token, 'Accept': 'application/json',
                                         'X-Client-Info': '{"timezone":"Africa/Nairobi"}'})
        data = post_json(self.base_link + '/wefeed-h5api-bff/subject/search', dict(
            keyword=q['title'], page=1, perPage=24, subjectType=2 if 'season' in q else 1), headers)
        rows = unwrap(data).get('items') or []
        pattern = re.compile(r'^' + re.escape(q['title']) + r'(?:\s+\[([^\]]+)\])?$', re.I)
        for row in rows[:24]:
            title = re.sub(r'\sS\d+(?:-S?\d+)*$', '', row.get('title', ''), flags=re.I)
            match = pattern.fullmatch(title)
            if not match or not row.get('subjectId'):
                continue
            # An explicit conflicting release year must not select a remake.
            if row.get('releaseDate') and str(row['releaseDate'])[:4] != q['year']:
                continue
            identity = str(row['subjectId'])
            detail = get('https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?' + urlencode(dict(id=identity)))
            entries = detail.get('data', {}).get('items') or []
            if not entries:
                continue
            path = entries[0].get('subject', {}).get('detailPath')
            if not path:
                continue
            args = dict(subjectId=identity, detailPath=path)
            if 'season' in q:
                args.update(se=q['season'], ep=q['episode'])
            origin = 'https://fmoviesunblocked.net'
            req = dict(headers, Referer=origin + '/spa/videoPlayPage/movies/' + path + '?' +
                       urlencode(dict(id=identity, type='/movie/detail')), Origin=origin)
            media_headers = dict(Referer=origin + '/', Origin=origin)
            for route in ('download', 'play'):
                client.check()
                try:
                    data = unwrap(get(self.base_link + '/wefeed-h5api-bff/subject/' + route + '?' + urlencode(args), req))
                    for group in ('downloads', 'streams', 'dash'):
                        for stream in data.get(group, [])[:32]:
                            if stream.get('vipLocked'):
                                continue
                            yield item(stream.get('url'), media_headers, 'MovieBox ' + route + ' ' + group + ' · ' +
                                       (match[1] or 'Original'), str(stream.get('resolution') or stream.get('resolutions') or ''))
                except Exception as error:
                    client.host_result('MovieBox ' + route, type(error).__name__)
