package local.tv.home;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TvNavigationTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();private MainActivity activity;private boolean autoUpdates;
    private View text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=text(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private void select(String label){instrumentation.runOnMainSync(()->{View view=text(activity.getWindow().getDecorView(),label);assertNotNull(label,view);while(!view.hasOnClickListeners()&&view.getParent() instanceof View)view=(View)view.getParent();assertTrue(label,view.performClick());});instrumentation.waitForIdleSync();}
    private void screenshot(String name) throws Exception{SystemClock.sleep(500);Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);File directory=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());try(OutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();}
    @Test public void tvScreensNavigateAndKeepAVisibleFocusTarget() throws Exception{
        Context context=instrumentation.getTargetContext();autoUpdates=context.getSharedPreferences("ui",0).getBoolean("autoUpdates",true);context.getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",false).commit();activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
        screenshot("home");instrumentation.runOnMainSync(()->assertNotNull(activity.getCurrentFocus()));
        select("Movies");screenshot("movies");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Settings");select("Playback");screenshot("settings-playback");select("Accounts");screenshot("settings-accounts");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Live TV");SystemClock.sleep(1000);screenshot("live-tv");
    }
    @After public void cleanup(){if(activity!=null)instrumentation.runOnMainSync(activity::finish);instrumentation.getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",autoUpdates).commit();}
}
