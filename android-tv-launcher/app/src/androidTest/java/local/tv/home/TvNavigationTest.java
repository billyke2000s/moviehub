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
    private void screenshot(String name) throws Exception{SystemClock.sleep(500);Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);File directory=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());try(OutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();shell("mkdir -p /sdcard/Download/moviehub-test-screenshots");shell("cp "+new File(directory,name+".png").getAbsolutePath()+" /sdcard/Download/moviehub-test-screenshots/"+name+".png");}
    private void shell(String command) throws Exception{
        try(android.os.ParcelFileDescriptor descriptor=instrumentation.getUiAutomation().executeShellCommand(command);InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);assertEquals("Screenshot export failed", "",output.toString("UTF-8").trim());}
    }
    @Test public void tvScreensNavigateAndKeepAVisibleFocusTarget() throws Exception{
        Context context=instrumentation.getTargetContext();ChannelDatabase db=ChannelDatabase.get(context);ChannelDatabase.Writer writer=db.writer("navigation-fixture",()->{},n->{});try{for(int i=1;i<=12;i++)writer.accept(new Playlist.Channel("Fixture channel "+i,"https://example.test/live/"+i+".ts",i%2==0?"Sports":"News","",""));writer.commit();}finally{writer.abort();}autoUpdates=context.getSharedPreferences("ui",0).getBoolean("autoUpdates",true);context.getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",false).commit();activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
        screenshot("home");instrumentation.runOnMainSync(()->assertNotNull(activity.getCurrentFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->assertNotNull("Remote opens Movies",text(activity.getWindow().getDecorView(),"Browse & search")));screenshot("movies");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Settings");select("Playback");screenshot("settings-playback");select("Accounts");screenshot("settings-accounts");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Live TV");SystemClock.sleep(1000);screenshot("live-tv");
    }
    @After public void cleanup(){if(activity!=null)instrumentation.runOnMainSync(activity::finish);instrumentation.getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",autoUpdates).commit();ChannelDatabase.get(instrumentation.getTargetContext()).clear();}
}
