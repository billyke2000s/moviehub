# Changelog

Versions are published to TVs through the in-app updater (`android-tv-latest` release). Each
version below was built from the listed `main` commit. Earlier history is in the git log.

Note: the release shown as 0.10 below was published with version code 11 (name "0.11" in the
update feed), so the next release after it used code 12, named 0.12.

## Unreleased

Nothing yet.

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
