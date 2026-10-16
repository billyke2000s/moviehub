package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Native TV home UI based on the supplied visual reference. No WebView. */
public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<App> apps=new ArrayList<>();
    private final Artwork artworkLoader=new Artwork();
    private Secrets secrets;private MediaRepository media;private SharedPreferences prefs;
    private LinearLayout page;private Runnable back;private int generation;private TextView clock;private Button updateButton;private AppUpdates.Release availableUpdate;private long lastUpdateCheck;private boolean updateBusy;private long promptedUpdateVersion;
    private final Runnable ticker=new Runnable(){public void run(){if(clock!=null)clock.setText(new java.text.SimpleDateFormat(prefs.getBoolean("clock24",true)?"HH:mm":"h:mm a",Locale.getDefault()).format(new Date()));handler.postDelayed(this,30000);}};
    private int accent(){return prefs.getInt("accent",0xff8a9eff);}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    interface Job<T>{T run() throws Exception;}
    interface Done<T>{void accept(T result) throws Exception;}
    private <T> void async(Job<T> job,Done<T> done){int request=generation;io.execute(()->{try{T result=job.run();runOnUiThread(()->{if(isFinishing()||isDestroyed()||request!=generation)return;try{done.accept(result);}catch(Exception e){toast("Could not display this response");}});}catch(Exception e){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()&&request==generation)showRequestError();});}});}
    private void showRequestError(){
        TextView error=text("Could not load this content. Check your connection and connected accounts.",18);page.addView(error);page.addView(button("Settings",this::settings));
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private boolean http(){return prefs.getBoolean("http",false);}
    @Override public void onCreate(Bundle state){super.onCreate(state);secrets=new Secrets(this);media=new MediaRepository(secrets);prefs=getSharedPreferences("ui",MODE_PRIVATE);try{ChannelSync.schedule(this);}catch(Exception ignored){}home();}
    @Override public void onStart(){super.onStart();handler.removeCallbacks(ticker);handler.post(ticker);checkForUpdate();try{ChannelSync.refreshIfStale(this);}catch(Exception ignored){}}
    @Override public void onStop(){handler.removeCallbacks(ticker);super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);artworkLoader.close();io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(back!=null)back.run();}
    private GradientDrawable box(int colour){GradientDrawable d=new GradientDrawable();d.setColor(colour);d.setCornerRadius(dp(14));d.setStroke(dp(1),0x33ffffff);return d;}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xfff8f8fc);t.setPadding(0,dp(8),0,dp(8));return t;}
    private void focus(View v){v.setFocusable(true);v.setOnFocusChangeListener((view,focused)->{GradientDrawable d=box(focused?0xff343e62:0xff202c43);if(focused)d.setStroke(dp(3),accent());view.setBackground(d);view.setScaleX(focused?1.025f:1f);view.setScaleY(focused?1.025f:1f);});}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(0xfff8f8fc);b.setAllCaps(false);b.setTextSize(18);b.setBackground(box(0xff202c43));b.setPadding(dp(15),dp(10),dp(15),dp(10));focus(b);b.setOnClickListener(v->action.run());return b;}
    private void screen(String title,Runnable goBack){generation++;back=goBack;clock=null;updateButton=null;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff14233c,0xff080b13,0xff070a10}));page=vertical();page.setPadding(dp(32),dp(22),dp(32),dp(28));scroll.addView(page);setContentView(scroll);if(title!=null){LinearLayout head=row();head.addView(text(title,28),new LinearLayout.LayoutParams(0,-2,1));head.addView(button("Back",goBack));page.addView(head);}}
    private void addRow(LinearLayout parent,View view,int width,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(width),dp(height));p.setMargins(dp(5),dp(5),dp(8),dp(5));parent.addView(view,p);}
    private LinearLayout horizontal(LinearLayout parent){HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout r=row();r.setPadding(dp(4),dp(8),dp(4),dp(8));scroll.addView(r);parent.addView(scroll);return r;}
    private void checkForUpdate(){
        long now=android.os.SystemClock.elapsedRealtime();if(lastUpdateCheck!=0&&now-lastUpdateCheck<6*60*60*1000L)return;lastUpdateCheck=now;
        io.execute(()->{AppUpdates.Release result=null;try{result=AppUpdates.check(getApplicationContext());}catch(Exception ignored){}final AppUpdates.Release release=result;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;availableUpdate=release;if(updateButton!=null)updateButton.setVisibility(release==null?View.GONE:View.VISIBLE);if(release!=null&&prefs.getBoolean("autoUpdates",true)&&promptedUpdateVersion!=release.version){promptedUpdateVersion=release.version;offerUpdate();}});});
    }
    private void offerUpdate(){
        if(availableUpdate==null||updateBusy)return;
        if(!getPackageManager().canRequestPackageInstalls()){new AlertDialog.Builder(this).setTitle("Allow app updates").setMessage("Allow Home to install updates, then select Update again.").setPositiveButton("Open settings",(d,w)->{try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){toast("Open Android Settings to allow app installation");}}).setNegativeButton("Cancel",null).show();return;}
        final AppUpdates.Release release=availableUpdate;
        new AlertDialog.Builder(this).setTitle("Update available").setMessage("Install version "+release.name+"?").setPositiveButton("Update",(dialog,which)->{
            updateBusy=true;if(updateButton!=null){updateButton.setEnabled(false);updateButton.setText("Downloading…");}
            io.execute(()->{try{AppUpdates.download(getApplicationContext(),release);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;try{android.net.Uri uri=android.net.Uri.parse("content://"+getPackageName()+".updates/update.apk");Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);install.setClipData(ClipData.newRawUri("Update",uri));startActivity(install);}catch(Exception e){toast("Android could not open the update installer");}resetUpdateButton();});}catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;toast("Update could not be downloaded or verified. Try again later.");resetUpdateButton();});}});
        }).setNegativeButton("Later",null).show();
    }
    private void resetUpdateButton(){updateBusy=false;if(updateButton!=null){updateButton.setEnabled(true);updateButton.setText("Update");}}
    private void home(){
        screen(null,null);LinearLayout top=row();top.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);clock=text("",22);top.addView(clock);updateButton=button("Update",this::offerUpdate);updateButton.setVisibility(availableUpdate==null?View.GONE:View.VISIBLE);top.addView(updateButton);top.addView(button("Settings",this::settings));page.addView(top);ticker.run();handler.removeCallbacks(ticker);handler.postDelayed(ticker,30000);
        TextView headline=text("Your TV. Your way.",42);headline.setPadding(0,dp(20),0,dp(22));page.addView(headline);
        LinearLayout tiles=row();String[] kinds={"Live TV","Movies","Series"};String[] pictures={"https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=1000&q=80","https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=1000&q=80","https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?w=1000&q=80"};for(int i=0;i<kinds.length;i++){String kind=kinds[i];FrameLayout tile=new FrameLayout(this);tile.setBackground(box(0xff202c43));tile.setClipToOutline(true);ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(image,new FrameLayout.LayoutParams(-1,-1));View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xff070a12,0x440b1123,0x00000000}));tile.addView(shade,new FrameLayout.LayoutParams(-1,-1));TextView label=text(kind,28);label.setPadding(dp(20),dp(18),dp(20),dp(18));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);tile.addView(label,lp);focus(tile);tile.setOnClickListener(v->{if(kind.equals("Live TV"))live();else library(kind);});LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,dp(165),1);layout.setMargins(dp(4),0,dp(10),0);tiles.addView(tile,layout);loadImage(image,pictures[i]);}page.addView(tiles);
        if(prefs.getBoolean("featured",true)){page.addView(text("Featured for you",24));LinearLayout posters=horizontal(page);posters.addView(text("Connect TMDB in Settings for recommendations",14));String seedType=prefs.getString("seedType","movie");int seed=prefs.getInt("seed",0);async(()->media.featured(seedType,seed),items->{posters.removeAllViews();if(items.length()==0){posters.addView(button("Connect TMDB",this::settings));return;}for(int i=0;i<Math.min(18,items.length());i++){JSONObject item=items.getJSONObject(i);String type=item.optString("media_type");if(!type.equals("movie")&&!type.equals("tv"))continue;String title=item.optString("title",item.optString("name","Title"));LinearLayout card=vertical();card.setBackground(box(0xff202c43));card.setPadding(dp(8),dp(8),dp(8),dp(8));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new LinearLayout.LayoutParams(-1,dp(215)));TextView caption=text(title,18);caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(caption);focus(card);card.setOnClickListener(v->tmdbTitle(item));addRow(posters,card,170,300);String artwork=item.optString("poster_path");if(artwork.startsWith("/"))loadImage(image,"https://image.tmdb.org/t/p/w342"+artwork);}});}
        page.addView(text("Apps",24));LinearLayout dock=horizontal(page);queryApps();Set<String> pinned=prefs.getStringSet("pinned",null);if(pinned==null){pinned=new LinkedHashSet<>();for(int i=0;i<Math.min(6,apps.size());i++)pinned.add(apps.get(i).component.flattenToString());prefs.edit().putStringSet("pinned",pinned).apply();}for(App app:apps)if(pinned.contains(app.component.flattenToString()))addApp(dock,app,false);addRow(dock,button("All Apps",this::allApps),140,114);
        page.post(()->tiles.getChildAt(0).requestFocus());
    }
    private void loadImage(ImageView view,String url){artworkLoader.load(view,url);}
    private void tmdbTitle(JSONObject item){
        String type=item.optString("media_type",item.has("title")?"movie":"tv");int tmdbId=item.optInt("id");
        screen("Details",()->library(type.equals("tv")?"Series":"Movies"));
        TextView loading=text("Loading details…",18);page.addView(loading);
        async(()->media.tmdb(type+"/"+tmdbId+"?append_to_response=external_ids,credits"),details->{details.put("media_type",type);page.removeView(loading);renderDetails(details);});
    }
    private void renderDetails(JSONObject item) throws Exception{
        String title=item.optString("title",item.optString("name","Title")),type=item.optString("media_type","movie");int tmdbId=item.optInt("id");String kind=type.equals("tv")?"Series":"Movies";
        FrameLayout hero=new FrameLayout(this);ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);hero.addView(image,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0xff080b13,0x55101a2a,0x11000000}));hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        TextView heading=text(title,34);heading.setPadding(dp(20),dp(10),dp(20),dp(12));hero.addView(heading,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));page.addView(hero,new LinearLayout.LayoutParams(-1,dp(240)));
        String artwork=item.optString("backdrop_path");if(artwork.startsWith("/"))loadImage(image,"https://image.tmdb.org/t/p/w1280"+artwork);
        LinearLayout actions=row();actions.addView(button(type.equals("tv")?"Browse episodes":"Find sources",()->{prefs.edit().putInt("seed",tmdbId).putString("seedType",type).apply();if(type.equals("tv"))seasons(tmdbId,title);else{JSONObject external=item.optJSONObject("external_ids");String imdb=external==null?item.optString("imdb_id"):external.optString("imdb_id",item.optString("imdb_id"));torrentSources(imdb,-1,-1,title,kind);}}));actions.addView(button("More like this",()->catalogue(type,type+"/"+tmdbId+"/recommendations",1)));page.addView(actions);
        String date=item.optString("release_date",item.optString("first_air_date",""));int runtime=item.optInt("runtime");String metadata=(date.length()>=4?date.substring(0,4):"Unreleased")+"   ★ "+String.format(Locale.ROOT,"%.1f",item.optDouble("vote_average"))+ (runtime>0?"   "+runtime+" min":"");page.addView(text(metadata,16));
        JSONArray genres=item.optJSONArray("genres");List<String> names=new ArrayList<>();if(genres!=null)for(int i=0;i<genres.length();i++)names.add(genres.getJSONObject(i).optString("name"));if(!names.isEmpty())page.addView(text(String.join(" · ",names),16));
        page.addView(text(item.optString("overview","No synopsis available."),18));JSONObject credits=item.optJSONObject("credits");if(credits!=null){JSONArray cast=credits.optJSONArray("cast");List<String> actors=new ArrayList<>();if(cast!=null)for(int i=0;i<Math.min(6,cast.length());i++)actors.add(cast.getJSONObject(i).optString("name"));if(!actors.isEmpty())page.addView(text("Cast · "+String.join(" · ",actors),15));}
    }
    private void posterGrid(LinearLayout target,JSONArray items,String type) throws Exception{
        int visible=0;LinearLayout posterRow=null;
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);if(item.optBoolean("adult",false))continue;item.put("media_type",type);
            if(visible%5==0){posterRow=row();target.addView(posterRow);}LinearLayout card=vertical();card.setBackground(box(0xff182337));card.setPadding(dp(6),dp(6),dp(6),dp(6));card.setClipToOutline(true);
            ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(poster,new LinearLayout.LayoutParams(-1,dp(205)));
            String name=item.optString("title",item.optString("name","Title"));TextView label=text(name,15);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(label);card.setContentDescription(name);focus(card);card.setOnClickListener(v->tmdbTitle(item));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(275),1);lp.setMargins(dp(4),dp(5),dp(4),dp(5));posterRow.addView(card,lp);String path=item.optString("poster_path");if(path.startsWith("/"))loadImage(poster,"https://image.tmdb.org/t/p/w342"+path);visible++;
        }
        if(posterRow!=null)while(posterRow.getChildCount()<5)posterRow.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        if(visible==0)target.addView(text("No titles found",18));
    }
    private static class App{String label;ComponentName component;Drawable icon;App(String label,ComponentName component,Drawable icon){this.label=label;this.component=component;this.icon=icon;}}
    private void queryApps(){apps.clear();Set<ComponentName> seen=new HashSet<>();for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER})for(ResolveInfo r:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category),0)){ActivityInfo a=r.activityInfo;if(!a.enabled||!a.exported||a.packageName.equals(getPackageName()))continue;ComponentName component=new ComponentName(a.packageName,a.name);if(seen.add(component))apps.add(new App(r.loadLabel(getPackageManager()).toString(),component,r.loadIcon(getPackageManager())));}apps.sort(Comparator.comparing(a->a.label.toLowerCase(Locale.ROOT)));}
    private void addApp(LinearLayout into,App app,boolean pinControls){LinearLayout cell=vertical();cell.setGravity(Gravity.CENTER);ImageView image=new ImageView(this);image.setImageDrawable(app.icon);cell.addView(image,new LinearLayout.LayoutParams(dp(52),dp(52)));TextView label=text(app.label,16);label.setMaxLines(2);label.setGravity(Gravity.CENTER);cell.addView(label);focus(cell);cell.setBackground(box(0xff202c43));cell.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component));}catch(Exception e){toast("App is unavailable");}});cell.setOnLongClickListener(v->{pin(app);return true;});cell.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_MENU&&event.getAction()==KeyEvent.ACTION_UP){pin(app);return true;}return false;});addRow(into,cell,130,114);if(pinControls)addRow(into,button("Pin / unpin",()->pin(app)),130,70);}
    private void pin(App app){Set<String> pins=new HashSet<>(prefs.getStringSet("pinned",Collections.emptySet()));String id=app.component.flattenToString();boolean added=pins.add(id);if(!added)pins.remove(id);prefs.edit().putStringSet("pinned",pins).apply();toast(added?"Added to dock":"Removed from dock");}
    private void allApps(){screen("All Apps",this::home);page.addView(text("Hold Select or press Menu to pin or unpin an app",14));queryApps();LinearLayout r=null;for(int i=0;i<apps.size();i++){if(i%6==0){r=row();page.addView(r);}addApp(r,apps.get(i),false);}if(apps.isEmpty())page.addView(text("No launchable apps found",16));}
    private ChannelDatabase channelsDb(){return ChannelDatabase.get(this);}
    interface ChannelPage{List<ChannelDatabase.Row> load(int offset) throws Exception;}
    private Set<String> favouriteKeys(){return new HashSet<>(prefs.getStringSet("favourites",Collections.emptySet()));}
    private void live(){channelBrowser("all","",0);}
    private void channelBrowser(String filter,String search,int offset){
        screen("Live TV",this::home);
        LinearLayout filters=row();
        for(String option:new String[]{"all","sports","favourites"})filters.addView(button(option.substring(0,1).toUpperCase(Locale.ROOT)+option.substring(1),()->channelBrowser(option,search,0)),new LinearLayout.LayoutParams(0,dp(56),1));
        filters.addView(button("Accounts",this::settings));page.addView(filters);
        LinearLayout searchRow=row();LinearLayout input=vertical();EditText query=field(input,"Search channels or groups",false,search);searchRow.addView(input,new LinearLayout.LayoutParams(0,-2,1));searchRow.addView(button("Search",()->channelBrowser(filter,query.getText().toString().trim(),0)));page.addView(searchRow);
        TextView status=text("Loading saved channels…",16);page.addView(status);LinearLayout results=vertical();page.addView(results);
        async(()->{
            ChannelDatabase db=channelsDb();
            return db.browse(filter,search,favouriteKeys(),offset);
        },items->{
            ChannelImporter.Status sync=ChannelImporter.status(this);
            status.setText(sync.running?String.format(Locale.getDefault(),"Updating · %,d channels imported",sync.progress):sync.queued?"Waiting for network to refresh":sync.message.isEmpty()?"Saved channels":"Saved channels · "+sync.message);
            int count=Math.min(items.size(),ChannelDatabase.PAGE);LinearLayout gridRow=null;
            for(int i=0;i<count;i++){
                ChannelDatabase.Row channel=items.get(i);if(i%3==0){gridRow=row();results.addView(gridRow);}
                boolean favourite=favouriteKeys().contains(channel.key);
                LinearLayout card=vertical();card.setPadding(dp(16),dp(12),dp(16),dp(12));card.setBackground(box(0xff182337));
                TextView name=text((favourite?"★  ":"")+channel.name,18);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
                TextView group=text(channel.group.isEmpty()?"Live TV":channel.group,13);group.setMaxLines(1);card.addView(group);focus(card);
                card.setOnClickListener(v->async(()->channelsDb().url(channel.id,channel.key),url->play(channel.name,url,channel.key,true)));
                card.setOnLongClickListener(v->{Set<String> keys=favouriteKeys();boolean added=keys.add(channel.key);if(!added)keys.remove(channel.key);prefs.edit().putStringSet("favourites",keys).apply();name.setText((added?"★  ":"")+channel.name);toast(added?"Added to favourites":"Removed from favourites");return true;});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(105),1);lp.setMargins(dp(4),dp(4),dp(4),dp(4));gridRow.addView(card,lp);
            }
            if(count==0)results.addView(text("No matching channels. Connect an account or refresh your provider.",18));
            LinearLayout navigation=row();if(offset>0)navigation.addView(button("Previous page",()->channelBrowser(filter,search,Math.max(0,offset-ChannelDatabase.PAGE))));
            if(items.size()>ChannelDatabase.PAGE)navigation.addView(button("Next page",()->channelBrowser(filter,search,offset+ChannelDatabase.PAGE)));
            navigation.addView(button("Refresh providers",()->{ChannelSync.refreshNow(this);channelBrowser(filter,search,0);}));page.addView(navigation);
            page.addView(text("Hold Select on a channel to save or remove a favourite",13));
            int request=generation;if(sync.running||sync.queued)handler.postDelayed(new Runnable(){public void run(){
                if(isFinishing()||isDestroyed()||generation!=request)return;
                ChannelImporter.Status latest=ChannelImporter.status(MainActivity.this);
                if(latest.running||latest.queued){status.setText(latest.running?String.format(Locale.getDefault(),"Updating · %,d channels imported",latest.progress):"Waiting for network to refresh");handler.postDelayed(this,2000);}
                else{status.setText(latest.message);page.addView(button("Reload channels",()->channelBrowser(filter,search,0)));}
            }},2000);
        });
    }
    private String id(String url){return ChannelQuery.key(url);}
    private void play(String title,String url,String id,boolean live){try{String safe=Playlist.networkUrl(url,http());startActivity(new Intent(this,VlcPlaybackActivity.class).putExtra("title",title).putExtra("url",safe).putExtra("id",id).putExtra("live",live));}catch(Exception e){toast("Use an HTTPS source, or enable HTTP sources in Settings");}}
    private void library(String kind){
        String type=kind.equals("Movies")?"movie":"tv";screen(kind,this::home);LinearLayout nav=row();
        for(String label:new String[]{"Browse & search","My library","Premiumize cloud","Transfers"})nav.addView(button(label,()->{if(label.equals("Browse & search"))catalogueMenu(kind);else if(label.equals("My library"))savedLibrary(kind);else if(label.equals("Transfers"))transfers(kind);else cloud("",kind);}));page.addView(nav);
        if(!secrets.has("tmdb")){page.addView(text("Connect TMDB for posters, details and recommendations",20));page.addView(button("Connect accounts",this::settings));return;}
        page.addView(text("Popular now",24));TextView loading=text("Loading titles…",16);page.addView(loading);LinearLayout grid=vertical();page.addView(grid);
        async(()->media.tmdb(type+"/popular").getJSONArray("results"),items->{page.removeView(loading);posterGrid(grid,items,type);});
    }
    private void savedLibrary(String kind){screen("My "+kind,()->library(kind));page.addView(button("Browse catalogue",()->catalogueMenu(kind)));page.addView(button("Premiumize transfers",()->transfers(kind)));page.addView(button("Add authorized source",()->addSource(kind,"","")));page.addView(button("Premiumize cloud",()->cloud("",kind)));try{JSONArray rows=media.library();int found=0;for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);if(!kind.equals(item.getString("kind")))continue;String title=item.getString("title"),url=item.optString("url"),key=item.getString("id");LinearLayout r=row();r.addView(button(title,()->{if("premiumize".equals(item.optString("source"))){async(()->media.premiumize("item/details?id="+Network.q(item.getString("sourceId")),secrets.get("premiumize")),detail->play(title,detail.getString("link"),key,false));}else play(title,url,key,false);}),new LinearLayout.LayoutParams(0,dp(66),1));r.addView(button("Remove",()->new AlertDialog.Builder(this).setTitle("Remove "+title+"?").setPositiveButton("Remove",(d,w)->{try{media.remove(key);library(kind);}catch(Exception e){toast("Could not remove this source");}}).setNegativeButton("Cancel",null).show()));page.addView(r);found++;}if(found==0)page.addView(text("Your library is empty",16));}catch(Exception e){toast("Library could not be read");}}
    private EditText field(LinearLayout parent,String hint,boolean password,String value){
        parent.addView(text(hint,16));EditText edit=new EditText(this);edit.setId(View.generateViewId());edit.setHint("Select to type");edit.setTextColor(0xfff8f8fc);edit.setHintTextColor(0xff99a6bc);edit.setTextSize(18);edit.setPadding(dp(14),dp(10),dp(14),dp(10));edit.setSingleLine(true);edit.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);edit.setText(value);edit.setFocusable(true);edit.setFocusableInTouchMode(true);edit.setShowSoftInputOnFocus(false);
        GradientDrawable idle=box(0xff080e1a);idle.setStroke(dp(1),0xff526079);edit.setBackground(idle);
        edit.setOnFocusChangeListener((v,focused)->{GradientDrawable bg=box(0xff080e1a);bg.setStroke(dp(focused?3:1),focused?accent():0xff526079);v.setBackground(bg);if(!focused)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);});
        Runnable keyboard=()->{edit.setShowSoftInputOnFocus(true);edit.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(edit,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);};edit.setOnClickListener(v->keyboard.run());
        edit.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getAction()==KeyEvent.ACTION_UP)keyboard.run();return true;}if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){View next=v.focusSearch(key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:View.FOCUS_DOWN);if(next!=null)next.requestFocus();}return true;}return false;});
        parent.addView(edit,new LinearLayout.LayoutParams(-1,dp(60)));return edit;
    }
    private Dialog form(String title,LinearLayout content){Dialog d=new Dialog(this);ScrollView scroll=new ScrollView(this);LinearLayout shell=vertical();shell.setPadding(dp(24),dp(20),dp(24),dp(24));shell.setBackgroundColor(0xff101827);shell.addView(text(title,24));shell.addView(content);shell.addView(button("Close",d::dismiss));scroll.addView(shell);d.setContentView(scroll);d.show();d.getWindow().setLayout(dp(500),-1);d.getWindow().setGravity(Gravity.RIGHT);d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);return d;}
    private void addSource(String kind,String name,String link){LinearLayout content=vertical();EditText title=field(content,"Title",false,name),url=field(content,"Authorized media URL",false,link);CheckBox permission=new CheckBox(this);permission.setText("I have permission to play this source");permission.setTextColor(0xfff8f8fc);content.addView(permission);Dialog dialog=form("Add to "+kind,content);content.addView(button("Save source",()->{if(!permission.isChecked()){toast("Confirm permission for this source");return;}try{media.add(title.getText().toString(),url.getText().toString(),kind,http());dialog.dismiss();library(kind);}catch(Exception e){toast("Enter a title and valid media URL");}}));}
    private void cloud(String folder,String kind){screen("Premiumize cloud",()->library(kind));page.addView(text("Loading…",16));async(()->{String token=secrets.get("premiumize");if(token.isEmpty())throw new Exception();return media.premiumize("folder/list"+(folder.isEmpty()?"":"?id="+Network.q(folder)),token).getJSONArray("content");},items->{page.removeViewAt(page.getChildCount()-1);if(items.length()==0)page.addView(text("This folder is empty",16));for(int i=0;i<items.length();i++){JSONObject file=items.getJSONObject(i);String name=file.optString("name","File"),key=file.optString("id"),type=file.optString("type");page.addView(button((type.equals("folder")?"▦ ":"▶ ")+name,()->{if(type.equals("folder")){cloud(key,kind);return;}async(()->media.premiumize("item/details?id="+Network.q(key),secrets.get("premiumize")),detail->{String url=detail.optString("link");if(url.isEmpty()){toast("This file has no playback link");return;}new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Play","Add to "+kind},(d,w)->{if(w==0)play(name,url,"pm:"+key,false);else{try{media.addPremiumize(name,key,kind);toast("Added to "+kind);}catch(Exception e){toast("Could not add this file");}}}).show();});}));}});}
    private static final class FoundChannel extends Exception{}
    /** Reads only until the first playable channel, so connecting a very large playlist stays quick. */
    private boolean hasChannel(String url) throws Exception{
        try(java.io.InputStream in=Network.open(url,null,Network.CATALOGUE_MAX,http())){Playlist.parse(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8),url,http(),c->{throw new FoundChannel();});}catch(FoundChannel found){return true;}
        return false;
    }
    private void settingsStyle(View view){if(view instanceof Button||view instanceof Switch){view.setFocusable(true);view.setOnFocusChangeListener((v,on)->{GradientDrawable bg=box(on?0xff263550:0xff101827);bg.setStroke(dp(on?2:0),accent());v.setBackground(bg);});view.setBackground(box(0xff101827));}if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)settingsStyle(group.getChildAt(i));}}
    private void settings(){screen("Settings",this::home);LinearLayout content=vertical();page.addView(content);Dialog dialog=new Dialog(this);LinearLayout tabs=horizontal(content);LinearLayout body=vertical();content.addView(body);for(String tab:new String[]{"Accounts","Appearance","Playback","Apps","System"})tabs.addView(button(tab,()->settingsTab(body,tab,dialog)));settingsTab(body,"Accounts",dialog);settingsStyle(tabs);tabs.getChildAt(0).requestFocus();}
    private void toggle(LinearLayout parent,String title,String key,boolean fallback){Switch s=new Switch(this);s.setText(title);s.setTextColor(0xfff8f8fc);s.setChecked(prefs.getBoolean(key,fallback));s.setPadding(0,dp(12),0,dp(12));s.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean(key,checked).apply());parent.addView(s);}
    private void languagePreference(LinearLayout parent,String title,String key,String fallback,boolean subtitles){
        String[] names=subtitles?new String[]{"Off","English","Spanish","French","German","Italian","Portuguese","Dutch","Polish","Japanese","Korean","Chinese","Hindi","Arabic"}:new String[]{"English","Original / default","Spanish","French","German","Italian","Portuguese","Dutch","Polish","Japanese","Korean","Chinese","Hindi","Arabic"};
        String[] codes=subtitles?new String[]{"none","eng,en","spa,es","fra,fre,fr","deu,ger,de","ita,it","por,pt","nld,dut,nl","pol,pl","jpn,ja","kor,ko","zho,chi,zh","hin,hi","ara,ar"}:new String[]{"eng,en","","spa,es","fra,fre,fr","deu,ger,de","ita,it","por,pt","nld,dut,nl","pol,pl","jpn,ja","kor,ko","zho,chi,zh","hin,hi","ara,ar"};
        String value=prefs.getString(key,fallback);int chosen=0;for(int i=0;i<codes.length;i++)if(codes[i].equals(value))chosen=i;
        Button control=button(title+": "+names[chosen],()->{});control.setOnClickListener(v->{String current=prefs.getString(key,fallback);int selected=0;for(int i=0;i<codes.length;i++)if(codes[i].equals(current))selected=i;new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names,selected,(d,which)->{prefs.edit().putString(key,codes[which]).apply();control.setText(title+": "+names[which]);d.dismiss();}).setNegativeButton("Cancel",null).show();});parent.addView(control);
    }
    private void settingsTab(LinearLayout body,String tab,Dialog dialog){
        body.removeAllViews();
        if(tab.equals("Accounts")){
            body.addView(text("Xtream"+(secrets.has("xtream")?" · Connected":" · Not connected"),20));EditText server=field(body,"Server URL",false,""),username=field(body,"Username",false,""),password=field(body,"Password",true,"");body.addView(button("Connect Xtream",()->{String url=server.getText().toString().trim(),user=username.getText().toString(),pass=password.getText().toString();async(()->{JSONObject account=MediaRepository.connectXtream(url,user,pass,http());secrets.put("xtream",account.toString());return true;},ok->{password.setText("");toast("Xtream connected · importing channels");ChannelSync.refreshNow(this);});}));body.addView(button("Disconnect Xtream",()->{secrets.remove("xtream");ChannelSync.refreshNow(this);toast("Xtream disconnected");}));
            body.addView(text("M3U playlist"+(secrets.has("m3u")?" · Connected":" · Not connected"),20));EditText playlist=field(body,"Playlist URL",false,"");body.addView(button("Connect playlist",()->{String url=playlist.getText().toString().trim();async(()->{String safe=Playlist.networkUrl(url,http());if(!hasChannel(safe))throw new Exception();secrets.put("m3u",safe);return true;},ok->{toast("Playlist connected · importing channels");ChannelSync.refreshNow(this);});}));body.addView(button("Disconnect playlist",()->{secrets.remove("m3u");ChannelSync.refreshNow(this);toast("Playlist disconnected");}));
            body.addView(text("TMDB artwork"+(secrets.has("tmdb")?" · Connected":" · Not connected"),20));EditText tmdb=field(body,"TMDB API Key (v3)",true,"");body.addView(button("Connect TMDB",()->{String token=tmdb.getText().toString().trim();async(()->{if(token.isEmpty())throw new Exception();new JSONObject(Network.text(MediaRepository.tmdbUrl("trending/all/week",token),null,false)).getJSONArray("results");secrets.put("tmdb",token);return true;},ok->{tmdb.setText("");toast("TMDB connected");});}));body.addView(button("Disconnect TMDB",()->{secrets.remove("tmdb");toast("TMDB disconnected");}));
            body.addView(text("Premiumize"+(secrets.has("premiumize")?" · Connected":" · Not connected"),20));EditText pm=field(body,"Premiumize API key",true,"");body.addView(button("Connect Premiumize",()->{String token=pm.getText().toString().trim();async(()->{if(token.isEmpty())throw new Exception();media.premiumize("account/info",token);secrets.put("premiumize",token);return true;},ok->{pm.setText("");toast("Premiumize connected");});}));body.addView(button("Disconnect Premiumize",()->{secrets.remove("premiumize");toast("Premiumize disconnected");}));toggle(body,"Allow HTTP provider sources","http",false);body.addView(text("HTTP sends provider credentials without transport encryption.",12));
        }else if(tab.equals("Appearance")){toggle(body,"Show featured row","featured",true);toggle(body,"24-hour clock","clock24",true);for(int colour:new int[]{0xff8a9eff,0xff55aaff,0xff45c9ac})body.addView(button(colour==0xff8a9eff?"Violet":colour==0xff55aaff?"Blue":"Teal",()->{prefs.edit().putInt("accent",colour).apply();toast("Accent saved");}));body.addView(button("Apply",()->{dialog.dismiss();home();}));}
        else if(tab.equals("Playback")){toggle(body,"Resume playback","resume",true);toggle(body,"Check and offer app updates automatically","autoUpdates",true);languagePreference(body,"Subtitles","subtitleLanguage","none",true);languagePreference(body,"Audio track","audioLanguage","eng,en",false);}
        else if(tab.equals("Apps")){body.addView(button("Manage installed-app dock",()->{dialog.dismiss();allApps();}));}
        else{body.addView(text(android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" · Android "+android.os.Build.VERSION.RELEASE,16));body.addView(button("Check for app updates",()->{lastUpdateCheck=0;checkForUpdate();}));body.addView(button("Open Android Settings",()->{try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception e){toast("Android Settings is unavailable");}}));body.addView(text("TMDB supplies metadata and artwork. This product uses the TMDB API but is not endorsed or certified by TMDB.",12));body.addView(button("Metadata attribution",()->new AlertDialog.Builder(this).setTitle("Metadata attribution").setMessage("The Movie Database (TMDB)\nhttps://www.themoviedb.org\n\nMetadata is separate from your playback sources.").setPositiveButton("Close",null).show()));}
        settingsStyle(body);
    }
    private void catalogueMenu(String kind){
        String type=kind.equals("Movies")?"movie":"tv";screen(kind+" catalogue",()->library(kind));
        page.addView(button("Search",()->{LinearLayout form=vertical();EditText query=field(form,"Search titles",false,"");Dialog dialog=form("Search "+kind,form);form.addView(button("Search",()->{String text=query.getText().toString().trim();if(text.isEmpty())return;dialog.dismiss();catalogue(type,"search/"+type+"?query="+Network.q(text)+"&include_adult=false",1);}));}));
        for(String category:new String[]{"Trending today","Trending this week","Popular","Top rated"})page.addView(button(category,()->{String path=category.startsWith("Trending")?"trending/"+type+"/"+(category.endsWith("today")?"day":"week"):type+"/"+(category.equals("Popular")?"popular":"top_rated");catalogue(type,path,1);}));
        if(type.equals("movie")){page.addView(button("Now playing",()->catalogue(type,"movie/now_playing",1)));page.addView(button("Upcoming",()->catalogue(type,"movie/upcoming",1)));}
        else{page.addView(button("Airing today",()->catalogue(type,"tv/airing_today",1)));page.addView(button("On the air",()->catalogue(type,"tv/on_the_air",1)));}
        page.addView(button("Browse by decade, language or service",()->advancedCatalogue(type)));page.addView(text("Genres",20));async(()->media.tmdb("genre/"+type+"/list").getJSONArray("genres"),genres->{for(int i=0;i<genres.length();i++){JSONObject genre=genres.getJSONObject(i);int id=genre.getInt("id");page.addView(button(genre.getString("name"),()->catalogue(type,"discover/"+type+"?with_genres="+id+"&include_adult=false",1)));}});
    }
    private void catalogue(String type,String path,int number){
        String kind=type.equals("movie")?"Movies":"Series";screen(kind+" · Page "+number,()->catalogueMenu(kind));page.addView(text("Loading…",16));
        async(()->media.tmdb(path+(path.contains("?")?"&":"?")+"page="+number),result->{page.removeViewAt(page.getChildCount()-1);JSONArray items=result.getJSONArray("results");int visible=0;
            posterGrid(page,items,type);int total=result.optInt("total_pages",1);if(number<Math.min(total,500))page.addView(button("Next page",()->catalogue(type,path,number+1)));if(number>1)page.addView(button("Previous page",()->catalogue(type,path,number-1)));
        });
    }
    private void seasons(int tmdbId,String title){screen(title,this::home);async(()->media.tmdb("tv/"+tmdbId+"?append_to_response=external_ids"),details->{String imdb=details.getJSONObject("external_ids").optString("imdb_id");JSONArray seasons=details.getJSONArray("seasons");for(int i=0;i<seasons.length();i++){JSONObject season=seasons.getJSONObject(i);int number=season.getInt("season_number");page.addView(button(season.optString("name","Season "+number),()->episodes(tmdbId,imdb,number,title)));}});}
    private void episodes(int tmdbId,String imdb,int season,String title){
        screen(title+" · Season "+season,()->seasons(tmdbId,title));TextView loading=text("Loading episodes…",18);page.addView(loading);
        async(()->media.tmdb("tv/"+tmdbId+"/season/"+season).getJSONArray("episodes"),episodes->{page.removeView(loading);if(episodes.length()==0)page.addView(text("No episodes available",18));
            for(int i=0;i<episodes.length();i++){
                JSONObject episode=episodes.getJSONObject(i);int number=episode.getInt("episode_number");String label=number+" · "+episode.optString("name","Episode");LinearLayout card=row();card.setPadding(dp(10),dp(10),dp(10),dp(10));card.setBackground(box(0xff182337));
                ImageView still=new ImageView(this);still.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(still,new LinearLayout.LayoutParams(dp(180),dp(102)));String path=episode.optString("still_path");if(path.startsWith("/"))loadImage(still,"https://image.tmdb.org/t/p/w342"+path);
                LinearLayout copy=vertical();copy.setPadding(dp(16),0,0,0);copy.addView(text(label,20));TextView summary=text(episode.optString("overview",""),14);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(summary);card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));focus(card);card.setOnClickListener(v->torrentSources(imdb,season,number,title+" · "+label,"Series"));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));page.addView(card,lp);
            }
        });
    }
    private String sourceLabel(String raw){
        String lower=raw.toLowerCase(Locale.ROOT);
        String quality=lower.contains("2160p")||lower.contains("4k")?"4K":lower.contains("1080p")?"1080p":lower.contains("720p")?"720p":"Other";
        String hdr=lower.contains("dolby vision")||java.util.regex.Pattern.compile("\\b(?:dv|dovi)\\b").matcher(lower).find()?" · Dolby Vision":lower.contains("hdr")?" · HDR":"";
        String codec=lower.contains("h265")||lower.contains("hevc")||lower.contains("x265")?" · HEVC":lower.contains("h264")||lower.contains("x264")?" · H.264":"";
        int split=raw.indexOf(10);String provider=split>=0?raw.substring(0,split):"Source";
        return quality+hdr+codec+"  ·  "+provider;
    }
    private void torrentSources(String imdb,int season,int episode,String title,String kind){
        screen(title+" · Sources",()->catalogueMenu(kind));if(!secrets.has("premiumize")){page.addView(text("Connect Premiumize to check and play these sources",20));page.addView(button("Connect account",this::settings));return;}page.addView(button("Premiumize cloud",()->cloud("",kind)));page.addView(text("Checking sources and Premiumize cache…",16));SourceRepository sourceRepo=new SourceRepository(secrets);
        async(()->{return sourceRepo.torrentio(imdb,season,episode);},sources->{page.removeViewAt(page.getChildCount()-1);if(sources.isEmpty()){page.addView(text("No supported sources returned",16));return;}
            for(SourceRepository.Cache status:SourceRepository.Cache.values()){page.addView(text(status==SourceRepository.Cache.CACHED?"Cached":status==SourceRepository.Cache.NOT_CACHED?"Not cached":"Unknown — cache check unavailable",20));int count=0;
                for(SourceRepository.Source source:sources)if(source.cache==status){count++;page.addView(button(sourceLabel(source.title)+(source.cache==SourceRepository.Cache.CACHED?"   ·   Play":""),()->{
                    if(source.cache==SourceRepository.Cache.UNKNOWN){torrentSources(imdb,season,episode,title,kind);return;}
                    if(source.cache==SourceRepository.Cache.NOT_CACHED){new AlertDialog.Builder(this).setTitle("Send to Premiumize?").setMessage("Queue this source in your cloud. Select a source you have permission to download.").setPositiveButton("Queue",(d,w)->async(()->sourceRepo.queue(source),id->{toast("Transfer queued");transfers(kind);})).setNegativeButton("Cancel",null).show();return;}
                    async(()->sourceRepo.direct(source),files->{screen(title+" · Files",()->torrentSources(imdb,season,episode,title,kind));for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String link=file.getString("link"),name=file.optString("path","File");int match=VideoFiles.score(name,"",season,episode);if(match<0)continue;page.addView(button((match>=2?"Suggested episode · ":"")+name,()->play(name,link,id(source.uri+":"+name),false)));}});
                }));}if(count==0)page.addView(text("No sources in this group",14));
            }
        });
    }
    private void transfers(String kind){screen("Premiumize transfers",()->library(kind));page.addView(button("Refresh",()->transfers(kind)));async(()->media.premiumize("transfer/list",secrets.get("premiumize")).getJSONArray("transfers"),items->{if(items.length()==0)page.addView(text("No transfers",16));for(int i=0;i<items.length();i++){JSONObject transfer=items.getJSONObject(i);String status=transfer.optString("status","unknown"),folder=transfer.optString("folder_id",""),file=transfer.optString("file_id","");String label=transfer.optString("name","Transfer")+" · "+status+" · "+Math.round(transfer.optDouble("progress",0)*100)+"%";page.addView(button(label,()->{if(!status.equals("finished")&&!status.equals("seeding")){toast("Transfer is "+status);return;}if(!file.isEmpty()&&!file.equals("null")){async(()->media.premiumize("item/details?id="+Network.q(file),secrets.get("premiumize")),detail->play(detail.optString("name","File"),detail.getString("link"),"pm:"+file,false));}else if(!folder.isEmpty()&&!folder.equals("null"))cloud(folder,kind);}));}});}

    private void advancedCatalogue(String type){
        screen("More ways to browse",()->catalogueMenu(type.equals("movie")?"Movies":"Series"));
        page.addView(text("Sort order",20));String date=type.equals("movie")?"primary_release_date":"first_air_date";
        for(String[] sort:new String[][]{{"Most popular","popularity.desc"},{"Highest rated","vote_average.desc"},{"Most voted","vote_count.desc"},{"Newest",date+".desc"},{"Oldest",date+".asc"}})page.addView(button(sort[0],()->catalogue(type,"discover/"+type+"?include_adult=false&vote_count.gte=100&sort_by="+sort[1],1)));
        if(type.equals("movie"))page.addView(button("Highest grossing",()->catalogue(type,"discover/movie?include_adult=false&sort_by=revenue.desc",1)));
        page.addView(text("Decades",20));int decade=(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)/10)*10;
        for(int year=decade;year>=1930;year-=10){int start=year;String key=type.equals("movie")?"primary_release_date":"first_air_date";page.addView(button(start+"s",()->catalogue(type,"discover/"+type+"?include_adult=false&"+key+".gte="+start+"-01-01&"+key+".lte="+(start+9)+"-12-31",1)));}
        page.addView(button("Browse all original languages",()->{screen("Original languages",()->advancedCatalogue(type));async(()->new JSONArray(Network.text(MediaRepository.tmdbUrl("configuration/languages",secrets.get("tmdb")),null,false)),languages->{for(int i=0;i<languages.length();i++){JSONObject language=languages.getJSONObject(i);String code=language.getString("iso_639_1");page.addView(button(language.optString("english_name",code),()->catalogue(type,"discover/"+type+"?include_adult=false&with_original_language="+Network.q(code),1)));}});}));
        page.addView(button("Streaming services by region",()->{LinearLayout content=vertical();EditText region=field(content,"Two-letter catalogue region",false,prefs.getString("catalogueRegion","US"));Dialog dialog=form("Catalogue region",content);content.addView(button("Show services",()->{String country=region.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);if(!country.matches("[A-Z]{2}")){toast("Enter a two-letter region");return;}prefs.edit().putString("catalogueRegion",country).apply();dialog.dismiss();screen("Services · "+country,()->advancedCatalogue(type));async(()->media.tmdb("watch/providers/"+type+"?watch_region="+country).getJSONArray("results"),providers->{for(int i=0;i<providers.length();i++){JSONObject provider=providers.getJSONObject(i);int id=provider.getInt("provider_id");page.addView(button(provider.getString("provider_name"),()->catalogue(type,"discover/"+type+"?include_adult=false&watch_region="+country+"&with_watch_providers="+id,1)));}});}));}));
    }

}
