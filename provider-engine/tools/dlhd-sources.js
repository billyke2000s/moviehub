// Source choices come from the current watch page, not a fixed player count.
var __dlhdSourceMarker = "--player--";
var __dlhdDirectoryUrl = "https://daddylive.pk/";
function __dlhdMirror(value) {
    if (!/^https:\/\/(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$/.test(value))
        throw new Error("Invalid DaddyLive mirror");
    return value;
}
function __dlhdDirectoryAt(base) {
    exports.setBase(__dlhdMirror(base));
    return fetch(base + "/24-7-channels.php", {
        headers: { Referer: base + "/", "User-Agent": exports.UA },
    }).then(function (response) {
        if (!response.ok) {
            var error = new Error("DaddyLive directory HTTP " + response.status);
            error.httpStatus = response.status;
            throw error;
        }
        if (response.url) exports.setBase(__dlhdMirror(new URL(response.url).origin));
        return response.text().then(function (html) {
            var rows = require("./parseDirectory.js").parseChannels(html);
            if (!rows.length) throw new Error("DaddyLive directory is empty");
            return rows;
        });
    });
}
function __dlhdDirectory() {
    return fetch(__dlhdDirectoryUrl, { headers: { "User-Agent": exports.UA } })
        .then(function (response) {
            if (!response.ok) throw new Error("DaddyLive mirror directory unavailable");
            return response.text();
        })
        .then(function (html) {
            var matches = /\bdata-url=["'](https:\/\/[^/"'?#]+)\/?["']/gi;
            var seen = {},
                mirrors = [],
                online = [],
                match;
            while ((match = matches.exec(html))) {
                try {
                    var mirror = __dlhdMirror(match[1]);
                    if (!seen[mirror]) {
                        seen[mirror] = true;
                        mirrors.push(mirror);
                    }
                } catch (_) {}
                if (mirrors.length >= 8) break;
            }
            if (!mirrors.length) throw new Error("DaddyLive mirror cards unavailable");
            function check(index) {
                if (index >= mirrors.length) return open(0);
                var mirror = mirrors[index];
                return fetch(__dlhdDirectoryUrl + "check_status.php?url=" + encodeURIComponent(mirror), {
                    headers: { "User-Agent": exports.UA, Referer: __dlhdDirectoryUrl },
                })
                    .then(function (response) {
                        if (!response.ok) return null;
                        return response.json();
                    })
                    .catch(function () {
                        return null;
                    })
                    .then(function (status) {
                        if (status && status.status === "online") online.push(mirror);
                        return check(index + 1);
                    });
            }
            function open(index) {
                if (index >= online.length) throw new Error("No usable online DaddyLive mirror");
                return __dlhdDirectoryAt(online[index]).catch(function (error) {
                    if (error.httpStatus === 401 || error.httpStatus === 403 || error.httpStatus === 429)
                        throw error;
                    return open(index + 1);
                });
            }
            return check(0);
        });
}
function __dlhdCatalogue() {
    return __dlhdDirectory().then(function (channels) {
        var output = [],
            expanded = 0;
        function next(index) {
            var channel, base;
            // Only the sports rows need watch-page discovery at import. Keep this
            // below the broker's 64-request limit and preserve every other channel.
            while (index < channels.length) {
                channel = channels[index];
                base = { channelId: String(channel.id), name: channel.name, category: channel.group };
                if (/^(?:sky\s*sports?|tnt\s*sports?|bt\s*sports?)\b/i.test(channel.name) && expanded < 24)
                    break;
                output.push(base);
                index++;
            }
            if (index >= channels.length) return output;
            expanded++;
            return __dlhd
                .listPlayerPages(base.channelId, true)
                .catch(function () {
                    return [];
                })
                .then(function (pages) {
                    pages.forEach(function (page) {
                        var path = new URL(page.url).pathname;
                        var prefix = /^\/([a-z]{1,32})\/stream-\d+\.php$/.exec(path);
                        if (!prefix) return;
                        output.push({
                            channelId:
                                prefix[1] === "stream"
                                    ? base.channelId
                                    : base.channelId + __dlhdSourceMarker + prefix[1],
                            dlhdId: base.channelId,
                            name: base.name,
                            category: base.category,
                            guideChannelId: base.channelId,
                            sourceLabel: "Player " + page.playerIndex,
                            playerPath: path,
                            mirror: exports.getBase(),
                        });
                    });
                    if (!pages.length) output.push(base);
                    return next(index + 1);
                });
        }
        return next(0);
    });
}
function __dlhdResolve(row) {
    var raw = String(row.channelId || "");
    var variant = /^(\d{1,8})--player--([a-z]{1,32})$/.exec(raw);
    var channel = variant ? variant[1] : String(row.dlhdId || raw);
    if (!/^\d{1,8}$/.test(channel)) throw new Error("Invalid DaddyLive channel");
    if (row.mirror) exports.setBase(__dlhdMirror(row.mirror));
    var path = row.playerPath || (variant ? "/" + variant[2] + "/stream-" + channel + ".php" : "");
    if (path) return __dlhd.resolvePlayerPage(channel, path).then(__dlhdResult);
    var memo = require("./playerMemory.js");
    if (row.preferredPlayer) memo.noteGood(channel, row.preferredPlayer);
    (row.burntPlayers || []).forEach(function (player) {
        memo.noteBad(channel, player);
    });
    return __dlhd.resolveStreamUrl(channel, { deep: true }).then(__dlhdResult);
}
