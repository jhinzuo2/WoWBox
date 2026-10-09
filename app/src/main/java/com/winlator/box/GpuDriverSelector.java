package com.winlator.box;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.contents.AdrenotoolsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chooses between the bundled Turnip (Adreno) driver and the RADV Xclipse driver (Samsung Xclipse).
 *
 * The RADV package is an Adrenotools-style bundle (meta.json + vulkan.radeon.so). It is not shipped
 * inside the APK; it is downloaded once on demand, verified against a pinned SHA-256 and unpacked to
 * contents/adrenotools/<RADV_DRIVER_ID>/, which is exactly where AdrenotoolsManager looks for drivers.
 */
public final class GpuDriverSelector {
    private static final String TAG = "GpuDriverSelector";

    public static final String PREF_GPU_DRIVER = "gpu_driver_choice";
    public static final String CHOICE_AUTO = "auto";
    public static final String CHOICE_TURNIP = "turnip";
    public static final String CHOICE_RADV = "radv";

    public static final String RADV_DRIVER_ID = "radv-xclipse-26.3.0-devel-313870e";
    public static final String RADV_DOWNLOAD_URL =
            "https://github.com/JimVulkan/radv-xclipse/releases/download/v1.5.0/radv-xclipse-26.3.0-devel-313870e.zip";
    public static final String RADV_SHA256 =
            "b9e34d18150df1c7644078306e6e6bb271d8fdc225ca87fda4cd525f19a07486";
    // meta.json of the package says minApi 34.
    public static final int RADV_MIN_API = 34;

    private static final long RADV_MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L;

    private GpuDriverSelector() {}

    public static String getChoice(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String choice = preferences.getString(PREF_GPU_DRIVER, CHOICE_AUTO);
        if (CHOICE_TURNIP.equals(choice) || CHOICE_RADV.equals(choice)) return choice;
        return CHOICE_AUTO;
    }

    public static void setChoice(SharedPreferences.Editor editor, String choice) {
        editor.putString(PREF_GPU_DRIVER, choice);
    }

    /** True on Samsung Xclipse GPUs (Exynos 2200 / 2400 / 2500 and friends). */
    public static boolean isXclipse(Context context) {
        try {
            String renderer = GPUInformation.getRenderer(context);
            if (renderer != null && !renderer.isEmpty()) {
                return renderer.toLowerCase(Locale.ENGLISH).contains("xclipse");
            }
        }
        catch (Throwable t) {
            Log.w(TAG, "GPU renderer query failed", t);
        }
        String soc = (Build.HARDWARE + " " + Build.BOARD).toLowerCase(Locale.ENGLISH);
        return soc.contains("s5e9925") || soc.contains("s5e9945") || soc.contains("s5e9955");
    }

    public static boolean isRadvSupportedByOs() {
        return Build.VERSION.SDK_INT >= RADV_MIN_API;
    }

    public static boolean isRadvInstalled(Context context) {
        AdrenotoolsManager manager = new AdrenotoolsManager(context);
        File library = manager.getDriverLibraryFile(RADV_DRIVER_ID);
        return library != null && library.isFile();
    }

    /** True when the user's choice (or auto-detection) says RADV should be used. */
    public static boolean wantsRadv(Context context) {
        if (!isRadvSupportedByOs()) return false;
        String choice = getChoice(context);
        if (CHOICE_RADV.equals(choice)) return true;
        if (CHOICE_TURNIP.equals(choice)) return false;
        return isXclipse(context);
    }

    /** True when RADV is wanted and ready to be loaded. */
    public static boolean isRadvActive(Context context) {
        return wantsRadv(context) && isRadvInstalled(context);
    }

    /**
     * Returns the Adrenotools driver id to load. Falls back to the given (Turnip) id whenever RADV is
     * not selected, not supported, or not installed yet.
     */
    public static String resolveDriverId(Context context, String fallbackDriverId) {
        return isRadvActive(context) ? RADV_DRIVER_ID : fallbackDriverId;
    }

    /**
     * Makes sure the RADV package is present if it is wanted. Blocking (may download); call from a
     * background thread. Returns true if RADV will be used for this session.
     */
    public static boolean prepare(Context context) {
        if (!wantsRadv(context)) return false;
        if (isRadvInstalled(context)) return true;
        Log.i(TAG, "RADV Xclipse driver wanted but not installed, downloading " + RADV_DOWNLOAD_URL);
        boolean ok = installRadv(context);
        Log.i(TAG, "RADV Xclipse driver install " + (ok ? "ok" : "FAILED, falling back to Turnip"));
        return ok;
    }

    public static synchronized boolean installRadv(Context context) {
        File zipFile = new File(context.getCacheDir(), RADV_DRIVER_ID + ".zip.part");
        AdrenotoolsManager manager = new AdrenotoolsManager(context);
        File finalDir = manager.getDriverDir(RADV_DRIVER_ID);
        File stageDir = new File(finalDir.getParentFile(), RADV_DRIVER_ID + ".tmp");
        try {
            download(RADV_DOWNLOAD_URL, zipFile);

            String actual = sha256(zipFile);
            if (!RADV_SHA256.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch, expected " + RADV_SHA256 + " got " + actual);
            }

            FileUtils.delete(stageDir);
            if (!stageDir.mkdirs()) throw new IOException("cannot create " + stageDir);
            unzip(zipFile, stageDir);

            JSONObject meta = new JSONObject(FileUtils.readString(new File(stageDir, "meta.json")));
            String libraryName = meta.optString("libraryName", "");
            if (libraryName.isEmpty() || !new File(stageDir, libraryName).isFile()) {
                throw new IOException("driver library missing in package");
            }
            int minApi = meta.optInt("minApi", 0);
            if (minApi > Build.VERSION.SDK_INT) {
                throw new IOException("driver needs API " + minApi + ", device is " + Build.VERSION.SDK_INT);
            }

            FileUtils.delete(finalDir);
            if (!stageDir.renameTo(finalDir)) throw new IOException("cannot move driver into place");
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "RADV Xclipse driver install failed", e);
            FileUtils.delete(stageDir);
            return false;
        }
        finally {
            zipFile.delete();
        }
    }

    private static void download(String address, File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "WoWBox");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long total = 0;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > RADV_MAX_DOWNLOAD_BYTES) throw new IOException("download too large");
                    out.write(buffer, 0, read);
                }
            }
        }
        finally {
            connection.disconnect();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void unzip(File zipFile, File targetDir) throws IOException {
        String targetPath = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(targetPath)) {
                    throw new IOException("unsafe zip entry " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) os.write(buffer, 0, read);
                }
            }
        }
    }
}
