"""Pin each existing native catalogue's newest upstream origin, independently.

Uses complete local Git histories, never repository push times or MovieHub edits.
This updates origins only: Kotlin resolver bodies still require a reviewed Python port.
No remote code or new providers are installed by this maintenance command.
"""
import argparse
import ast
from datetime import datetime
import ipaddress
import json
from pathlib import Path
import subprocess
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/python/moviehub_native'
UPSTREAMS = {
    'phisher': ('phisher98/TVVVV', 'domains.json'),
    'csx': ('SaurabhKaperwan/Utils', 'urls.json'),
}
# Upstream key -> stable MovieHub name, module, factory. Existing providers only.
TARGETS = {
    '4khdhub': ('fourkhdhub', 'fourkhdhub', 'source'),
    'bollyflix': ('bollyflix', 'cinestream_downloads', 'Bollyflix'),
    'rtally': ('rtally', 'cinestream_embeds', 'Rtally'),
    'hindmoviez': ('hindmoviez', 'cinestream_downloads', 'HindMoviez'),
    'moviesdrive': ('moviesdrive', 'cinestream_downloads', 'MoviesDrive'),
    'multimovies': ('multimovies', 'cinestream_embeds', 'Multimovies'),
    'skymovies': ('skymovies', 'cinestream_indexes', 'SkyMovies'),
    'uhdmovies': ('uhdmovies', 'cinestream_downloads', 'UHDMovies'),
    'moviesmod': ('moviesmod', 'cinestream_downloads', 'MoviesMod'),
    'topmovies': ('topmovies', 'cinestream_downloads', 'TopMovies'),
    'vegamovies': ('vegamovies', 'cinestream_downloads', 'VegaMovies'),
    'rogmovies': ('rogmovies', 'cinestream_downloads', 'RogMovies'),
    'toonstream': ('toonstream', 'cinestream_embeds', 'Toonstream'),
    'zinkmovies': ('zinkmovies', 'cinestream_downloads', 'ZinkMovies'),
    'dudefilms': ('dudefilms', 'cinestream_downloads', 'DudeFilms'),
    'm4ufree': ('m4ufree_lat', 'cinestream_indexes', 'M4ufreeLat'),
    'mlsbd': ('mlsbd', 'cinestream_indexes', 'Mlsbd'),
    'fibwatch': ('fibwatch', 'cinestream_indexes', 'Fibwatch'),
    'hdhub4u': ('hdhub4u', 'cinestream_downloads', 'HDHub4u'),
}


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


def normalized(data):
    result = {}
    for key, value in data.items():
        key = key.casefold()
        if key in result:
            raise ValueError('Duplicate upstream key: ' + key)
        result[key] = value
    return result


def origin(value):
    if not isinstance(value, str):
        raise ValueError('Origin must be a string')
    parsed = urlparse(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.port not in (None, 443) or parsed.path not in ('', '/')
            or parsed.query or parsed.fragment or any(c.isspace() for c in value)):
        raise ValueError('Expected a public HTTPS origin')
    host = parsed.hostname
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if '.' not in host or host.endswith(('.localhost', '.local', '.internal')):
            raise ValueError('Expected a public hostname')
    else:
        raise ValueError('Literal IP origins require separate review')
    return value.rstrip('/')


def changes(repo, filename):
    """Date the current value's latest semantic change, ignoring unrelated edits."""
    if git(repo, 'rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Complete Git history required: ' + str(repo))
    current = normalized(json.loads(git(repo, 'show', 'HEAD:' + filename)))
    pending = set(current) & set(TARGETS)
    last_same = {}
    result = {}
    history = git(repo, 'log', '--format=%H%x09%cI', '--', filename).splitlines()
    for line in history:
        commit, changed_at = line.split('\t')
        snapshot = normalized(json.loads(git(repo, 'show', commit + ':' + filename)))
        for key in list(pending):
            if snapshot.get(key) == current[key]:
                last_same[key] = dict(commit=commit, changed_at=changed_at)
            else:
                result[key] = dict(last_same[key], origin=origin(current[key]))
                pending.remove(key)
        if not pending:
            break
    for key in pending:
        result[key] = dict(last_same[key], origin=origin(current[key]))
    return result


def select(candidates):
    newest = max(datetime.fromisoformat(row['changed_at']) for row in candidates)
    winners = [row for row in candidates if datetime.fromisoformat(row['changed_at']) == newest]
    if len({row['origin'] for row in winners}) != 1:
        raise ValueError('Conflicting origins with equal change times require review')
    return sorted(winners, key=lambda row: row['repository'])[0]


def replace_literals(text, replacements):
    lines = text.splitlines(keepends=True)
    for node, value in sorted(replacements, key=lambda pair: (pair[0].lineno, pair[0].col_offset),
                              reverse=True):
        if node.lineno != node.end_lineno or not isinstance(node.value, str):
            raise ValueError('Expected a single-line string literal')
        index = node.lineno - 1
        # AST columns are UTF-8 byte offsets, including when comments contain Unicode.
        line = lines[index].encode()
        lines[index] = (line[:node.col_offset] + repr(value).encode() +
                        line[node.end_col_offset:]).decode()
    return ''.join(lines)


def render(selected, root=ROOT):
    edits = {}
    inventory = root / '__init__.py'
    inventory_tree = ast.parse(inventory.read_text())
    for key, row in selected.items():
        name, module, factory = TARGETS[key]
        path = root / (module + '.py')
        classes = [node for node in ast.parse(path.read_text()).body
                   if isinstance(node, ast.ClassDef) and node.name == factory]
        values = [node.value for cls in classes for node in cls.body if isinstance(node, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == 'base_link'
                          for target in node.targets)]
        if len(values) != 1:
            raise ValueError('Cannot locate exactly one origin for ' + name)
        edits.setdefault(path, []).append((values[0], row['origin']))
        sites = []
        for node in ast.walk(inventory_tree):
            if (isinstance(node, ast.Tuple) and len(node.elts) >= 3
                    and isinstance(node.elts[0], ast.Constant) and node.elts[0].value == name
                    and isinstance(node.elts[2], ast.Constant)):
                sites.append(node.elts[2])
            if isinstance(node, ast.Call) and any(
                    arg.arg == 'id' and isinstance(arg.value, ast.Constant)
                    and arg.value.value == 'native:' + name for arg in node.keywords):
                sites.extend(arg.value for arg in node.keywords if arg.arg == 'site')
        if len(sites) != 1:
            raise ValueError('Cannot locate exactly one inventory entry for ' + name)
        edits.setdefault(inventory, []).append((sites[0], urlparse(row['origin']).hostname))
    return {path: replace_literals(path.read_text(), replacements) for path, replacements in edits.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phisher', type=Path, required=True, help='Complete phisher98/TVVVV clone')
    parser.add_argument('--csx', type=Path, required=True, help='Complete SaurabhKaperwan/Utils clone')
    parser.add_argument('--write', action='store_true', help='Apply origins and provenance; default checks only')
    args = parser.parse_args()
    candidates = {key: [] for key in TARGETS}
    snapshots = {}
    for label, (repository, filename) in UPSTREAMS.items():
        repo = getattr(args, label)
        remote = git(repo, 'remote', 'get-url', 'origin').removesuffix('.git').rstrip('/')
        if remote not in ('https://github.com/' + repository, 'git@github.com:' + repository):
            raise ValueError('Unexpected upstream remote: ' + label)
        snapshots[repository] = git(repo, 'rev-parse', 'HEAD')
        for key, row in changes(repo, filename).items():
            candidates[key].append(dict(row, repository=repository, path=filename))
    selected = {key: select(rows) for key, rows in candidates.items()}
    outputs = render(selected)
    manifest = dict(scope='catalogue origins only; resolver code is separately maintained',
                    snapshots=snapshots,
                    providers={key: dict(selected=row, candidates=candidates[key])
                               for key, row in selected.items()})
    outputs[ROOT / 'upstream_origins.json'] = json.dumps(manifest, indent=2, sort_keys=True) + '\n'
    pending = [path for path, text in outputs.items() if not path.exists() or path.read_text() != text]
    if args.write:
        for path in pending:
            path.write_text(outputs[path])
    for key, row in selected.items():
        print(key, row['repository'], row['changed_at'], row['commit'][:12])
    print(('Updated' if args.write else 'Outdated') + ' files: ' + str(len(pending)))
    if pending and not args.write:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
