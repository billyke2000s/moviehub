"""Configure one private repository using credentials supplied only by the bootstrap process."""
import base64
import json
import os
import secrets
import urllib.error
import urllib.request


def configure(url, owner, owner_password, bot_password, topic):
    bot = 'moviehub-repair'
    repo = f'/repos/{owner}/moviehub'
    def api(method, path, payload=None, *, as_bot=False, missing=False):
        username, password = (bot, bot_password) if as_bot else (owner, owner_password)
        headers = {'Authorization': 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode(),
                   'Content-Type': 'application/json'}
        request = urllib.request.Request(url + '/api/v1' + path,
            data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as result:
                raw = result.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError(f'Forgejo {method} {path} returned HTTP {error.code}') from None

    if api('GET', repo)['private'] is not True:
        raise RuntimeError('expected a private repository')
    if api('GET', '/users/' + bot, missing=True) is None:
        api('POST', '/admin/users', {'username': bot, 'email': bot+'@localhost.invalid',
                                    'password': bot_password, 'must_change_password': False})
    if api('GET', '/user', as_bot=True).get('is_admin') is not False:
        raise RuntimeError('repair account must have ordinary user permissions')
    api('PUT', repo + '/collaborators/' + bot, {'permission': 'write'})
    rule = dict(enable_push=False, enable_push_whitelist=False, enable_merge_whitelist=True,
                merge_whitelist_usernames=[owner], block_on_outdated_branch=True,
                dismiss_stale_approvals=True, unprotected_file_patterns='')
    prior = api('GET', repo+'/branch_protections/main', missing=True)
    if prior is None:
        api('POST', repo+'/branch_protections', dict(rule, rule_name='main'))
    else:
        # Keep any stricter existing review and status-check requirements.
        api('PATCH', repo+'/branch_protections/main', rule)
    current = api('GET', repo+'/branch_protections/main')
    if (current.get('enable_push') is not False or current.get('enable_merge_whitelist') is not True
            or current.get('merge_whitelist_usernames') != [owner] or current.get('unprotected_file_patterns')):
        raise RuntimeError('Forgejo did not enforce the expected main branch protection')

    def token(scopes):
        record = api('POST', '/users/'+bot+'/tokens', {
            'name': 'moviehub-bootstrap-'+secrets.token_hex(8), 'scopes': scopes,
            'repositories': [{'owner': owner, 'name': 'moviehub'}]}, as_bot=True)
        if not record.get('sha1'):
            raise RuntimeError('Forgejo did not issue the requested token')
        return record['sha1']
    # Keep only our two named installation credentials. A retry replaces them after
    # successfully storing their replacements, without touching unrelated user tokens.
    prefix = 'moviehub-bootstrap-'
    old_tokens = []
    page = 1
    while True:
        batch = api('GET', f'/users/{bot}/tokens?limit=50&page={page}', as_bot=True)
        old_tokens.extend(t for t in batch if t['name'].startswith(prefix))
        if len(batch) < 50:
            break
        page += 1
    write = token(['write:repository', 'write:issue'])
    api('PUT', repo+'/actions/secrets/HEALTH_TOKEN', {'data': write})
    api('PUT', repo+'/actions/secrets/NTFY_URL', {'data': topic})
    path = repo+'/actions/variables/MOVIEHUB_FORGEJO_URL'
    api('POST' if api('GET', path, missing=True) is None else 'PUT', path, {'value': url})
    reader = token(['read:repository'])
    return bot, reader, [entry['id'] for entry in old_tokens]


if __name__ == '__main__':
    import subprocess
    bot_pw = os.environ['FJ_BOT_PASSWORD']
    name, password, old_ids = configure('http://127.0.0.1:3000', 'billy', os.environ['FJ_ADMIN_PASSWORD'],
                                        bot_pw, os.environ['FJ_NTFY_URL'])
    # Do not open a service-owned path with root privileges.
    subprocess.run(['runuser', '-u', 'moviehub-release', '--', '/bin/sh', '-ec',
                    'cd /var/lib/moviehub-release; umask 077; f=$(mktemp); cat > "$f"; mv -fT "$f" .git-credentials'],
                   input=f'http://{name}:{password}@127.0.0.1:3000/billy/moviehub.git\n'.encode(), check=True)
    auth = base64.b64encode(f'{name}:{bot_pw}'.encode()).decode()
    for old in old_ids:
        req = urllib.request.Request(f'http://127.0.0.1:3000/api/v1/users/{name}/tokens/{int(old)}',
                                     headers={'Authorization':'Basic '+auth}, method='DELETE')
        with urllib.request.urlopen(req, timeout=30):
            pass
