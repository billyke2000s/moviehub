package local.tv.home;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PosterFeedTest {
    final Instrumentation remote=InstrumentationRegistry.getInstrumentation();MainActivity activity;boolean autoUpdates;PosterFeed feed;CountDownLatch release=new CountDownLatch(1);
    interface Check {boolean ok();}
    void await(Check check) throws Exception {long end=SystemClock.uptimeMillis()+12000;while(SystemClock.uptimeMillis()<end){AtomicBoolean ok=new AtomicBoolean();remote.runOnMainSync(()->ok.set(check.ok()));if(ok.get())return;SystemClock.sleep(80);}fail("Timed out waiting for poster feed");}
    @Before public void launch(){Context context=remote.getTargetContext();autoUpdates=context.getSharedPreferences("ui",0).getBoolean("autoUpdates",true);context.getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",false).commit();activity=(MainActivity)remote.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));remote.waitForIdleSync();}
    @After public void cleanup(){release.countDown();remote.runOnMainSync(()->{if(feed!=null)feed.close();activity.finish();});remote.getTargetContext().getSharedPreferences("ui",0).edit().putBoolean("autoUpdates",autoUpdates).commit();}
    JSONObject page(int number,int size,int pages,String type) throws Exception {JSONArray items=new JSONArray();for(int i=0;i<size;i++)items.put(new JSONObject().put("id",(number-1)*20+i+1).put("media_type",type).put("title",i==0?"An extraordinarily long movie and series title that needs to scroll so every word can be read on a television screen":"Poster "+((number-1)*20+i+1)));return new JSONObject().put("results",items).put("total_pages",pages);}
    View label(View node,String value){if(node instanceof TextView&&value.contentEquals(((TextView)node).getText()))return node;if(node instanceof ViewGroup){ViewGroup parent=(ViewGroup)node;for(int i=0;i<parent.getChildCount();i++){View found=label(parent.getChildAt(i),value);if(found!=null)return found;}}return null;}
    void key(int key){remote.sendKeyDownUpSync(key);remote.waitForIdleSync();}
    LinearLayout mount(String kind,PosterFeed.Loader loader) throws Exception {return mount(kind,loader,true);}
    LinearLayout mount(String kind,PosterFeed.Loader loader,boolean wait) throws Exception {
        AtomicReference<LinearLayout> navigation=new AtomicReference<>();remote.runOnMainSync(()->{try{
            Method browse=MainActivity.class.getDeclaredMethod("browse",String.class,String.class,String.class,int.class);browse.setAccessible(true);browse.invoke(activity,kind,"Top rated",kind.equals("Movies")?"movie/top_rated":"tv/top_rated",1);
            Field page=MainActivity.class.getDeclaredField("page");page.setAccessible(true);LinearLayout root=(LinearLayout)page.get(activity);LinearLayout body=(LinearLayout)root.getChildAt(1),right=(LinearLayout)body.getChildAt(1);LinearLayout menu=(LinearLayout)((ScrollView)body.getChildAt(0)).getChildAt(0);navigation.set(menu);feed=activity.catalogueFeed(right,right.getChildAt(1),menu,kind.equals("Movies")?"movie":"tv",loader);
        }catch(Exception e){throw new AssertionError(e);}});if(wait)await(()->feed.items.size()>=20&&feed.getLayoutManager().findViewByPosition(0)!=null);return navigation.get();
    }
    @Test public void categoriesRouteRightToNearestPosterForMoviesAndSeries() throws Exception {
        for(String kind:new String[]{"Movies","Series"}){LinearLayout menu=mount(kind,n->page(n,20,1,kind.equals("Movies")?"movie":"tv"));View category=label(menu,"Top rated");remote.runOnMainSync(()->category.requestFocus());key(KeyEvent.KEYCODE_DPAD_RIGHT);
            remote.runOnMainSync(()->{View focused=activity.getCurrentFocus();assertNotNull(focused);assertSame("Right enters a poster, not the Back button",feed,focused.getParent());Rect source=new Rect();category.getGlobalVisibleRect(source);Rect chosen=new Rect();focused.getGlobalVisibleRect(chosen);for(int i=0;i<feed.getChildCount();i++){View card=feed.getChildAt(i);if(feed.getChildAdapterPosition(card)>=feed.items.size())continue;Rect bounds=new Rect();card.getGlobalVisibleRect(bounds);assertTrue("Poster is nearest to category height",Math.abs(chosen.centerY()-source.centerY())<=Math.abs(bounds.centerY()-source.centerY()));}});
        }
        screenshot("poster-category-focus");
    }
    @Test public void rightDuringInitialLoadingEntersPostersWhenReady() throws Exception {
        LinearLayout menu=mount("Movies",n->{assertTrue(release.await(15,TimeUnit.SECONDS));return page(n,20,1,"movie");},false);View category=label(menu,"Top rated");remote.runOnMainSync(()->category.requestFocus());key(KeyEvent.KEYCODE_DPAD_RIGHT);remote.runOnMainSync(()->assertSame(category,activity.getCurrentFocus()));release.countDown();await(()->activity.getCurrentFocus()!=null&&activity.getCurrentFocus().getParent()==feed);
    }
    @Test public void delayedPagesAppendWithoutMovingRemoteFocusOrScroll() throws Exception {
        AtomicInteger requested=new AtomicInteger();mount("Movies",n->{requested.set(n);if(n==2)assertTrue(release.await(15,TimeUnit.SECONDS));return page(n,n==3?7:20,3,"movie");});
        remote.runOnMainSync(()->feed.getLayoutManager().findViewByPosition(0).requestFocus());
        for(int i=0;i<4;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);await(()->requested.get()==2);
        AtomicReference<View> focused=new AtomicReference<>();AtomicInteger offset=new AtomicInteger();remote.runOnMainSync(()->{focused.set(activity.getCurrentFocus());offset.set(focused.get().getTop());assertSame(feed,focused.get().getParent());});release.countDown();await(()->feed.items.size()==40);
        remote.runOnMainSync(()->{assertSame("Append preserves the actual focused card",focused.get(),activity.getCurrentFocus());assertEquals("Append preserves scroll position",offset.get(),focused.get().getTop());assertNull(label(feed,"Next"));});
        for(int i=0;i<12;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);await(()->feed.items.size()==47);for(int i=0;i<8;i++)key(KeyEvent.KEYCODE_DPAD_DOWN);
        await(()->label(feed,"End of list")!=null);remote.runOnMainSync(()->assertSame(feed,activity.getCurrentFocus().getParent()));screenshot("poster-end-of-list");
    }
    @Test public void homeShelfContinuesBeyondTwentyAndDockHeadingStaysAboveTiles() throws Exception {
        AtomicReference<Object> shelf=new AtomicReference<>();remote.runOnMainSync(()->{try{Field f=MainActivity.class.getDeclaredField("homeShelf");f.setAccessible(true);Object value=f.get(activity);shelf.set(value);Method load=value.getClass().getDeclaredMethod("load",String.class,PosterFeed.Loader.class);load.setAccessible(true);load.invoke(value,"",(PosterFeed.Loader)n->{if(n==2)assertTrue(release.await(15,TimeUnit.SECONDS));return page(n,20,2,"movie");});Field field=value.getClass().getDeclaredField("feed");field.setAccessible(true);feed=(PosterFeed)field.get(value);}catch(Exception e){throw new AssertionError(e);}});await(()->feed.items.size()==20&&feed.getLayoutManager().findViewByPosition(0)!=null);
        remote.runOnMainSync(()->feed.getLayoutManager().findViewByPosition(0).requestFocus());for(int i=0;i<16;i++)key(KeyEvent.KEYCODE_DPAD_RIGHT);
        AtomicReference<View> focus=new AtomicReference<>();AtomicInteger offset=new AtomicInteger();remote.runOnMainSync(()->{focus.set(activity.getCurrentFocus());offset.set(feed.computeHorizontalScrollOffset());});release.countDown();await(()->feed.items.size()==40);
        remote.runOnMainSync(()->{assertSame(focus.get(),activity.getCurrentFocus());assertEquals(offset.get(),feed.computeHorizontalScrollOffset());});for(int i=0;i<30;i++)key(KeyEvent.KEYCODE_DPAD_RIGHT);await(()->label(feed,"End of list")!=null);
        remote.runOnMainSync(()->{View heading=label(activity.getWindow().getDecorView(),"Your apps"),apps=label(activity.getWindow().getDecorView(),"All Apps");apps.requestFocus();Rect title=new Rect(),tile=new Rect();heading.getGlobalVisibleRect(title);apps.getGlobalVisibleRect(tile);assertTrue(title.bottom<=tile.top);assertTrue(((View)heading.getParent()).getElevation()>0);});screenshot("home-continuous-shelf-and-dock");
    }
    @Test public void longTitlesUseTwoLinesThenMarqueeOnlyWhileFocused() throws Exception {
        mount("Series",n->page(n,20,1,"tv"));AtomicReference<PosterTitle> title=new AtomicReference<>();remote.runOnMainSync(()->{LinearLayout card=(LinearLayout)feed.getLayoutManager().findViewByPosition(0);PosterTitle text=(PosterTitle)card.getChildAt(1);title.set(text);assertEquals(2,text.getMaxLines());assertEquals(0,text.getPaddingTop());card.requestFocus();});await(()->title.get().isSelected());remote.runOnMainSync(()->{assertNull("Full focused title is not ellipsized",title.get().getEllipsize());assertEquals(1,title.get().getMaxLines());});SystemClock.sleep(3500);Bitmap first=titleCrop(title.get());SystemClock.sleep(600);Bitmap second=titleCrop(title.get());assertFalse("Focused marquee must actually move",first.sameAs(second));first.recycle();second.recycle();screenshot("poster-focused-title");key(KeyEvent.KEYCODE_DPAD_RIGHT);remote.runOnMainSync(()->{assertFalse(title.get().isSelected());assertEquals(2,title.get().getMaxLines());});
    }
    @Test public void failedPageRetriesSameNumberAndClosedFeedIgnoresLateResponse() throws Exception {
        AtomicInteger attempts=new AtomicInteger();mount("Movies",n->{if(n==2&&attempts.incrementAndGet()==1)throw new IOException("fixture outage");return page(n,20,2,"movie");});remote.runOnMainSync(()->feed.loadMore());await(()->attempts.get()==1);SystemClock.sleep(200);remote.runOnMainSync(()->feed.loadMore());await(()->feed.items.size()==40);assertEquals(2,attempts.get());
        mount("Series",n->{if(n==2)assertTrue(release.await(15,TimeUnit.SECONDS));return page(n,20,2,"tv");});PosterFeed old=feed;remote.runOnMainSync(()->{old.loadMore();old.close();});release.countDown();SystemClock.sleep(300);remote.runOnMainSync(()->assertEquals(20,old.items.size()));
    }
    Bitmap titleCrop(PosterTitle title){Rect bounds=new Rect();remote.runOnMainSync(()->title.getGlobalVisibleRect(bounds));Bitmap screen=remote.getUiAutomation().takeScreenshot();assertNotNull(screen);Bitmap crop=Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height());screen.recycle();return crop;}
    void screenshot(String name) throws Exception {Bitmap image=remote.getUiAutomation().takeScreenshot();assertNotNull(image);File directory=new File(remote.getTargetContext().getExternalFilesDir(null),"screenshots");assertTrue(directory.isDirectory()||directory.mkdirs());File file=new File(directory,name+".png");try(OutputStream output=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}image.recycle();for(String command:new String[]{"mkdir -p /sdcard/Download/moviehub-test-screenshots","cp "+file.getAbsolutePath()+" /sdcard/Download/moviehub-test-screenshots/"+name+".png"})try(android.os.ParcelFileDescriptor fd=remote.getUiAutomation().executeShellCommand(command);InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream output=new ByteArrayOutputStream()){int b;while((b=in.read())!=-1)output.write(b);assertEquals("Screenshot export failed","",output.toString("UTF-8").trim());}}
}
