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
        headers: "{'User-Agent':exports.UA,Referer:'https://watch.whaletvplus.com/'}",
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
        resolve: "exports.resolvePlexMaster(row.channelId)",
        headers: "exports.PLEX_STREAM_HEADERS",
    },
};
