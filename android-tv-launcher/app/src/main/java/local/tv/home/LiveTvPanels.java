package local.tv.home;

import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.Locale;

/** The Live TV guide's side panels: options, channel groups, search and colour-key set-up. */
final class LiveTvPanels {

    private final MainActivity activity;
    private final LiveTvScreen guide;

    LiveTvPanels(MainActivity activity, LiveTvScreen guide) {
        this.activity = activity;
        this.guide = guide;
    }

    /** Teaches the guide this remote's colour keys: press red, green, yellow, then blue. */
    void learnColourKeys(int step) {
        if (step >= 4) {
            activity.toast("Coloured buttons set up");
            guide.guide(guide.pageStart, 0);
            return;
        }
        String[] names = { "RED", "GREEN", "YELLOW", "BLUE" };
        android.app.Dialog panel = SidePanel.of(activity, "Press the " + names[step] + " button")
            .message(
                "Press the " +
                    names[step].toLowerCase(Locale.ROOT) +
                    " button on your remote now. Back cancels."
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
                activity.prefs.edit().putInt(LiveTvScreen.COLOUR_PREFS[step], key).apply();
                panel.dismiss();
                learnColourKeys(step + 1);
            }
            return true;
        });
    }

    /** Everything the colour keys do, for remotes without colour keys. */
    void options() {
        SidePanel.of(activity, "Live TV")
            .option("All channels", () -> guide.show("all", ""))
            .option("Favourites", () -> guide.show("favourites", ""))
            .option("Sports", () -> guide.show("sports", ""))
            .option("Groups", this::groups)
            .option("Search", this::searchPanel)
            .option("Set up coloured buttons", () -> learnColourKeys(0))
            .option("Refresh channels", () -> {
                ChannelSync.refreshNow(activity);
                guide.guide(0, 0);
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
