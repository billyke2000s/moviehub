package local.tv.home;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.widget.EditText;
import android.widget.ScrollView;
import org.json.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
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
        screenshot("home");instrumentation.runOnMainSync(()->{assertNotNull(activity.getCurrentFocus());assertFullyVisible("All Apps");assertFullyVisible("Settings");assertFullyVisible("For you");assertFullyVisible("My list");});
        instrumentation.runOnMainSync(()->{try{Field field=MainActivity.class.getDeclaredField("homeShelf");field.setAccessible(true);Object shelf=field.get(activity);Method show=shelf.getClass().getDeclaredMethod("show",JSONArray.class,String.class);show.setAccessible(true);JSONArray titles=new JSONArray();for(int i=0;i<12;i++)titles.put(new JSONObject().put("id",550+i).put("media_type","movie").put("title",i==0?"Fight Club":"Artwork fixture "+i).put("overview","An insomniac office worker and a soap maker form an underground fight club.").put("poster_path","/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg").put("backdrop_path","/hZkgoQYus5vegHoetLkCJzb17zJ.jpg"));show.invoke(shelf,titles,"");}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();SystemClock.sleep(3000);screenshot("home-artwork");instrumentation.runOnMainSync(()->{assertFullyVisible("All Apps");assertFullyVisible("View details");});
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->assertNotNull("Remote opens Movies",text(activity.getWindow().getDecorView(),"Trending this week")));instrumentation.runOnMainSync(()->{for(String category:new String[]{"Search","Trending today","Trending this week","Popular","Top rated","Now playing","Upcoming","Genres","More filters","My library"})assertFullyVisible(category);});screenshot("movies");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Settings");select("Playback");screenshot("settings-playback");select("Accounts");screenshot("settings-accounts");instrumentation.runOnMainSync(()->{assertEquals("Account overview must not expose a wall of login fields",0,countInputs(activity.getWindow().getDecorView()));assertFullyVisible("Xtream");assertFullyVisible("Premiumize");});instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        select("Live TV");SystemClock.sleep(1000);screenshot("live-tv");
    }
    private int countInputs(View view){int count=view instanceof EditText?1:0;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=countInputs(group.getChildAt(i));}return count;}
    private void assertFullyVisible(String label){View view=text(activity.getWindow().getDecorView(),label);assertNotNull(label,view);Rect visible=new Rect();assertTrue(label+" visible",view.getGlobalVisibleRect(visible));assertEquals(label+" is vertically clipped",view.getHeight(),visible.height());assertEquals(label+" is horizontally clipped",view.getWidth(),visible.width());}
    @Test public void detailsAndConnectionFormsFitAndKeepDistinctPurposes() throws Exception{
        Context context=instrumentation.getTargetContext();autoUpdates=context.getSharedPreferences("ui",0).getBoolean("autoUpdates",true);context.getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",false).commit();activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{try{Method method=MainActivity.class.getDeclaredMethod("accountScreen",String.class);method.setAccessible(true);method.invoke(activity,"TMDB");}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->{assertEquals(1,countInputs(activity.getWindow().getDecorView()));assertFullyVisible("API Key (v3 auth)");});screenshot("connect-tmdb");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{try{Method screen=MainActivity.class.getDeclaredMethod("fixedScreen",String.class,Runnable.class);screen.setAccessible(true);screen.invoke(activity,"Details",(Runnable)()->{});JSONObject fixture=new JSONObject().put("id",100).put("media_type","tv").put("name","A series with a longer title").put("first_air_date","2026-01-01").put("vote_average",8.1).put("overview","A detailed synopsis that needs room to breathe on a television screen. "+new String(new char[20]).replace("\0","The story follows a family on an unexpected journey. ")).put("genres",new JSONArray().put(new JSONObject().put("name","Drama"))).put("credits",new JSONObject().put("cast",new JSONArray().put(new JSONObject().put("name","Actor One"))));Method details=MainActivity.class.getDeclaredMethod("renderDetails",JSONObject.class);details.setAccessible(true);details.invoke(activity,fixture);}catch(Exception e){throw new AssertionError(e);}});instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->{assertFullyVisible("Browse episodes");assertFullyVisible("Full synopsis");assertFullyVisible("Cast · Actor One");});screenshot("details");
    }
    @After public void cleanup(){if(activity!=null)instrumentation.runOnMainSync(activity::finish);instrumentation.getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",autoUpdates).commit();ChannelDatabase.get(instrumentation.getTargetContext()).clear();}
}
