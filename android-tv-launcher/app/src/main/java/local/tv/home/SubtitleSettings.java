package local.tv.home;

import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings → Playback: subtitle size and background ({@link SubtitleStyle}). */
final class SubtitleSettings {

    private SubtitleSettings() {}

    static void cards(SettingsScreens settings, MainActivity activity, LinearLayout body) {
        TextView[] size = new TextView[1];
        size[0] = settings.settingCard(
            body,
            "Subtitle size",
            "Text size for films, series and Live TV; applies to the next video",
            SubtitleStyle.name(activity.prefs.getInt(SubtitleStyle.SIZE, SubtitleStyle.NORMAL)),
            false,
            "Change",
            () ->
                SidePanel.of(activity, "Subtitle size")
                    .choices(
                        SubtitleStyle.NAMES,
                        SubtitleStyle.index(activity.prefs.getInt(SubtitleStyle.SIZE, SubtitleStyle.NORMAL)),
                        which -> {
                            activity.prefs
                                .edit()
                                .putInt(SubtitleStyle.SIZE, SubtitleStyle.PERCENT[which])
                                .apply();
                            size[0].setText(SubtitleStyle.NAMES[which]);
                        }
                    )
                    .show()
        );
        // On/off like every other switch in Settings.
        settings.toggle(
            body,
            "Subtitle background",
            "A dark box behind subtitles, easier to read over bright pictures",
            SubtitleStyle.BACKGROUND,
            false
        );
    }
}
