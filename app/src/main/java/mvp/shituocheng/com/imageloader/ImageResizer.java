package mvp.shituocheng.com.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by shituocheng on 2016/10/31.
 */

public class ImageResizer {

    private static final String TAG="ImageResizer";

    public ImageResizer() {

    }

    public Bitmap decodeSampledBitmapFromResource(Resources resources, int resId, int reqWidth, int reqHeight){

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BitmapFactory.decodeResource(resources, resId, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(resources, resId, options);
    }

    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fileDescriptor, int reqWidth, int reqHeight){

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BitmapFactory.decodeFileDescriptor(fileDescriptor);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fileDescriptor, null, options);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight){

        if (reqWidth == 0 || reqHeight ==0){
            return 1;
        }

        final int height = options.outHeight;
        final int width = options.outWidth;

        Log.d(TAG, "origin, w="+ width+"h="+height);
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth){
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth){
                inSampleSize = 2;
            }

        }
        Log.d(TAG,"sampleSize="+inSampleSize);
        return inSampleSize;
    }
}
