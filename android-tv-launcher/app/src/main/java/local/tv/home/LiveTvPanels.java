package local.tv.home;

import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Live TV guide's side panels: options, sections and groups, search, programme information
 * with reminders, and colour-key set-up.
 */
final class LiveTvPanels {

    private final MainActivity activity;
    private final LiveTvScreen guide;

    LiveTvPanels(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /**
     * The guide's own options: what has no other home. Search, Reminders and Favourites are on the
     * colour buttons. A day choice is shown only when it would change something.
     */
    void options() {
        boolean picture = activity.prefs.getBoolean(LiveTvScreen.PICTURE, true);
        SidePanel panel = SidePanel.of(activity, "Live TV");
        if (guide.windowOffset < LiveTvScreen.MAX_OFFSET) panel.option("Next day (+24 hours)", () ->
            guide.nextDay(guide.focusedRow())
        );
        if (guide.windowOffset > 0) panel.option("Previous day (\u221224 hours)", () ->
            guide.previousDay(guide.focusedRow())
        );
        panel
            .option(picture ? "Picture in the guide · On" : "Picture in the guide · Off", () -> {
                activity.prefs.edit().putBoolean(LiveTvScreen.PICTURE, !picture).apply();
                guide.startPicture();
            })
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
