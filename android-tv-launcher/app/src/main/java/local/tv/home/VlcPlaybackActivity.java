package local.tv.home;

import android.app.*;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.*;

/** Fullscreen TV playback; account and resume storage retain their original identities. */
public class VlcPlaybackActivity extends Activity {
    private LibVLC engine;private MediaPlayer player;private VLCVideoLayout video;
    private String url,id;private long position;private boolean live,ended,foreground,triedAlternative,dragging,dialogOpen;
    private float playbackRate=1f;private long sleepAt;private String aspect;
    private final Runnable sleep=()->{Toast.makeText(this,"Sleep timer finished",Toast.LENGTH_SHORT).show();finish();};
    private Button pause;private SeekBar timeline;private TextView elapsed,duration,status;
    private AudioManager audio;private AudioFocusRequest audioFocus;
    private LinearLayout controls;private final Handler overlayHandler=new Handler(Looper.getMainLooper());
    private final Runnable hideOverlay=()->{if(controls!=null&&!dragging&&!dialogOpen&&player!=null&&player.isPlaying())controls.setVisibility(View.GONE);};
    private final Runnable stalled=()->showError("The source is taking too long to respond.");
    private final Runnable tick=new Runnable(){public void run(){
        if(player!=null){long length=player.getLength(),time=Math.max(0,player.getTime());elapsed.setText(live?"LIVE":format(time));duration.setText(length>0?format(length):"—");timeline.setEnabled(!live&&player.isSeekable()&&length>0);if(!dragging&&length>0)timeline.setProgress((int)Math.min(1000,time*1000/length));}
        if(foreground)overlayHandler.postDelayed(this,1000);
    }};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private static String format(long millis){long seconds=millis/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void revealControls(){if(controls==null)return;boolean hidden=controls.getVisibility()!=View.VISIBLE;controls.setVisibility(View.VISIBLE);if(hidden)pause.requestFocus();overlayHandler.removeCallbacks(hideOverlay);overlayHandler.postDelayed(hideOverlay,4500);}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)immersive();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            int key=event.getKeyCode();
            if(key==KeyEvent.KEYCODE_BACK){finish();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){toggle();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY){if(player!=null)player.play();revealControls();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){if(player!=null)player.pause();pause.setText("Play");revealControls();return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_REWIND){seek(-10000);return true;}
            if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){seek(10000);return true;}
            if(key>=KeyEvent.KEYCODE_DPAD_UP&&key<=KeyEvent.KEYCODE_DPAD_CENTER){
                if(controls.getVisibility()!=View.VISIBLE){revealControls();return true;}revealControls();
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);immersive();
        id=getIntent().getStringExtra("id");if(id==null)id="media";live=getIntent().getBooleanExtra("live",false);
        try{url=Playlist.networkUrl(getIntent().getStringExtra("url"),getSharedPreferences("ui",MODE_PRIVATE).getBoolean("http",false));}catch(Exception e){Toast.makeText(this,"Invalid or insecure playback URL",Toast.LENGTH_LONG).show();finish();return;}
        Object saved=getSharedPreferences("positions",MODE_PRIVATE).getAll().get(id);
        position=state==null?(!live&&getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true)&&saved instanceof Number?((Number)saved).longValue():0):state.getLong("position");
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);video=new VLCVideoLayout(this);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(20);status.setGravity(Gravity.CENTER);status.setText("Opening source…");root.addView(status,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(24),dp(12),dp(24),dp(12));controls.setBackgroundColor(0xe6101827);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("title"));title.setTextColor(Color.WHITE);title.setTextSize(22);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);controls.addView(title);
        LinearLayout progress=new LinearLayout(this);progress.setGravity(Gravity.CENTER_VERTICAL);elapsed=label();duration=label();timeline=new SeekBar(this);timeline.setMax(1000);int tint=getSharedPreferences("ui",MODE_PRIVATE).getInt("accent",0xff8a9eff);timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(tint));timeline.setEnabled(false);progress.addView(elapsed);progress.addView(timeline,new LinearLayout.LayoutParams(0,dp(44),1));progress.addView(duration);controls.addView(progress);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;overlayHandler.removeCallbacks(hideOverlay);}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;revealControls();}
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser&&player!=null&&!live&&player.isSeekable()){player.setTime(player.getLength()*value/1000);revealControls();}}
        });
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(buttons);controls.addView(scroll);
        if(!live)add(buttons,"−10 s",()->seek(-10000));pause=add(buttons,"Pause",this::toggle);if(!live)add(buttons,"+10 s",()->seek(10000));add(buttons,"Video",this::resolution);add(buttons,"Audio",()->tracks(false));add(buttons,"Subtitles",()->tracks(true));add(buttons,"More",this::moreControls);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));root.setOnClickListener(v->revealControls());setContentView(root);pause.requestFocus();revealControls();
    }
    private TextView label(){TextView t=new TextView(this);t.setTextColor(Color.WHITE);t.setTextSize(15);t.setText("—");return t;}
    private Button add(LinearLayout parent,String title,Runnable action){
        Button button=new Button(this);button.setFocusableInTouchMode(true);button.setText(title);button.setTextColor(Color.WHITE);button.setTextSize(16);button.setAllCaps(false);button.setPadding(dp(14),dp(8),dp(14),dp(8));button.setBackground(controlBackground(false));button.setOnFocusChangeListener((v,focused)->v.setBackground(controlBackground(focused)));button.setOnClickListener(v->{revealControls();action.run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(52));lp.setMargins(dp(3),dp(4),dp(3),dp(4));parent.addView(button,lp);return button;
    }
    private android.graphics.drawable.GradientDrawable controlBackground(boolean focused){android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(focused?0xff343e62:0xff202c43);background.setCornerRadius(dp(12));background.setStroke(dp(focused?2:1),focused?getSharedPreferences("ui",MODE_PRIVATE).getInt("accent",0xff8a9eff):0x33ffffff);return background;}
    @Override public void onStart(){
        super.onStart();foreground=true;if(url==null)return;audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        audioFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener(change->{if(change<0&&player!=null){player.pause();pause.setText("Play");revealControls();}}).build();
        if(audio.requestAudioFocus(audioFocus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)startPlayer();else showError("Audio is in use by another app.");overlayHandler.post(tick);
    }
    private void startPlayer(){
        if(!foreground)return;release();status.setText("Opening source…");status.setVisibility(View.VISIBLE);overlayHandler.removeCallbacks(stalled);overlayHandler.postDelayed(stalled,45000);
        engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--network-caching=1500","--no-video-title-show","--audio-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("audioLanguage","eng,en"),"--sub-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("subtitleLanguage","none"))));
        player=new MediaPlayer(engine);player.setRate(playbackRate);if(aspect!=null)player.setAspectRatio(aspect);player.attachViews(video,null,false,false);
        player.setEventListener(event->{if(!foreground||player==null)return;
            if(event.type==MediaPlayer.Event.Playing){player.setRate(playbackRate);if(aspect!=null)player.setAspectRatio(aspect);overlayHandler.removeCallbacks(stalled);status.setVisibility(View.GONE);if(position>0&&!live&&player.isSeekable()){player.setTime(position);position=0;}pause.setText("Pause");revealControls();}
            else if(event.type==MediaPlayer.Event.Buffering){if(event.getBuffering()<100){status.setText("Buffering…");status.setVisibility(View.VISIBLE);}else status.setVisibility(View.GONE);}
            else if(event.type==MediaPlayer.Event.EndReached){ended=true;getSharedPreferences("positions",MODE_PRIVATE).edit().remove(id).apply();String episode=getIntent().getStringExtra("episodeKey");if(episode!=null&&!episode.isEmpty()){android.content.SharedPreferences prefs=getSharedPreferences("ui",MODE_PRIVATE);Set<String> watched=new HashSet<>(prefs.getStringSet("watchedEpisodes",Collections.emptySet()));watched.add(episode);prefs.edit().putStringSet("watchedEpisodes",watched).apply();}finish();}
            else if(event.type==MediaPlayer.Event.EncounteredError){
                if(live&&!triedAlternative&&(Uri.parse(url).getPath().endsWith(".ts")||Uri.parse(url).getPath().endsWith(".m3u8"))){triedAlternative=true;Uri original=Uri.parse(url);String path=original.getEncodedPath();path=path.endsWith(".ts")?path.substring(0,path.length()-3)+".m3u8":path.substring(0,path.length()-5)+".ts";url=original.buildUpon().encodedPath(path).build().toString();video.post(this::startPlayer);}
                else showError("This source could not be played. Try another source or retry the connection.");
            }
        });
        Media media=new Media(engine,Uri.parse(url));media.setHWDecoderEnabled(true,false);player.setMedia(media);media.release();player.play();
    }
    private void showError(String message){
        if(!foreground||isFinishing()||dialogOpen)return;overlayHandler.removeCallbacks(stalled);if(player!=null){long time=player.getTime();if(time>0&&!live)position=time;player.pause();}pause.setText("Play");status.setText("Playback unavailable");status.setVisibility(View.VISIBLE);dialogOpen=true;
        AlertDialog error=new AlertDialog.Builder(this).setTitle("Playback error").setMessage(message).setPositiveButton("Retry",(d,w)->startPlayer()).setNegativeButton("Back to sources",(d,w)->finish()).create();error.setOnDismissListener(d->{dialogOpen=false;revealControls();});error.show();
    }
    private void toggle(){revealControls();if(player==null)return;if(player.isPlaying()){player.pause();pause.setText("Play");}else{player.play();pause.setText("Pause");}}
    private void seek(long delta){revealControls();if(player!=null&&!live&&player.isSeekable()){long target=Math.max(0,player.getTime()+delta),length=player.getLength();player.setTime(length>0?Math.min(length,target):target);}}
    private void resolution(){
        if(player==null)return;Media.VideoTrack track=player.getCurrentVideoTrack();String size=track==null?"Resolution unavailable":track.width+" × "+track.height;
        MediaPlayer.TrackDescription[] choices=player.getVideoTracks();if(choices==null||choices.length<2){new AlertDialog.Builder(this).setTitle("Video").setMessage(size+"\nChoose a different source for a different resolution.").setPositiveButton("Close",null).show();return;}
        chooseTracks(choices,player.getVideoTrack(),0,"Video · "+size);
    }
    private void tracks(boolean subtitles){if(player==null)return;MediaPlayer.TrackDescription[] choices=subtitles?player.getSpuTracks():player.getAudioTracks();if(choices==null||choices.length==0){Toast.makeText(this,"No tracks available",Toast.LENGTH_SHORT).show();return;}chooseTracks(choices,subtitles?player.getSpuTrack():player.getAudioTrack(),subtitles?2:1,subtitles?"Subtitles":"Audio");}
    private void chooseTracks(MediaPlayer.TrackDescription[] choices,int current,int type,String title){
        String[] names=new String[choices.length];int selected=-1;for(int i=0;i<names.length;i++){names[i]=choices[i].id<0?"Off":choices[i].name;if(choices[i].id==current)selected=i;}
        dialogOpen=true;AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(names,selected,(d,which)->{if(player!=null){int choice=choices[which].id;if(type==2)player.setSpuTrack(choice);else if(type==1)player.setAudioTrack(choice);else player.setVideoTrack(choice);}d.dismiss();}).setNegativeButton("Close",null).create();dialog.setOnDismissListener(d->{dialogOpen=false;revealControls();});dialog.show();
    }
    private void moreControls(){
        List<String> labels=new ArrayList<>();if(!live)labels.add("Playback speed · "+playbackRate+"×");labels.add("Picture size");labels.add("Subtitle timing");labels.add(sleepAt>0?"Sleep timer · "+Math.max(1,(sleepAt-SystemClock.elapsedRealtime())/60000)+" min left":"Sleep timer");dialogOpen=true;AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Playback options").setItems(labels.toArray(new String[0]),(d,which)->{String label=labels.get(which);overlayHandler.post(()->{if(label.startsWith("Playback speed"))chooseSpeed();else if(label.equals("Picture size"))choosePicture();else if(label.equals("Subtitle timing"))subtitleTiming();else chooseSleep();});}).setNegativeButton("Close",null).create();dialog.setOnDismissListener(d->{dialogOpen=false;revealControls();});dialog.show();
    }
    private void options(String title,String[] labels,int selected,java.util.function.IntConsumer action){dialogOpen=true;AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,selected,(d,which)->{action.accept(which);d.dismiss();}).setNegativeButton("Cancel",null).create();dialog.setOnDismissListener(d->{dialogOpen=false;revealControls();});dialog.show();}
    private void chooseSpeed(){float[] rates={.5f,.75f,1f,1.25f,1.5f,1.75f,2f};int selected=2;for(int i=0;i<rates.length;i++)if(rates[i]==playbackRate)selected=i;options("Playback speed",new String[]{"0.5×","0.75×","Normal · 1×","1.25×","1.5×","1.75×","2×"},selected,which->{playbackRate=rates[which];if(player!=null)player.setRate(playbackRate);});}
    private void choosePicture(){String[] aspects={null,"16:9","4:3","21:9"};int selected=0;for(int i=1;i<aspects.length;i++)if(aspects[i].equals(aspect))selected=i;options("Picture size",new String[]{"Original · Fit","16:9","4:3","21:9"},selected,which->{aspect=aspects[which];if(player!=null){player.setScale(0);player.setAspectRatio(aspect);}});}
    private void subtitleTiming(){if(player==null)return;long delay=player.getSpuDelay();options("Subtitle timing · "+delay/1000+" ms",new String[]{"Earlier by 250 ms","Later by 250 ms","Reset timing"},-1,which->{if(player!=null)player.setSpuDelay(which==2?0:delay+(which==0?-250000:250000));});}
    private void chooseSleep(){options("Sleep timer",new String[]{"Off","15 minutes","30 minutes","60 minutes","90 minutes"},sleepAt==0?0:-1,which->{overlayHandler.removeCallbacks(sleep);int[] minutes={0,15,30,60,90};sleepAt=which==0?0:SystemClock.elapsedRealtime()+minutes[which]*60000L;if(sleepAt>0)overlayHandler.postDelayed(sleep,minutes[which]*60000L);});}
    private void release(){if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();player=null;}if(engine!=null){engine.release();engine=null;}}
    @Override public void onStop(){overlayHandler.removeCallbacksAndMessages(null);sleepAt=0;foreground=false;if(player!=null){long time=player.getTime();if(time>=0)position=time;if(!live){long length=player.getLength();android.content.SharedPreferences.Editor edit=getSharedPreferences("positions",MODE_PRIVATE).edit();if(length>0)edit.putLong(id+":duration",length);if(!ended&&getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true))edit.putLong(id,position);edit.apply();}}release();if(audio!=null&&audioFocus!=null)audio.abandonAudioFocusRequest(audioFocus);super.onStop();}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putLong("position",player==null?position:Math.max(position,player.getTime()));}
    @Override public void onDestroy(){overlayHandler.removeCallbacksAndMessages(null);release();super.onDestroy();}
}
