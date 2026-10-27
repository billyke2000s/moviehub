package local.tv.home;

import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.*;
import java.util.function.Consumer;

/** One direct, independently selectable row per link. Metadata refresh never recreates a row. */
final class FreeVodRows {

    private final MainActivity activity;
    private final LinearLayout target;
    private final Consumer<SourceRepository.Source> select;
    private final Map<String, Button> displayed = new LinkedHashMap<>();
    private final Map<String, Integer> numbers = new HashMap<>();
    private int serial;

    FreeVodRows(MainActivity activity, LinearLayout target, Consumer<SourceRepository.Source> select) {
        this.activity = activity;
        this.target = target;
        this.select = select;
    }

    void render(List<SourceRepository.Source> sources) {
        Set<String> present = new HashSet<>();
        for (SourceRepository.Source source : sources) {
            String key = VodSearch.sourceKey(source);
            present.add(key);
            Button row = displayed.get(key);
            if (row == null) {
                row = activity.button("", () -> select.accept(source));
                row.setSingleLine(false);
                row.setMaxLines(3);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                displayed.put(key, row);
                numbers.put(key, ++serial);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
                layout.bottomMargin = activity.dp(8);
                target.addView(row, layout);
            }
            String website = source.site.isEmpty() ? SourcesScreen.provider(source.title) : source.site;
            String label = source.facts.value.listLabel(website) + " · Link " + numbers.get(key);
            if (!label.contentEquals(row.getText())) row.setText(label);
        }
        boolean refocus = false;
        Iterator<Map.Entry<String, Button>> rows = displayed.entrySet().iterator();
        while (rows.hasNext()) {
            Map.Entry<String, Button> row = rows.next();
            if (present.contains(row.getKey())) continue;
            refocus |= row.getValue().hasFocus();
            target.removeView(row.getValue());
            numbers.remove(row.getKey());
            rows.remove();
        }
        if (refocus && !displayed.isEmpty()) displayed.values().iterator().next().requestFocus();
    }

    Button row(SourceRepository.Source source) {
        return displayed.get(VodSearch.sourceKey(source));
    }
}
