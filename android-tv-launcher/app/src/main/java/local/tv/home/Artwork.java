package local.tv.home;

import android.graphics.*;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.*;

/** Small decoded bitmap cache and bounded downloads. Failed artwork never blocks navigation. */
final class Artwork {
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(12*1024*1024){
        @Override protected int sizeOf(String key,Bitmap image){return image.getByteCount();}
    };
    private final ThreadPoolExecutor downloads=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main=new Handler(Looper.getMainLooper());
    void load(ImageView view,String url){
        view.setTag(url);view.setBackgroundColor(0xff182337);Bitmap cached=CACHE.get(url);
        if(cached!=null){view.setImageBitmap(cached);return;}
        downloads.execute(()->{
            try{
                Bitmap image=CACHE.get(url);
                if(image==null){
                    byte[] bytes=Network.bytes(url,null,3*1024*1024,false);
                    BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);
                    if(size.outWidth<=0||size.outHeight<=0||size.outWidth>8000||size.outHeight>8000)return;
                    BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;
                    while(size.outWidth/decode.inSampleSize>1280||size.outHeight/decode.inSampleSize>720)decode.inSampleSize*=2;
                    image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,decode);if(image==null)return;CACHE.put(url,image);
                }
                final Bitmap ready=image;main.post(()->{if(view.isAttachedToWindow()&&url.equals(view.getTag()))view.setImageBitmap(ready);});
            }catch(Exception ignored){/* Leave the neutral artwork background visible. */}
        });
    }
    void close(){downloads.shutdownNow();main.removeCallbacksAndMessages(null);}
}
