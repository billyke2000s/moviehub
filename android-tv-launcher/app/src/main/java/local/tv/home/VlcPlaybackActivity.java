package local.tv.home;

import android.app.*;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.*;

/** LibVLC integration; device/codec coverage must be tested. */
public class VlcPlaybackActivity extends Activity {
    private LibVLC engine;private MediaPlayer player;private VLCVideoLayout video;
    private String url,id;private long position;private boolean live,ended,foreground;private Button pause;
    private AudioManager audio;private AudioFocusRequest audioFocus;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);id=getIntent().getStringExtra("id");if(id==null)id="media";live=getIntent().getBooleanExtra("live",false);
        try{url=Playlist.networkUrl(getIntent().getStringExtra("url"),getSharedPreferences("ui",MODE_PRIVATE).getBoolean("http",false));}catch(Exception e){finish();return;}
        Object saved=getSharedPreferences("positions",MODE_PRIVATE).getAll().get(id);position=state==null?(!live&&getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true)&&saved instanceof Number?((Number)saved).longValue():0):state.getLong("position");
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);video=new VLCVideoLayout(this);root.addView(video,new FrameLayout.LayoutParams(-1,-1));LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setBackgroundColor(0xcc101827);
        add(controls,"−10 s",()->seek(-10000));pause=add(controls,"Pause",this::toggle);add(controls,"+10 s",()->seek(10000));add(controls,"Resolution",this::resolution);add(controls,"Audio track",()->tracks(false));add(controls,"Subtitles",()->tracks(true));add(controls,"Back",this::finish);root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);pause.requestFocus();
    }
    private Button add(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());parent.addView(b);return b;}
    @Override public void onStart(){super.onStart();foreground=true;if(url==null)return;audio=(AudioManager)getSystemService(AUDIO_SERVICE);audioFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener(change->{if(change<0&&player!=null){player.pause();pause.setText("Play");}}).build();if(audio.requestAudioFocus(audioFocus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)startPlayer();else Toast.makeText(this,"Audio is in use",Toast.LENGTH_SHORT).show();}
    private void startPlayer(){
        if(!foreground)return;release();engine=new LibVLC(this,new ArrayList<>(Arrays.asList("--network-caching=1500","--no-video-title-show","--audio-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("audioLanguage","eng,en"),"--sub-language="+getSharedPreferences("ui",MODE_PRIVATE).getString("subtitleLanguage","none"))));player=new MediaPlayer(engine);player.attachViews(video,null,false,false);
        player.setEventListener(event->{if(!foreground||player==null)return;if(event.type==MediaPlayer.Event.Playing){if(position>0&&!live&&player.isSeekable()){player.setTime(position);position=0;}pause.setText("Pause");}else if(event.type==MediaPlayer.Event.EndReached){ended=true;getSharedPreferences("positions",MODE_PRIVATE).edit().remove(id).apply();finish();}else if(event.type==MediaPlayer.Event.EncounteredError){Toast.makeText(this,"This source could not be played",Toast.LENGTH_LONG).show();finish();}});
        Media media=new Media(engine,Uri.parse(url));media.setHWDecoderEnabled(true,false);player.setMedia(media);media.release();player.play();
    }
    private void toggle(){if(player==null)return;if(player.isPlaying()){player.pause();pause.setText("Play");}else{player.play();pause.setText("Pause");}}
    private void seek(long delta){if(player!=null&&!live&&player.isSeekable())player.setTime(Math.max(0,player.getTime()+delta));}
    private void resolution(){
        if(player==null)return;Media.VideoTrack current=player.getCurrentVideoTrack();String size=current==null?"Resolution unavailable":current.width+" × "+current.height;
        MediaPlayer.TrackDescription[] available=player.getVideoTracks();List<MediaPlayer.TrackDescription> choices=new ArrayList<>();if(available!=null)for(MediaPlayer.TrackDescription track:available)if(track.id>=0)choices.add(track);
        if(choices.size()<2){new AlertDialog.Builder(this).setTitle("Resolution").setMessage(size+"\nChoose a different source for a different resolution.").setPositiveButton("Close",null).show();return;}
        String[] labels=new String[choices.size()];int selected=-1;for(int i=0;i<labels.length;i++){labels[i]=choices.get(i).name;if(choices.get(i).id==player.getVideoTrack())selected=i;}
        new AlertDialog.Builder(this).setTitle("Resolution · "+size).setSingleChoiceItems(labels,selected,(d,which)->{if(player!=null)player.setVideoTrack(choices.get(which).id);d.dismiss();}).setNegativeButton("Close",null).show();
    }
    private void tracks(boolean subtitles){if(player==null)return;MediaPlayer.TrackDescription[] tracks=subtitles?player.getSpuTracks():player.getAudioTracks();if(tracks==null||tracks.length==0){Toast.makeText(this,"No tracks available",Toast.LENGTH_SHORT).show();return;}String[] labels=new String[tracks.length];for(int i=0;i<labels.length;i++)labels[i]=tracks[i].name;new AlertDialog.Builder(this).setTitle(subtitles?"Subtitles":"Audio").setItems(labels,(d,which)->{if(player!=null){if(subtitles)player.setSpuTrack(tracks[which].id);else player.setAudioTrack(tracks[which].id);}}).show();}
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){toggle();return true;}if(key==KeyEvent.KEYCODE_MEDIA_REWIND){seek(-10000);return true;}if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){seek(10000);return true;}return super.onKeyDown(key,event);}
    private void release(){if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();player=null;}if(engine!=null){engine.release();engine=null;}}
    @Override public void onStop(){foreground=false;if(player!=null){position=player.getTime();if(!live&&!ended&&getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true))getSharedPreferences("positions",MODE_PRIVATE).edit().putLong(id,position).apply();}release();if(audio!=null&&audioFocus!=null)audio.abandonAudioFocusRequest(audioFocus);super.onStop();}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putLong("position",player==null?position:player.getTime());}
    @Override public void onDestroy(){release();super.onDestroy();}
}
