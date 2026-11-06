package net.ossrs.yasea.demo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Saves finished recordings into the shared Movies/Yasea collection so they show
 * up in every gallery and file manager, and survive uninstalling the app.
 *
 * The mp4 muxer needs a seekable file (it rewinds to patch the mdat atom and
 * writes moov last), but MediaStore only hands out a forward-only stream. So the
 * recording is written to a temporary file as before and only moved here once the
 * muxer has finished and the file is complete and playable.
 */
public final class MovieSaver {

    private static final String TAG = "MovieSaver";
    private static final String ALBUM = "StreamMaster";
    private static final String MIME = "video/mp4";

    private MovieSaver() {
    }

    /**
     * Publishes a finished recording to Movies/Yasea.
     *
     * @return the display path shown to the user, or null when the copy failed.
     */
    public static String publish(Context context, File source, String displayName) {
        if (source == null || !source.exists() || source.length() == 0) {
            Log.w(TAG, "nothing to publish: " + source);
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishViaMediaStore(context, source, displayName);
        }
        return publishViaFile(context, source, displayName);
    }

    /** API 29+: insert into MediaStore with RELATIVE_PATH, then stream the bytes in. */
    private static String publishViaMediaStore(Context context, File source, String displayName) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, MIME);
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + ALBUM);
        // Hide the entry until the copy is complete, otherwise a gallery can pick up
        // a half-written mp4 and show it as corrupt.
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri item = null;
        try {
            item = resolver.insert(collection, values);
            if (item == null) {
                Log.e(TAG, "MediaStore refused the insert");
                return null;
            }
            OutputStream out = resolver.openOutputStream(item);
            if (out == null) {
                Log.e(TAG, "cannot open output stream for " + item);
                resolver.delete(item, null, null);
                return null;
            }
            try {
                copy(source, out);
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // the copy result already tells us whether this mattered
                }
            }
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "failed to publish to MediaStore", e);
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (Exception ignored) {
                    // best effort cleanup
                }
            }
            return null;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(item, done, null, null);

        // The temp file has served its purpose.
        if (!source.delete()) {
            Log.w(TAG, "could not delete temp file " + source);
        }
        return Environment.DIRECTORY_MOVIES + "/" + ALBUM + "/" + displayName;
    }

    /**
     * API 21-28: no RELATIVE_PATH, so write straight to the public Movies directory.
     * Requires WRITE_EXTERNAL_STORAGE, which the caller must have requested.
     */
    private static String publishViaFile(Context context, File source, String displayName) {
        File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File dir = new File(movies, ALBUM);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return null;
        }

        File target = new File(dir, displayName);
        try {
            copy(source, new FileOutputStream(target));
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "failed to copy to " + target, e);
            return null;
        }

        // Let the gallery index it without waiting for a reboot.
        android.media.MediaScannerConnection.scanFile(
                context, new String[] { target.getAbsolutePath() }, new String[] { MIME }, null);

        if (!source.delete()) {
            Log.w(TAG, "could not delete temp file " + source);
        }
        return target.getAbsolutePath();
    }

    private static void copy(File source, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(source);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // reading is done; a close failure does not affect the result
            }
        }
    }
}
