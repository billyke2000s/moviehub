package local.tv.home;

import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Live TV guide's side panels: options, sections and groups, search, programme information
 * with reminders, the parental lock and colour-key set-up.
 */
final class LiveTvPanels {

    private final MainActivity activity;
    private final LiveTvScreen guide;

    LiveTvPanels(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /** Teaches the guide this remote's colour keys: press red, green, yellow, then blue. */
    void learnColourKeys(int step) {
        learnColourKeys(step, new StringBuilder());
    }

    /** {@code learned} lists what each button sent so far, shown as the set-up goes on. */
    private void learnColourKeys(int step, StringBuilder learned) {
        String[] names = { "RED", "GREEN", "YELLOW", "BLUE" };
        if (step >= 4) {
            activity.toast("Coloured buttons set up\n" + learned);
            guide.guide(guide.pageStart, 0);
            return;
        }
        android.app.Dialog panel = SidePanel.of(activity, "Press the " + names[step] + " button")
            .message(
                "Press the " +
                    names[step].toLowerCase(Locale.ROOT) +
                    " button on your remote now. If nothing happens, the remote is not sending" +
                    " that button to the TV box, so MovieHub can't use it. Back cancels." +
                    (learned.length() == 0 ? "" : "\n\n" + learned)
            )
            .show();
        panel.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK) return false;
            boolean navigation =
                key == KeyEvent.KEYCODE_DPAD_UP ||
                key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_LEFT ||
                key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                key == KeyEvent.KEYCODE_DPAD_CENTER ||
                key == KeyEvent.KEYCODE_ENTER;
            if (navigation) return true;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                int scan = event.getScanCode();
                // A button Android has no name for is told apart only by its scan code.
                if (key == KeyEvent.KEYCODE_UNKNOWN && scan <= 0) return true;
                android.content.SharedPreferences.Editor edit = activity.prefs.edit();
                if (key == KeyEvent.KEYCODE_UNKNOWN) edit.putInt(
                    LiveTvScreen.COLOUR_SCAN_PREFS[step],
                    scan
                ).remove(LiveTvScreen.COLOUR_PREFS[step]);
                else edit.putInt(LiveTvScreen.COLOUR_PREFS[step], key).remove(
                    LiveTvScreen.COLOUR_SCAN_PREFS[step]
                );
                edit.apply();
                learned
                    .append(learned.length() == 0 ? "" : "\n")
                    .append(names[step].charAt(0))
                    .append(names[step].substring(1).toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(KeyEvent.keyCodeToString(key))
                    .append(" (scan ")
                    .append(scan)
                    .append(")");
                panel.dismiss();
                learnColourKeys(step + 1, learned);
            }
            return true;
        });
    }

    /** Everything the colour keys do, for remotes without colour keys, and the guide's settings. */
    void options() {
        boolean picture = activity.prefs.getBoolean(LiveTvScreen.PICTURE, true);
        // Lists and sections are in the guide menu and on the colour keys; this panel only holds
        // what has no other home. A day choice is shown only when it would change something.
        SidePanel panel = SidePanel.of(activity, "Live TV").option("Search", this::searchPanel);
        if (guide.windowOffset < LiveTvScreen.MAX_OFFSET) panel.option("Next day (+24 hours)", () ->
            guide.nextDay(guide.focusedRow())
        );
        if (guide.windowOffset > 0) panel.option("Previous day (\u221224 hours)", () ->
            guide.previousDay(guide.focusedRow())
        );
        panel
            .option("Reminders", this::reminders)
            .option("Parental lock", this::parentalLock)
            .option(picture ? "Picture in the guide · On" : "Picture in the guide · Off", () -> {
                activity.prefs.edit().putBoolean(LiveTvScreen.PICTURE, !picture).apply();
                guide.startPicture();
            })
            .option("Set up coloured buttons", () -> learnColourKeys(0))
            .option("Refresh channels", () -> {
                ChannelSync.refreshNow(activity);
                guide.guide(0, 0);
            })
            .show();
    }

    /** International channels by country: Ireland, the United States … then A to Z. */
    void countries() {
        activity.async(
            () -> guide.channelsDb().countries(),
            countries -> {
                String[] names = new String[countries.size() + 1];
                names[0] = "All international channels";
                for (int i = 0; i < countries.size(); i++) names[i + 1] =
                    GuideOrder.name(countries.get(i).name) + " · " + countries.get(i).count;
                SidePanel.of(activity, "International")
                    .choices(names, -1, which -> {
                        if (which == 0) guide.show("section:" + GuideOrder.INTERNATIONAL, "");
                        else guide.show("country:" + countries.get(which - 1).name, "");
                    })
                    .show();
            }
        );
    }

    // ---- Programme information (the i key) ----------------------------------------------------

    /**
     * What is on a channel: now and the rest of today, with descriptions. Choosing a programme
     * shows it in full and sets or cancels a reminder.
     */
    void programmeInfo(ChannelDatabase.Row channel, int position) {
        long now = System.currentTimeMillis();
        activity.async(
            () ->
                guide
                    .channelsDb()
                    .programmes(channel, EpgImporter.COUNTRY, now, now + EpgImporter.AHEAD_MS, 40),
            listing -> {
                ChannelDatabase.Programme current = null;
                for (ChannelDatabase.Programme item : listing)
                    if (item.start <= now && item.stop > now) current = item;
                StringBuilder message = new StringBuilder();
                if (current == null) message.append(
                    listing.isEmpty() ? "No programme information for this channel." : "Nothing listed now."
                );
                else {
                    message.append("Now  ").append(times(current)).append("\n").append(current.title);
                    if (!current.description.isEmpty()) message.append("\n").append(current.description);
                }
                SidePanel panel = SidePanel.of(activity, channel.number + "  " + channel.name)
                    .message(message.toString())
                    .option("Watch " + channel.name, () -> guide.open(channel, position));
                for (ChannelDatabase.Programme item : listing) {
                    if (item == current) continue;
                    panel.option(
                        (Reminders.has(activity, channel, item) ? "⏰  " : "") +
                            LiveTvScreen.clockTime(new java.util.Date(item.start)) +
                            "   " +
                            item.title,
                        () -> programme(channel, position, item)
                    );
                }
                boolean locked = ParentalLock.channelLocked(activity, channel);
                panel.option(locked ? "Unlock this channel" : "Lock this channel", () ->
                    lockChannel(channel, !locked)
                );
                panel.show();
            }
        );
    }

    /** One programme in full, with a reminder to set or cancel. */
    void programme(ChannelDatabase.Row channel, int position, ChannelDatabase.Programme item) {
        boolean reminder = Reminders.has(activity, channel, item);
        String about =
            times(item) +
            "  ·  " +
            channel.number +
            " " +
            channel.name +
            (item.category.isEmpty() ? "" : "  ·  " + item.category) +
            (item.tags().isEmpty() ? "" : "  ·  " + item.tags()) +
            (item.description.isEmpty() ? "" : "\n\n" + item.description);
        SidePanel.of(activity, item.title)
            .message(about)
            .action(reminder ? "Cancel reminder" : "Remind me", () -> {
                Reminders.set(activity, channel, item, !reminder);
                activity.toast(reminder ? "Reminder cancelled" : "Reminder set for " + item.title);
                guide.guide(guide.pageStart, position - guide.pageStart);
            })
            .action("Back", () -> programmeInfo(channel, position))
            .show();
    }

    static String times(ChannelDatabase.Programme item) {
        return (
            LiveTvScreen.clockTime(new java.util.Date(item.start)) +
            "–" +
            LiveTvScreen.clockTime(new java.util.Date(item.stop))
        );
    }

    /** Reminders set, soonest first; choosing one cancels it. */
    void reminders() {
        List<Reminders.Reminder> all = Reminders.all(activity);
        if (all.isEmpty()) {
            SidePanel.of(activity, "Reminders")
                .message(
                    "No reminders set. Press ▶ on a channel in the guide and choose a programme to set one."
                )
                .action("Close", null)
                .show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) {
            Reminders.Reminder item = all.get(i);
            names[i] =
                new java.text.SimpleDateFormat("EEE", Locale.UK).format(new java.util.Date(item.start)) +
                " " +
                LiveTvScreen.clockTime(new java.util.Date(item.start)) +
                "  " +
                item.title +
                "  ·  " +
                item.channel;
        }
        SidePanel.of(activity, "Reminders")
            .choices(names, -1, which -> {
                Reminders.Reminder item = all.get(which);
                SidePanel.of(activity, item.title)
                    .message(names[which])
                    .action("Cancel reminder", () -> cancelReminder(item))
                    .action("Keep it", null)
                    .show();
            })
            .show();
    }

    private void cancelReminder(Reminders.Reminder item) {
        ChannelDatabase.Row row = new ChannelDatabase.Row(
            0,
            item.channel,
            "",
            "",
            item.key,
            "",
            "",
            item.number,
            ""
        );
        Reminders.set(
            activity,
            row,
            new ChannelDatabase.Programme(item.start, item.start, item.title, "", ""),
            false
        );
        activity.toast("Reminder cancelled");
        guide.guide(guide.pageStart, 0);
    }

    // ---- Parental lock ------------------------------------------------------------------------

    /** Locks or unlocks one channel; both need the PIN (one is chosen first if there is none). */
    private void lockChannel(ChannelDatabase.Row channel, boolean lock) {
        Runnable apply = () -> {
            ParentalLock.setChannel(activity, channel, lock);
            activity.toast(lock ? channel.name + " is locked" : channel.name + " is unlocked");
            guide.guide(guide.pageStart, 0);
        };
        if (!ParentalLock.hasPin(activity)) ParentalLock.choosePin(activity, apply);
        else ParentalLock.check(activity, "Changing the lock", apply, null);
    }

    /** The lock's settings, behind the PIN: what is locked, a new PIN, or turning it off. */
    void parentalLock() {
        parentalLock(() -> guide.guide(guide.pageStart, 0));
    }

    /**
     * The same, from anywhere (the guide, the phone list or Settings); {@code changed} redraws the
     * screen it was opened from after something is locked or unlocked.
     */
    void parentalLock(Runnable changed) {
        if (!ParentalLock.hasPin(activity)) {
            SidePanel.of(activity, "Parental lock")
                .message(
                    "Lock Live TV channels, whole guide sections (such as Sports), " +
                        "or all films and series, " +
                        "so they need a four-digit PIN to watch. " +
                        "Lock one channel from its programme information (\u25b6 in the guide)."
                )
                .action("Choose a PIN", () -> ParentalLock.choosePin(activity, () -> lockSettings(changed)))
                .action("Close", null)
                .show();
            return;
        }
        ParentalLock.check(activity, "The parental lock", () -> lockSettings(changed), null);
    }

    private void lockSettings(Runnable changed) {
        boolean films = ParentalLock.filmsLocked(activity);
        SidePanel.of(activity, "Parental lock")
            .option(films ? "Films and series \u00b7 Locked" : "Films and series \u00b7 Not locked", () -> {
                ParentalLock.setFilmsLocked(activity, !films);
                activity.toast(films ? "Films and series are unlocked" : "Films and series need the PIN");
                changed.run();
                lockSettings(changed);
            })
            .option("Locked Live TV guide sections", () -> sectionLocks(changed))
            .option("Change PIN", () -> ParentalLock.choosePin(activity, null))
            .option("Turn off the lock", () -> {
                ParentalLock.clear(activity);
                activity.toast("Parental lock turned off");
                changed.run();
            })
            .show();
    }

    /** Ticks the locked sections; choosing one locks or unlocks it. */
    private void sectionLocks(Runnable changed) {
        String[] names = new String[SkyNumbers.SECTIONS.length];
        for (int i = 0; i < names.length; i++) names[i] =
            (ParentalLock.sectionLocked(activity, SkyNumbers.SECTIONS[i]) ? "🔒  " : "") +
            SkyNumbers.SECTIONS[i];
        SidePanel.of(activity, "Locked Live TV guide sections")
            .choices(names, -1, which -> {
                String section = SkyNumbers.SECTIONS[which];
                ParentalLock.setSection(activity, section, !ParentalLock.sectionLocked(activity, section));
                changed.run();
                sectionLocks(changed);
            })
            .show();
    }

    void groups() {
        activity.async(
            () -> guide.channelsDb().groups(),
            groups -> {
                String[] names = new String[groups.size()];
                for (int i = 0; i < names.length; i++) names[i] =
                    (groups.get(i).name.isEmpty() ? "Ungrouped" : groups.get(i).name) +
                    " · " +
                    groups.get(i).count;
                SidePanel.of(activity, "Channel groups")
                    .choices(names, -1, which -> guide.show("group:" + groups.get(which).name, ""))
                    .show();
            }
        );
    }

    void searchPanel() {
        LinearLayout form = activity.vertical();
        EditText query = activity.field(form, "Channel or group", false, guide.search);
        android.app.Dialog dialog = activity.form("Search live TV", form);
        form.addView(
            activity.button("Search", () -> {
                dialog.dismiss();
                guide.show(
                    guide.filter.equals("favourites") ? "favourites" : "all",
                    query.getText().toString().trim()
                );
            })
        );
    }
}
