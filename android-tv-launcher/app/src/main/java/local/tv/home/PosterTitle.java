package local.tv.home;

import android.content.Context;
import android.text.*;
import android.view.Gravity;
import android.widget.TextView;

/** Two readable lines at rest, full horizontal marquee only for an overflowing focused title. */
final class PosterTitle extends TextView {
    private boolean cardFocused;
    PosterTitle(Context context){super(context);setTextSize(15);setTextColor(0xfff2f4f8);setIncludeFontPadding(false);setGravity(Gravity.TOP);setPadding(0,0,0,0);rest();}
    private void rest(){setSelected(false);setSingleLine(false);setMaxLines(2);setEllipsize(TextUtils.TruncateAt.END);setHorizontallyScrolling(false);}
    void cardFocus(boolean focused){cardFocused=focused;rest();if(focused)post(this::scrollIfNeeded);}
    private void scrollIfNeeded(){if(!cardFocused||getWidth()<=0)return;StaticLayout layout=StaticLayout.Builder.obtain(getText(),0,length(),getPaint(),getWidth()).setIncludePad(false).build();if(layout.getLineCount()>2){setSingleLine(true);setEllipsize(TextUtils.TruncateAt.MARQUEE);setMarqueeRepeatLimit(-1);setHorizontalFadingEdgeEnabled(true);setSelected(true);}}
}
