package com.wix.RNCameraKit.camera.commands;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.facebook.react.bridge.Promise;
import com.wix.RNCameraKit.camera.CameraViewManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class Capture implements Command {

    private final Context context;

    public Capture(Context context) {
        this.context = context;
    }

    @Override
    public void execute(final Promise promise) {
        try {
            tryTakePicture(promise);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tryTakePicture(final Promise promise) throws Exception {
        CameraViewManager.getCamera().takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                camera.stopPreview();
                new SaveImageTask(promise).execute(data);
            }
        });
    }

    private class SaveImageTask extends AsyncTask<byte[], Void, Void> {

        private final Promise promise;

        private SaveImageTask(Promise promise) {
            this.promise = promise;
        }

        @Override
        protected Void doInBackground(byte[]... data) {
            byte[] rawImageData = data[0];
            Bitmap image = decodeAndRotateIfNeeded(rawImageData);
            String filePath = saveToMediaStore(image);
            if (filePath == null)
                promise.reject("CameraKit", "failed to save image to MediaStore");
            else {
                promise.resolve(filePath);
                CameraViewManager.reconnect();
            }
            return null;
        }

        private String saveToMediaStore(Bitmap image) {
            try {
                String fileUri = MediaStore.Images.Media.insertImage(context.getContentResolver(), image, System.currentTimeMillis() + "", "");
                Cursor cursor = context.getContentResolver().query(Uri.parse(fileUri), new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
                cursor.moveToFirst();
                String filePath = cursor.getString(0);
                cursor.close();
                return filePath;
            } catch (Exception e) {
                return null;
            }
        }

        private Bitmap decodeAndRotateIfNeeded(byte[] rawImageData) {
            Matrix bitmapMatrix = getRotationMatrix(rawImageData);
            Bitmap image = BitmapFactory.decodeByteArray(rawImageData, 0, rawImageData.length);
            if (bitmapMatrix.isIdentity())
                return image;
            else
                return rotateImage(image, bitmapMatrix);
        }

        private Bitmap rotateImage(Bitmap image, Matrix bitmapMatrix) {
            return Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), bitmapMatrix, false);
        }

        private Matrix getRotationMatrix(byte[] rawImageData) {
            try {
                return tryGetRotationMatrix(rawImageData);
            } catch (Exception e) {
                return new Matrix();
            }
        }

        private Matrix tryGetRotationMatrix(byte[] rawImageData) throws ImageProcessingException, IOException, MetadataException {
            Matrix matrix = new Matrix();
            Metadata metadata = readMetadata(rawImageData);
            final ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            boolean hasOrientation = exifIFD0Directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION);
            if (hasOrientation) {
                final int exifOrientation = exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                boolean isFacingFront = CameraViewManager.getCameraInfo().facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                convertExifOrientationToMatrix(matrix, exifOrientation, isFacingFront);
            }
            return matrix;
        }

        private void convertExifOrientationToMatrix(Matrix matrix, int exifOrientation, boolean isCameraFacingFront) {
            switch (exifOrientation) {
                case 1:
                    break;  // top left
                case 2:
                    matrix.postScale(-1, 1);
                    break;  // top right
                case 3:
                    matrix.postRotate(180);
                    break;  // bottom right
                case 4:
                    matrix.postRotate(180);
                    matrix.postScale(-1, 1);
                    break;  // bottom left
                case 5:
                    matrix.postRotate(90);
                    matrix.postScale(-1, 1);
                    break;  // left top
                case 6:
                    matrix.postRotate(90);
                    break;  // right top
                case 7:
                    matrix.postRotate(270);
                    matrix.postScale(-1, 1);
                    break;  // right bottom
                case 8:
                    matrix.postRotate(270);
                    break;  // left bottom
                default:
                    break;  // Unknown
            }
            if (isCameraFacingFront) {
                matrix.postRotate(180);
            }
        }

        private Metadata readMetadata(byte[] rawImageData) throws ImageProcessingException, IOException {
            Metadata metadata = null;
            ByteArrayInputStream inputStream = null;
            BufferedInputStream bufferedInputStream = null;
            try {
                inputStream = new ByteArrayInputStream(rawImageData);
                bufferedInputStream = new BufferedInputStream(inputStream);
                metadata = ImageMetadataReader.readMetadata(bufferedInputStream, rawImageData.length);
            } finally {
                if (bufferedInputStream != null) bufferedInputStream.close();
                if (inputStream != null) inputStream.close();
            }
            return metadata;
        }
    }
}
