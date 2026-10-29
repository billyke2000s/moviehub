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
    ('videasy', 'Videasy', 'vidy.st', True, True),
    ('meowtv', 'Meowtv', 'meowtv.ru', True, True),
    ('vidup', 'Vidup', 'vidup.to', True, True),
    ('vidcore', 'Vidcore', 'vidcore.io', True, True),
    ('vidfast', 'Vidfast', 'vidfast.vc', True, True),
    ('onetouchtv', 'OneTouchTv', 'api3.devcorp.me', True, True),
    ('cinejoy', 'Cinejoy', 'cinejoy.pk', True, True),
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
    ('animesalt', 'AnimeSalt', 'animesalt.cx', True),
    ('rtally', 'Rtally', 'rtally.lol', False),
    ('kisskh', 'KissKH', 'kisskh.do', True),
):
    PROVIDERS.append(dict(id='native:' + name, module='moviehub_native.cinestream_embeds',
                          factory=factory, family='native', name=name, site=site,
                          movies=True, series=series))

# Keep only concrete incomplete/unsafe paths pending. HTTP availability alone does not
# establish a broken site. Hexa additionally gates its independently failing backends.
for name, factory, site, module, movies in (
    ('bollyflix', 'Bollyflix', 'bollyflix.co.za', 'cinestream_downloads', True),
    ('topmovies', 'TopMovies', 'moviesleech.club', 'cinestream_downloads', True),
    ('moviesmod', 'MoviesMod', 'moviesmod.meme', 'cinestream_downloads', True),
    ('moviesdrive', 'MoviesDrive', 'new1.moviesdrive.cyou', 'cinestream_downloads', True),
    ('uhdmovies', 'UHDMovies', 'uhdmovies.beauty', 'cinestream_downloads', True),
    ('dudefilms', 'DudeFilms', 'dudefilms.red', 'cinestream_downloads', True),
    ('vegamovies', 'VegaMovies', '1vegamovies.shop', 'cinestream_downloads', True),
    ('rogmovies', 'RogMovies', 'rogmovies.im', 'cinestream_downloads', True),
    ('hindmoviez', 'HindMoviez', 'hindmovie.tax', 'cinestream_downloads', True),
    ('hdhub4u', 'HDHub4u', 'new10.hdhub4u.cl', 'cinestream_downloads', True),
    ('zinkmovies', 'ZinkMovies', 'new6.zinkmovies.mobi', 'cinestream_downloads', True),
    ('fibwatch', 'Fibwatch', 'fibwatch.art', 'cinestream_indexes', True),
    ('mlsbd', 'Mlsbd', 'mlsbd.co', 'cinestream_indexes', True),
    ('skymovies', 'SkyMovies', 'new4.skymovieshd.clinic', 'cinestream_indexes', True),
    ('m4ufree_lat', 'M4ufreeLat', 'm4ufree.lat', 'cinestream_indexes', False),
):
    PROVIDERS.append(dict(id='native:' + name, module='moviehub_native.' + module,
                          factory=factory, family='native', name=name, site=site,
                          movies=movies, series=True))

_PENDING = {'native:vaplayer', 'native:primesrc', 'native:asiaflix'}
for record in PROVIDERS:
    if record['id'] in _PENDING:
        record['pending'] = True
