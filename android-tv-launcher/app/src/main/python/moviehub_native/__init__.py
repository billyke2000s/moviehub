"""MovieHub's separately maintained public website adapters; no Kodi provenance claim."""
PROVIDERS = [dict(id='native:fourkhdhub', module='moviehub_native.fourkhdhub', family='native',
    name='fourkhdhub', site='4khdhub.one', movies=True, series=False),
    dict(id='native:vixsrc', module='moviehub_native.vixsrc', family='native',
    name='vixsrc', site='vixsrc.to', movies=True, series=True)]
