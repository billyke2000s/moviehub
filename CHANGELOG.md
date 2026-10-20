# Changelog

## Unreleased — early live video freeze investigation

- Count rendered texture-buffer updates even when presentation timestamps repeat or reset; clear handoff covers from buffer callbacks.
- Detect a stopped visible video consumer independently of advancing audio; try one consumer refresh without reopening media or audio before bounded source recovery.
- Add constant-timestamp EGL, actual consumer-stall recovery and wrapped-HLS audio/video reload checks, plus a sustained numerical live diagnostic.

## Unreleased — retained live picture

- Remove system Activity fades for Live TV entry/return, including Android 14+ transition overrides.

- Keep the decoder Surface and SurfaceTexture through guide/full-screen handoffs instead of detaching native video output. Cover the transfer with the last rendered frame until a fresh frame arrives.
- Check repeated round trips using rendered frames, nonblank pixels and output identity, alongside existing stream and resolver counters. Physical-TV timing still needs verification.

## Unreleased — sustained provider playback

- Detect stopped native/provider video progress, with clock fallback when video counters are unavailable even without buffering/error events, using the existing eight-second stall threshold and bounded provider retry/backup handling. Ignore paused and hidden playback.
- Speed up PNG segment decoding with filter-0 and RGB bulk-copy paths and larger inflate buffers, preserving validation limits and releasing the inflater on failure. Physical-TV cause and sustained playback remain unverified.

## Unreleased — provider playback startup

- Give bounded link resolution its own phase, then start the video-opening timer when the URL/relay is ready. Previously a working provider taking over 15 seconds could be discarded before LibVLC received a stream.
- Show “Finding a working stream…” during resolution; preserve source backup, cancellation and view handoff behaviour.

## Unreleased — readable provider refresh failures

- Expand Settings cards to fit descriptions, put provider failures first, and retain safe network/HTTP/
  runtime diagnostics. The TV's DaddyLive import failure still needs a fresh diagnostic result.
- Fit guide labels to row height so constrained displays do not cut text in half.

## Unreleased — DaddyLive playlist sessions and PNG media

- Add the current anonymous playlist-token bootstrap, memory-only renewal and a single refresh after a
  rejected token. Keep tokens scoped to the discovered CDN/channel and preserve the existing player.
- Unpack supported PNG-contained MPEG-TS segments with strict format, size and decompression bounds.
- Add provider, renewal/scoping and segment regression checks. Desktop samples decoded for five channels;
  physical TV and sustained playback are not yet verified.


## Unreleased — experimental standalone provider engine

- Live probe on real services (20 October 2026) found MovieHub itself blocking providers: Samsung TV Plus now redirects through github.com (not allowed), and Tubi's guide (3.7 MB) and Plex's catalogue (3.1 MB) were over the 3 MB response limit. Allowed that redirect and raised the limits to 6 MB per response and 15 MB per operation, with a 16 MB script input cap (Tubi totals 7.5 MB, Plex 8.7 MB). A catalogue step may now take 10 s in the interpreter (3 s before) for slower TV boxes. Samsung redirects to Amagi's CDN are allowed. DaddyLive's stream servers answer 403 (referrer/signature gate) and ZLive's catalogue 404: provider-side, not changed.
- Failed providers are named in one line in the guide ("Unavailable now: …"), and the guide status is at most two lines; one line per provider had squeezed the channel rows to nothing. Settings → Provider engine shows the type of each provider's last refresh error, and the import count rises as providers load.
- On start, MovieHub asks once whether to turn on the free TV providers ("Turn them all on" or "Not now"), then shows the safety notice. Existing installs are asked too; Back leaves the question for next time. Providers were previously all off with no prompt.
- Provider channels now back up the same channel from the free lists and accounts: "Sky Sports F1 (720p)", "Sky Sports F1 UK", "… HD" and "Sky Sports Formula 1" are one channel with several sources (channel database version 9 re-keys saved channels; favourites and locks kept). Other countries, +1, regional and UHD channels stay separate.
- When a source fails, Live TV tries each other copy once ("Trying another source") before saying the channel is not working; SELECT then tries all copies again from the first. A slow error and a timeout from the same source no longer skip a backup.
- Provider channels are filed in the guide's existing sections by whole words (the earlier mapping put "TF1" in Sports). The Source list names each copy's provider.
- A provider turned on in Settings → Provider engine now imports whatever the free-list choice is; before, an account or "only without a TV provider" silently hid it.
- Add isolated local JavaScript execution of the historical FreeLiveSports TypeScript leaf, stable SQLite channels, inline EPG and resolve-on-play.
- Add opt-in provider settings, signed package validation, atomic rollback and gated upstream update CI.
- Document private upstream access, missing signing setup and provider/runtime limitations; no production release.


Versions are published to TVs through the in-app updater (`android-tv-latest` release). Each
version below was built from the listed `main` commit. Earlier history is in the git log.

Note: the release shown as 0.10 below was published with version code 11 (name "0.11" in the
update feed), so the next release after it used code 12, named 0.12.

## 0.39 (19 October 2026)

- Discover regional Pluto TV channels directly without a separately hosted server; resolve streams when played and keep favourites stable across session refreshes.
- Add native Pluto controls and catalogue counts to Free channels, while keeping Xtream, M3U and optional hosted Masqueradarr separate. FreeLinks remains disabled.
- Cancel stale native channel requests, preserve guide/fullscreen playback handoff, and try same-channel backups after source failures or live stream endings.
- Refresh provider guide data independently from XMLTV; retain cached channels and listings when a service fails.
- Add provider fixtures and native playback cancellation, fallback and handoff regression tests. Actual desktop decoding succeeded for one UK Pluto channel; real TV verification remains required.

## Unreleased

- Fixed Live TV's upgrade from the 0.35 channel database: create the preferred-channel column
  before rebuilding channel numbers, preserving saved channels, encrypted URLs and favourites.

- Troubleshooting uses the Settings sidebar and cards, with matching typography, spacing and
  buttons. Back returns to the System tab instead of Accounts.

## 0.37 (19 Oct 2026)

- Films and episodes carry on where you stopped whichever source you pick: progress is saved by
  the TMDB film or episode, not only by the link you pressed. Files opened from the cloud (no
  TMDB details) still carry on by file.

## 0.36 (19 Oct 2026)

- Skipping in films is quicker. Pressing −10 s or +10 s several times in a row now makes one
  jump, 0.75 s after the last press (as Kodi does), instead of reconnecting and buffering at every
  press. The controls show where the jump will land straight away.
- Moving along the film's progress bar with the remote jumps once, when you stop, instead of at
  every step. Letting go of the bar on a touch screen jumps at once.
- The player's download speed is right: it read about 1000 times too low (often 0.0 Mb/s), and so
  did the last speed in Diagnostics. It shows how fast the player reads the stream, which follows
  the stream's bitrate once the buffer is full, not the connection's top speed.
- Back on a film first hides the controls, then shows "Press Back again to leave playback", and
  only leaves on another press within 3 seconds. Live TV keeps its own Back.
- Films and Live TV now have separate player settings, so changing one can never change the other
  (the values are the same as before).
- Settings → System → Diagnostics is now Troubleshooting: an internet speed test, checks that each
  service you use answers (internet, TMDB, Premiumize, stream sources, your TV provider, the
  programme guide), playback timings, and the recent errors the app recovered from (place and
  type only, never addresses or account details).
- A closing player could crash the app if Live TV's shared player was released at that moment
  (seen in the device tests as "can't get VLCObject instance"); it now lets go of it instead.
- Live TV: changing channel and browsing other channels in the banner fetch just the one channel
  needed instead of a page of 151, and the guide fetches the eleven rows it uses.
- Live TV: the favourites list no longer rewrites its table on every page or channel change, only
  when favourites change.
- Live TV: finding a channel by number counts the list once instead of up to three times.
- Programme guide download: programmes on channels you don't have are skipped before their times
  are read, and times are read without building a date parser each time.
- Live TV: all channels, sections and countries are read through an index of each channel's
  preferred copy instead of sorting out duplicates across the whole list every time (channel
  database version 8; the upgrade fills it in once).
- Removed three unused channel queries; the channel database's tables and upgrades moved into
  `ChannelSchema`.
- Series: the episode you chose is found in far more season packs (S01 E03, S01Ep03, "Season 1
  Episode 3", E03 in a Season 1 folder, a plain file in an episode folder, and more). A file
  that names exactly that episode still plays straight away; one that very probably is it
  (a bare "03 - Title", a double episode) is marked "Suggested episode" for you to confirm and
  has the focus. Names that could mean something else (720p, x264, years, another season) are
  never marked. A show called "Extras" no longer loses its episodes.
- Apps: an app with separate TV and phone launcher entries is listed once (apps from different
  packages with the same name are still both shown). The Apps screen shows its first rows at once
  and the rest straight after.
- Two marked, empty lists for new sources, ready to fill in: `DefaultSourceProviders.EXTRA` for
  film and series providers and `FreeChannels.EXTRA` for TV channel lists (AGENTS.md, Adding
  sources). Empty, so nothing changes yet.

## 0.35 (18 Oct 2026)

- A Live TV channel that won't play no longer opens a "Can't play this" panel that blocks the
  remote. The picture says the channel isn't working, and ▲ ▼ go straight on to the next one;
  SELECT tries it again and BACK goes to the guide. (Backups are still tried first.) Films and
  series keep their panel.
- Down past the bottom row of the guide turns the page again (round to the first page after the
  last), instead of moving onto the colour keys. The colour keys are a guide to the remote again;
  without coloured buttons, BACK opens the guide menu, which has Reminders, Search and Options
  along the top and Favourites on its second page.
- In the guide menu, Down from the last row goes round to the top, as on the satellite box.
- Going back from the full screen to the guide, the corner picture could stay black on some
  boxes even though the channel carried on. The picture's decoder now restarts on the corner's
  view (no reconnecting), so the picture shows.

## 0.34 (18 Oct 2026)

Details from the old Sky+ box, checked against David Wheatley's Sky+ web EPG simulator
(sky-epg.davwheat.dev), on the dark-blue guide:

- The TV Guide menu is in the box's order: 1 All Channels, 2 Entertainment, 3 Movies, 4 Sports,
  5 News, 6 Documentaries, 7 Kids, 8 Music, 9 Radio, 0 More…. Favourites is on the blue key and
  the second page, with Religion, Shopping, International, your lists' groups and Options. BACK
  on the second page goes back to the first.
- Paging wraps round: Page down on the last page goes to the first, Page up on the first goes to
  the last.
- The box's exact blues and yellows for the rows, number boxes, highlight and colour keys.
  Programme slots with no listings are drawn in the faded blue the box used.

## 0.33 (18 Oct 2026)

- The channel guide is back to the dark-blue look from 0.26: the time and list name at the top
  left, Options and the live picture at the top right, the original sizes, colours and typeface,
  and square colour keys. The light-blue redesign from 0.28 (programme preview and tabs) is gone;
  sections are on the blue key and the guide menu. Scrolling right through the day, reminders and
  the rest are kept.
- The TV Guide menu is in the same dark blue as the guide.
- No coloured buttons on your remote? The colour keys at the bottom of the guide and the menu can
  now be chosen: press Down past the bottom row, move along them and press SELECT. Page down stays
  selected, so SELECT again turns another page. While watching, SELECT twice opens Options, which
  now also has Programme information, so it covers every coloured button.
- Live TV now opens on the TV Guide menu first, like the old satellite box's TV Guide button.
  Pick a list (or press its number) to see the channels; BACK from the channels comes back here.
- Every row of the menu now fits on the screen, so 9 Music and 0 More… are no longer cut off.
- REMINDERS, SEARCH and OPTIONS along the top of the menu now work: press Up from the first row,
  then Left and Right.

## 0.32 (18 Oct 2026)

- The "Staying safe" notice is now short and plain: MovieHub never asks you to leave the app
  while watching, so ignore any message in a stream that does and watch something else.

## 0.31 (18 Oct 2026)

- The first time MovieHub opens it asks whether it is on a TV with a remote or a phone or tablet
  by touch (with its best guess suggested, as some TV boxes report themselves as phones). The
  answer is kept; Settings → System → Layout changes it.
- Phone layout, starting with Live TV: a portrait channel list with numbers, now and next, section
  tabs and search; tap to watch, hold for favourites. The player's banner gets touch buttons
  (channel up and down, info, source, audio, pause, guide), and tapping the picture shows or hides
  it. Films, series and settings still use the TV layout for now.

## 0.30 (18 Oct 2026)

- Phones and tablets get a MovieHub icon in the app drawer, so it opens as an ordinary app without
  being made the home screen. TVs are unchanged. It is still laid out for a TV and a remote.

## 0.29 (18 Oct 2026)

- The guide's coloured buttons are back to how they were, as on the satellite guide: red page up,
  green page down, yellow search, blue sections (the guide menu). CH+ and CH− still turn pages too.
  Tomorrow and Back to today are in Options, and ◀ ▶ still move through the day.

## 0.28 (18 Oct 2026)

The Live TV guide is redone from photos of the real satellite-box guide:

- A light-blue guide: "TV guide" and the clock across the top, then the selected programme's
  time, title, tags and description with the live picture beside it.
- Tabs across the guide: All Channels, Favourites, Entertainment, Movies, Sports, News, Docs and
  Kids (▲ from the top row; ◀ ▶ change the list), and More… for the rest.
- The selected programme and its channel name are both yellow; the other cells are dark blue.
- Round colour keys: red Reminders, green Search A–Z, yellow +24 Hours, blue −24 Hours. CH+ and
  CH− turn the pages.
- BACK from the guide opens the guide menu: a numbered list (1 All Channels … 9 Music, 0 More…)
  with the chosen row in yellow; number keys pick a row. Its colour keys: red Options, green
  Reminders, yellow Search A–Z, blue Favourites.

## 0.27 (18 Oct 2026)

- Staying safe: when MovieHub first opens it explains that it will never ask you, during or about
  a stream, to go to a website, ring a number, install anything, let anyone control your devices
  or pay, and that any channel saying so is a scam. It stays in Settings → System.
- Live TV uses a cleaner TV typeface (Hind, free to use) in the guide, banner and panels.
- The guide has a day bar (today and tomorrow), and the yellow key jumps to the same time
  tomorrow and back. Search is still in Options.
- Programme details where the free guide has them: episode ("S1 Ep3"), age rating, subtitles,
  HD and New, shown in the banner and the programme information.
- The banner slides up when it appears.

## 0.26 (18 Oct 2026)

- Live TV keeps one player running: going from the guide's corner picture to the full screen and
  back carries the same stream on, with no reconnecting or buffering. Films and series are not
  affected.
- The full-screen Live TV picture looks like the classic UK satellite box: the film controls are
  gone, and the banner has a light-blue title strip with the yellow channel number, name and
  clock over a deep-blue panel with Now and Next, a progress bar and the colour keys.
- Satellite remote: SELECT shows the banner (SELECT again opens Options), i or yellow shows the
  programme information panel, red is audio and subtitles, green the source, blue (or MENU)
  Options, ◀ ▶ browse what is on, ▲ ▼ change channel.
- Pausing live TV shows a pause bar; playing on behind live says how to catch up.
- BACK in Live TV closes whatever is open, then goes straight back to the guide (one press: the
  picture carries on in the corner). Films still need BACK twice.
- The guide moves like the satellite guide: ▶ goes along a channel's programmes and, past the
  last slot, moves the times on (the channel names stay on the left); ◀ comes back towards now
  and then to the channel name; ▲ ▼ keep to the same time on the next channel. SELECT on what is
  on now watches it, and on a later programme sets (or cancels) a reminder. i (or holding SELECT)
  shows the programme's details.

## 0.25 (18 Oct 2026)

Live TV is organised like a UK satellite guide instead of one long list:

- Every UK channel has a number in the right place, not just the ones the satellite guide lists:
  other entertainment channels follow 182, films the 300s, music the 350s, sport the 400s, news
  the 500s, religion the 580s, kids the 600s and shopping the 660s. Regional BBC One and BBC Two
  and the BBC red-button streams are in the 800s.
- Radio stations have their own Radio section (UK radio is 901 onwards) instead of sitting among
  TV channels.
- Channels from other countries are in a new International section, numbered from 1001 country
  by country: Ireland, the United States, Canada, Australia and New Zealand first, then A to Z.
  Choosing International on the blue key lists the countries.
- Entertainment, Movies, Sports, News and the other sections now hold UK channels only.

## 0.24 (18 Oct 2026)

Live TV now has the rest of what the old satellite boxes did:

- UK satellite channel numbers: 101 BBC One, 102 BBC Two, 103 ITV1, 104 Channel 4, 105 Channel 5
  and so on, wherever the channel is in your lists. Other channels follow from 1001. The guide is
  in number order, and typing a number goes to that channel even from Favourites or a section.
- Guide sections on the blue key: Entertainment, Movies, Music, Sports, News, Documentaries, Kids,
  Religion and Shopping (your lists' own groups are still there too).
- The channel you last watched keeps playing in the top corner of the guide. Options → Picture in
  the guide turns it off.
- Programme information: press ▶ (or Info) on a channel in the guide for what is on now with a
  description and the rest of the day. Choose a programme to read about it and set a reminder.
- Reminders: when a programme you chose is about to start, a panel offers to switch to it
  (while MovieHub is open). Options → Reminders lists them.
- The banner shows a progress bar and a short description, and ◀ ▶ (with the controls hidden)
  browse what is on other channels without leaving this one: ▲ ▼ move between channels, ◀ ▶
  between programmes, SELECT watches the channel shown and BACK closes it.
- Parental lock: choose a four-digit PIN (Options → Parental lock), then lock channels (from
  their programme information) or whole sections. Locked channels ask for the PIN before playing.
- Pause live TV, then carry on from where you paused, or choose More → Back to live. Rewind works
  on channels whose stream allows it; others say so.

## 0.23 (18 Oct 2026)

- More free channels: the Free-TV list (free-to-air and free ad-supported channels) is added to
  iptv-org, with its UK channels first, so BBC One is channel 101.
- Backups: a channel found in more than one list appears once in the guide; the other copies are
  backups. If a stream fails, stops for 8 seconds or keeps buffering, the player switches to the
  next copy by itself, and More → Source lets you pick one.
- Watching Live TV works like a satellite box: Up/Down (when the controls are hidden), CH+/CH- and
  PG+/PG- change channel, number keys tune straight to a channel, the Last Channel key goes back,
  and a now-and-next banner shows the channel number, name and programmes on every change.
- The guide looks more like the classic satellite-box guide: dark bands with a lighter blue sweep,
  arrows on programmes that run past the slots shown, and SELECT on a yellow key.

## 0.22 (18 Oct 2026)

- Live TV guide shows what is on: a free UK programme guide (epgshare01) is downloaded with the
  channels, about once a day, and programmes fill the half-hour slots. Channels are matched by
  name, so many UK channels have listings; others still say "No programme information".
- Coloured buttons: remotes that send other codes for them now work too, and Options → Set up
  coloured buttons teaches the guide your remote's buttons.
- Number keys jump straight to a channel: type 151 to go to channel 151.

## 0.21 (18 Oct 2026)

- Live TV works without a TV provider: the full public iptv-org channel list is loaded the first
  time you open Live TV. Settings → Accounts → Free channels can switch to one country, keep the
  full list even with a provider, or turn it off. Some free streams may not work, and some need
  Allow HTTP.
- Live TV redesigned as a classic satellite TV guide: a blue full-screen grid of ten channels per
  page numbered from 101, half-hour time slots across the top, the selected channel in yellow, and
  colour keys (red/green page up and down, yellow search, blue groups; CH+/CH- also page). Options
  covers the same for remotes without colour keys. Programme titles need a programme guide from a
  provider; the free list has none, so rows say "No programme information".

## 0.20 (18 Oct 2026)

- New Diagnostics page (Settings → System → Diagnostics): how long TMDB requests, finding sources,
  the Premiumize cache check and file link, artwork, "press play to picture" and buffering pauses
  take on this box, plus the last download speed. Only names and times are shown.
- Code tidy-up with no visible change: the player is split into three smaller files.

## 0.19 (18 Oct 2026)

- Faster menus: your saved logins are unlocked once instead of on every request (every poster row,
  search and source check used to go through Android's secure storage).
- Pressing play no longer waits for the watch history to be saved; it saves in the background.
- Home no longer reloads every installed app's name and icon each time; it reloads only after apps
  are installed, updated or removed.
- Player: the download speed shows under the title and next to the buffering percentage, so you can
  tell a slow connection from a slow app.

## 0.18 (18 Oct 2026)

- Player controls redone in the streaming-app style: flat icons with no button shapes (rewind 10,
  play/pause, forward 10, Audio & Subtitles, Video, More), the selected one bright and slightly
  larger; the time left shows at the end of the timeline.
- No pill-shaped buttons anywhere: every button uses the app's standard style.
- Faster start: when a source holds one obvious file (the film, or your episode in a season pack)
  it plays straight away instead of asking you to choose a file.
- Resuming opens the video at your place instead of starting at 0:00 and then jumping, which made
  it buffer twice. Buffering shows its progress in percent.

## 0.17 (18 Oct 2026)

- Player: Back no longer stops the video straight away. The first press shows the controls and
  "Press Back again to stop watching"; a second press within 3 seconds leaves. Holding Back does
  not count as several presses.

## 0.16 (18 Oct 2026)

- Player: the title now sits at the top; at the bottom a full-width slim timeline with the time on
  the right, play controls on the left and Video, Audio, Subtitles and More on the right.
- Built with Android Gradle plugin 9.4.1 and Gradle 9.6.0 (no visible change).

## 0.15 (18 Oct 2026)

- For you on Home blends recommendations from what you played, saved and opened, instead of
  changing with every title you look at.
- New Apps screen: search, Android shortcuts (Wi-Fi, Bluetooth, display, sound, storage, ...),
  recently used apps, TV banners, and hiding apps you never use (hold Select on an app).
- MovieHub has its own icon and banner, and is named "MovieHub" instead of "Home".
- Every pop-up is now a panel on the right in MovieHub's style instead of Android's grey dialogs.
- Redesigned Sources (quality, HDR, codec and size at a glance), Series (season pills with the
  episodes on the same page), Continue watching, Live TV, cloud and the player controls.
- Search: one large box with results appearing as you type, in Movies, Series and People rows.
- Movies/Series: Back jumps to the top bar from anywhere; Back again leaves.
- Clearer messages when something fails to load, with Try again or Open Settings.
- Updates show what's new before installing.
- Faster artwork: images you have seen are kept on the box and load instantly.
- Home: the featured title's button is back to the "View details" style, sized to its label.

## 0.14 (18 Oct 2026)

Published from `main` after PRs #32 and #33:

- Redesigned title details page: full-screen artwork with the type and genres, a large title,
  facts, synopsis (shown once, not twice) and pill buttons on the left, the main action in white;
  then a "More like this" poster row, the collection and the cast as round photos.
- The Movies/Series title area keeps the same height for every title, so the poster rows no
  longer move when a synopsis is shorter or longer; the title sits higher to make room.
- Home: the featured title's action became a slim "More info" pill (changed back in 0.15).

## 0.13 (18 Oct 2026)

- Settings: Appearance, Playback and System now use the same cards as Accounts (title, short
  description, current value on the right, one action button) instead of plain switches and
  full-width buttons. The redundant "Return home" button is gone; Back does the same.
- Movies and Series open on a new start page in the style of the big streaming apps: a large
  backdrop with the focused title, year, rating and synopsis, above rows of posters by category
  (Trending, Popular, Top rated, release lists and every genre). Posters have no captions, the row
  being browsed always sits fully in view, rows load as you reach them, and your place is kept when
  you open a title and come back. Browse, Filters, Search and Library work as before; a category
  chosen there opens as a full grid, and Back returns to the rows.
- Home: the featured title's artwork now fills the whole screen behind the page, pinned top-right
  with soft fades on the left and bottom, instead of sitting in a zoomed-in box. Artwork
  cross-fades as you move between posters, and no placeholder block flashes while it loads.
- Home: a MemoryClean shortcut beside Settings, shown as the app's own icon with no box (only
  when an app named exactly "MemoryClean" is installed). The clock moves left to make room.
- Focused list rows (for example stream sources) no longer grow into the rows around them or past
  the screen edge; small items still grow slightly, and focused items always draw on top.

## 0.12 (18 Oct 2026)

Published from `main` after PRs #25 and #26:

- Full portrait posters and redesigned movie/series browsing with filter overlays (PR #20).
- The catalogue no longer jumps back to the top when the first page arrives; long category lists
  moved into a Browse overlay (PR #21, plus direct follow-up commits on initial poster focus).
- Rearrange the Home hotbar with the remote; app actions and Android system controls in All Apps (PR #23).
- Redesigned streaming menus: catalogue previews, cinematic details, trailers, cast, collections,
  search history, combined filters, library sorting, viewing progress, watched episodes, channel
  groups and logos, source quality filters, cloud breadcrumbs and player options (PR #24).
- Fixed: quick D-pad presses along a poster row or grid could lose the selection.
- Failures the app recovers from are logged to logcat (tag `MovieHub`) without account details.
- Device tests are reliable again (8/8 parallel runs) and call app code directly; CI actions use Node 24.
- Code restructure: Java sources reformatted for readability, `MainActivity` split into feature
  classes, project documentation added, and failed device tests listed as CI annotations. No
  intended behaviour change (PR #25).

## 0.10 (17 Oct 2026)

Built from PR #19: keeps scrolling artwork loaded and simplifies catalogue navigation (grouped
catalogues open on daily trending).

## 0.9 (17 Oct 2026)

Built from PR #18: keeps TV focus stable while movie and series feeds load continuously; end-of-list
message and scrolling captions.

## 0.8 (17 Oct 2026)

Built from PR #17: complete TMDB category browsing in Movies and Series (all movie and TV genres,
trending, popular, top rated and release/airing lists).

## 0.7 (17 Oct 2026)

Built from PR #16: TV home fits the viewport; catalogue and account navigation made visible.

## 0.6 (16 Oct 2026)

Built from PR #15: MovieHub TV browsing, scalable IPTV and fullscreen playback, with signing
continuity checks.

### Experimental provider runtime 2 (unreleased)

- Execute fifteen additional historical Masqueradarr adapters locally, including HTTP/POST, async replay,
  cookies within operations, Tubi crypto, compressed catalogues and fresh stream resolution.
- Add individual settings, stable SQLite locators, inline guide isolation and optional local HLS header transport.
- Preserve direct playback and existing providers; document partial live evidence and remaining browser/source blockers.

## Unreleased — live controls and source choice

- First Back shows live controls; next Back returns to the guide’s retained mini-player.
- Add a selectable Sources action, Automatic by default, with manual source pinning.
- Keep backup discovery local and resolve only a selected source or the next bounded failover.
