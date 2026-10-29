"""MovieHub's separately maintained public website adapters; no Kodi provenance claim."""
PROVIDERS = [dict(id='native:fourkhdhub', module='moviehub_native.fourkhdhub', family='native',
    name='fourkhdhub', site='4khdhub.one', movies=True, series=False),
    dict(id='native:vixsrc', module='moviehub_native.vixsrc', family='native',
    name='vixsrc', site='vixsrc.to', movies=True, series=True),
    dict(id='native:rive', module='moviehub_native.rive', family='native',
    name='rive', site='rivestream.app', movies=True, series=True),
    dict(id='native:movy', module='moviehub_native.movy', family='native',
    name='movy', site='movy.sx', movies=True, series=True)]

# CineStream 487: separate modules, no changes to the four established native adapters.
_CINESTREAM = [
    ('vaplayer', 'VaPlayer', 'streamdata.vaplayer.ru', True, True),
    ('primesrc', 'PrimeSrc', 'primesrc.me', True, True),
    ('fshare', 'Fshare', 'fsharetv.cc', True, False),
    ('videasy', 'Videasy', 'player.videasy.to', True, True),
    ('meowtv', 'Meowtv', 'meowtv.ru', True, True),
    ('vidup', 'Vidup', 'vidup.to', True, True),
    ('vidcore', 'Vidcore', 'vidcore.io', True, True),
    ('vidfast', 'Vidfast', 'vidfast.vc', True, True),
    ('onetouchtv', 'OneTouchTv', 'api3.devcorp.me', True, True),
    ('cinejoy', 'Cinejoy', 'cinejoy.to', True, True),
    ('hexa', 'Hexa', 'hexa.su', True, True),
]
_CATALOGUES = [
    ('projectfreetv_sx', 'ProjectFreeTv', 'projectfreetv.sx', True, True),
    ('akwam', 'Akwam', 'akwam.it', True, False),
    ('asiaflix', 'Asiaflix', 'asiaflix.net', True, True),
]
for group, module in ((_CINESTREAM, 'cinestream_api'), (_CATALOGUES, 'cinestream_catalogues')):
    for name, factory, site, movies, series in group:
        PROVIDERS.append(dict(id='native:' + name, module='moviehub_native.' + module,
                              factory=factory, family='native', name=name, site=site,
                              movies=movies, series=series))

for name, factory, site, module in (
    ('vidrock', 'Vidrock', 'vidrock.ru', 'cinestream_crypto'),
    ('peachify', 'Peachify', 'peachify.top', 'cinestream_crypto'),
    ('vidzee', 'Vidzee', 'player.vidzee.wtf', 'cinestream_crypto'),
    ('moviebox', 'MovieBox', 'h5-api.aoneroom.com', 'cinestream_moviebox'),
):
    PROVIDERS.append(dict(id='native:' + name, module='moviehub_native.' + module,
                          factory=factory, family='native', name=name, site=site,
                          movies=True, series=True))
PROVIDERS.append(dict(id='native:allmovieland', module='moviehub_native.cinestream_api',
                      factory='AllMovieLand', family='native', name='allmovieland',
                      site='allmovieland.one', movies=True, series=True))
for name, factory, site, series in (
    ('multimovies', 'Multimovies', 'm1.multimovies.courses', True),
    ('toonstream', 'Toonstream', 'toonstream.us', True),
    ('animesalt', 'AnimeSalt', 'animesalt.ac', True),
    ('rtally', 'Rtally', 'rtally.lol', False),
    ('kisskh', 'KissKH', 'kisskh.nl', True),
):
    PROVIDERS.append(dict(id='native:' + name, module='moviehub_native.cinestream_embeds',
                          factory=factory, family='native', name=name, site=site,
                          movies=True, series=series))

# Keep candidate protocols auditable without exposing unverified/crashing HLS routes in discovery.
# The CineStream live audit hit LibVLC FakeESOut timestamp assertions; no engine change is in scope.
for record in PROVIDERS:
    if 'factory' in record:
        record['pending'] = True
