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
        LinearLayout progress=new LinearLayout(this);progress.setGravity(Gravity.CENTER_VERTICAL);elapsed=label();duration=label();timeline=new SeekBar(this);timeline.setMax(1000);timeline.setEnabled(false);progress.addView(elapsed);progress.addView(timeline,new LinearLayout.LayoutParams(0,dp(44),1));progress.addView(duration);controls.addView(progress);
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){dragging=true;overlayHandler.removeCallbacks(hideOverlay);}
            public void onStopTrackingTouch(SeekBar bar){dragging=false;revealControls();}
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser&&player!=null&&!live&&player.isSeekable()){player.setTime(player.getLength()*value/1000);revealControls();}}
        });
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(buttons);controls.addView(scroll);
        if(!live)add(buttons,"−10 s",()->seek(-10000));pause=add(buttons,"Pause",this::toggle);if(!live)add(buttons,"+10 s",()->seek(10000));add(buttons,"Video",this::resolution);add(buttons,"Audio",()->tracks(false));add(buttons,"Subtitles",()->tracks(true));add(buttons,"Back",this::finish);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));root.setOnClickListener(v->revealControls());setContentView(root);pause.requestFocus();revealControls();
    }
    private TextView label(){TextView t=new TextView(this);t.setTextColor(Color.WHITE);t.setTextSize(15);t.setText("—");return t;}
    private Button add(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextSize(15);b.setAllCaps(false);b.setOnClickListener(v->{revealControls();action.run();});parent.addView(b);return b;}
    @Override public void onStart(){
        super.onStart();foreground=true;if(url==null)return;audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        audioFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener(change->{if(change<0&&player!=null){player.pause();pause.setText("Play");revealControls();}}).build();
        if(audio.requestAudioFocus(audioFocus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)startPlayer();else showError("Audio is in use by another app.");overlayHandler.post(tick);
    }
    private void startPlayer(){
        if(!foreground)return;release();status.setText("Opening source…");status.setVisibility(View.VISIBLE);overlayHandler.removeCallbacks(stalled);overlayHandler.postDelayed(stalled,45000);
        engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--network-caching=1500","--no-video-title-show","--audio-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("audioLanguage","eng,en"),"--sub-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("subtitleLanguage","none"))));
        player=new MediaPlayer(engine);player.attachViews(video,null,false,false);
        player.setEventListener(event->{if(!foreground||player==null)return;
            if(event.type==MediaPlayer.Event.Playing){overlayHandler.removeCallbacks(stalled);status.setVisibility(View.GONE);if(position>0&&!live&&player.isSeekable()){player.setTime(position);position=0;}pause.setText("Pause");revealControls();}
            else if(event.type==MediaPlayer.Event.Buffering){if(event.getBuffering()<100){status.setText("Buffering…");status.setVisibility(View.VISIBLE);}else status.setVisibility(View.GONE);}
            else if(event.type==MediaPlayer.Event.EndReached){ended=true;getSharedPreferences("positions",MODE_PRIVATE).edit().remove(id).apply();finish();}
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
    private void release(){if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();player=null;}if(engine!=null){engine.release();engine=null;}}
    @Override public void onStop(){overlayHandler.removeCallbacksAndMessages(null);foreground=false;if(player!=null){long time=player.getTime();if(time>=0)position=time;if(!live&&!ended&&getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true))getSharedPreferences("positions",MODE_PRIVATE).edit().putLong(id,position).apply();}release();if(audio!=null&&audioFocus!=null)audio.abandonAudioFocusRequest(audioFocus);super.onStop();}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putLong("position",player==null?position:Math.max(position,player.getTime()));}
    @Override public void onDestroy(){overlayHandler.removeCallbacksAndMessages(null);release();super.onDestroy();}
}
