// Wrappers replace server database/hooks with MovieHub's normalized row/stream interface.
export const adapters = {
    stirr: {
        catalogue: "exports.fetchStirrRows()",
        resolve: "exports.resolveStirrMaster(row.channelId)",
        headers: "exports.STIRR_STREAM_HEADERS",
    },
    tcl: {
        catalogue: "exports.fetchTclRows()",
        resolve: "exports.resolveTclMaster(row.channelId,row.source,row.media)",
        headers: "exports.TCL_STREAM_HEADERS",
    },
    xumo: {
        catalogue: "exports.fetchXumoRows()",
        resolve: "exports.resolveXumoMaster(row.channelId)",
        headers: "exports.XUMO_STREAM_HEADERS",
    },
    lg: {
        catalogue: "exports.fetchLgRows()",
        resolve: "exports.expandStreamMacros(row.streamUrl)",
        headers: "exports.LG_HEADERS",
    },
    whale: {
        catalogue: "exports.fetchWhaleRows()",
        resolve: "exports.fillUrlMacros(row.streamUrl)",
        headers: "exports.WHALE_HEADERS",
    },
    distro: {
        catalogue: "exports.fetchDistroRows()",
        resolve: "exports.fillStreamMacros(row.streamUrl)",
        headers: "exports.DISTRO_STREAM_HEADERS",
    },
    vizio: {
        catalogue:
            "exports.fetchVizioCatalog().then(function(rows){return rows.filter(exports.isSurfacedRow);})",
        resolve: "exports.expandMacros(row.streamUrl)",
        headers: "{'User-Agent':exports.UA}",
    },
    vidaa: {
        catalogue:
            "exports.fetchVidaaRows().then(function(rows){return rows.filter(exports.isSurfacedRow);})",
        resolve: "row.streamUrl",
        headers: "{'User-Agent':exports.UA}",
    },
    roku: {
        catalogue: "exports.fetchRokuRows()",
        resolve: "exports.resolveRokuMaster(row.channelId,false)",
        headers: "exports.ROKU_STREAM_HEADERS",
    },
    plex: {
        catalogue: "exports.fetchPlexRows()",
        // The manifest path needs the COMPOUND id (row.id, "<server prefix>-<gridKey>"), not the
        // stable gridKey in row.channelId: Plex answers 404 for the gridKey alone. The prefix
        // changes when Plex moves servers, so it comes from the (cached) catalogue row each time.
        resolve: "exports.resolvePlexMaster(row.id||row.channelId)",
        headers: "exports.PLEX_STREAM_HEADERS",
    },
};

// Regional copies: the same audited adapter with only its region changed, so each region is its
// own channel source, Settings switch and failure line. `env` is set on the module's process.env
// before the adapter loads; `replace` swaps one exact line of its source (the build fails if the
// line is missing, so an upstream change cannot silently drop the region).
export const variants = {
    lguk: {
        base: "lg",
        replace: ["export const LG_COUNTRY = 'US';", "export const LG_COUNTRY = 'GB';"],
    },
    // GB plus jsrdn's worldwide feed (QQ); the adapter keeps whichever answer, failing only if both do.
    distrouk: { base: "distro", env: { DISTRO_GEO: "GB QQ" } },
    vidaauk: { base: "vidaa", env: { VIDAA_GEO: "gb" } },
};
