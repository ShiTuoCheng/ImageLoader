package mvp.shituocheng.com.imageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.nfc.Tag;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shituocheng on 2016/10/31.
 */

public class ImageLoader {

    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;
    private static final int TAG_KEY_URL = 6;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "ImageLoader"+mCount.getAndIncrement());
        }
    };

    public static final Executor ThREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(), sThreadFactory
    );

    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult loaderResult = (LoaderResult)msg.obj;
            ImageView imageView = loaderResult.imageView;
            String uri = (String)imageView.getTag(TAG_KEY_URL);
            if (uri.equals(loaderResult.uri)){
                imageView.setImageBitmap(loaderResult.bitmap);
            }else {
                Log.w(TAG, "set image bitmap, but url has changed , ignored");
            }
        }
    };

    private Context mContext;
    private ImageResizer imageResizer = new ImageResizer();
    private LruCache<String, Bitmap> lruCache;
    private DiskLruCache diskLruCache;

    public ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;

        lruCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");

        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                diskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    private void addBitmapToMemoryCache(String key, Bitmap bitmap){
        if (getBitmapFromMemoryCache(key) == null){
            lruCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromMemoryCache(String key){
        return lruCache.get(key);
    }

    public void bindBitmap(final String uri, final ImageView imageView){
        bindBitmap(uri, imageView, 0, 0);
    }

    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight){
        imageView.setTag(TAG_KEY_URL, uri);
        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null){

                    LoaderResult loaderResult = new LoaderResult(imageView, uri, bitmap);

                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, loaderResult).sendToTarget();
                }
            }
        };
        ThREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight){

        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            Log.d(TAG, "loadBitmapFromMemeoryCache,uri"+uri);
            return bitmap;
        }

        try{
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null){
                Log.d(TAG, "loadBitmapFromDisk,url"+uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            Log.d(TAG, "loadBitmapFromHttp,url:"+uri);
        }catch (IOException e){
            e.printStackTrace();
        }

        if (bitmap ==  null && !mIsDiskLruCacheCreated){
            Log.w(TAG, "DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }

        return bitmap;
    }

    private Bitmap loadBitmapFromMemoryCache(String url){
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemoryCache(key);
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight)throws IOException{

            if (Looper.myLooper() == Looper.getMainLooper()){
                throw new RuntimeException("can't visit network from ui thread.");
            }
            if (diskLruCache == null){
                return null;
        }

        String key = hashKeyFormUrl(url);
        DiskLruCache.Editor editor = diskLruCache.edit(key);
        if (editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }
            diskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight)throws IOException{

        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG, "load bitmap from UI Thread");
        }
        if (diskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        DiskLruCache.Snapshot snapshot = diskLruCache.get(key);
        if (snapshot != null) {

            FileInputStream fileInputStream = (FileInputStream)snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = imageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null){
                addBitmapToMemoryCache(key, bitmap);
            }
        }

        return bitmap;
    }

    public boolean downloadUrlToStream(String urlString, OutputStream outputStream){
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream bufferedInputStream = null;
        BufferedOutputStream bufferedOutputStream = null;

        try{

            final URL url = new URL(urlString);
            httpURLConnection = (HttpURLConnection)url.openConnection();
            bufferedInputStream = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);

            bufferedOutputStream = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while ((b = bufferedInputStream.read()) != -1){
                bufferedOutputStream.write(b);
            }

            return true;
        }catch (IOException e){
            Log.e(TAG, "download failed"+e);
        }finally {
            if (httpURLConnection != null){
                httpURLConnection.disconnect();
            }
        }

        return false;
    }

    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream bufferedInputStream = null;

        try {
            final URL url = new URL(urlString);
            httpURLConnection = (HttpURLConnection)url.openConnection();
            bufferedInputStream = new BufferedInputStream(httpURLConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(bufferedInputStream);
        }   catch (IOException e) {
            e.printStackTrace();
        }   finally {
            if (httpURLConnection != null){
                httpURLConnection.disconnect();
            }
        }
        return bitmap;
    }

    private String hashKeyFormUrl(String url){
        String cacheKey;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }

        return cacheKey;

    }

    private String bytesToHexString(byte[] bytes){
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++){
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1){
                stringBuilder.append("0");
            }
            stringBuilder.append(hex);
        }
        return stringBuilder.toString();
    }

    private File getDiskCacheDir(Context context, String uniqueName){
        boolean externalStorgeAvaliable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);

        final String cachePath;

        if (externalStorgeAvaliable){
            cachePath = context.getExternalCacheDir().getPath();
        }else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return path.getUsableSpace();
        }

        final StatFs statFs = new StatFs(path.getPath());
        return statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
    }

    private static class LoaderResult{
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap){
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

}
