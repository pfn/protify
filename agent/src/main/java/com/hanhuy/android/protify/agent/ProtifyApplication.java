package com.hanhuy.android.protify.agent;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import com.hanhuy.android.protify.agent.internal.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * some of this is straight up ripped off from bazelbuild's StubApplication
 * https://github.com/bazelbuild/bazel/blob/3eb0687fde3745cf52bbbb513f7769ecb9d004e4/src/tools/android/java/com/google/devtools/build/android/incrementaldeployment/StubApplication.java
 * @author pfnguyen
 */
@SuppressWarnings("unused")
public class ProtifyApplication extends Application {
    private final static String TAG = "ProtifyApplication";
    private final String realApplicationClass;
    private Application realApplication;
    private final static int NOTIFICATION_ID = 0x70726f74; // = "prot"

    public ProtifyApplication() {
        String[] applicationInfo = getResourceAsString("protify_application_info.txt").split("\n");
        realApplicationClass = applicationInfo[0].trim();
        Log.d(TAG, "Real application class: [" + realApplicationClass + "]");
        Protify.installed = true;
    }

    @SuppressWarnings("deprecation")
    private static Notification loadingNotification(Context c, String text) {
        final Notification n;
        // R is filtered out of DEX, find the resource manually
        int icon = c.getResources().getIdentifier(
                "protify_internal_ic_notification_loading", "drawable", c.getPackageName());
        if (icon == 0)
            throw new IllegalStateException(
                    "protify_internal_ic_notification_loading not found");
        if (Build.VERSION.SDK_INT >= 14) {
            final Notification.Builder nb = new Notification.Builder(c);
            nb
                    .setContentTitle(text)
                    .setSmallIcon(icon)
                    .setProgress(100, 0, true)
                    .setOngoing(true);
            n = nb.getNotification();
        } else {
            n = new Notification();
            n.icon = icon;
            n.flags = Notification.FLAG_ONGOING_EVENT;
            n.tickerText = text;
            n.setLatestEventInfo(c, text, null,
                    PendingIntent.getBroadcast(c, 0, new Intent(
                            "com.hanhuy.android.protify.internal.action.NOOP"),
                            0));
        }
        n.when = System.currentTimeMillis();
        return n;
    }

    private Object stashedContentProviders;
    private static Field getField(Object instance, String fieldName)
            throws ClassNotFoundException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // IllegalStateException will be thrown below
            }
        }

        throw new IllegalStateException("Field '" + fieldName + "' not found");
    }

    private void enableContentProviders() {
        Log.v(TAG, "enableContentProviders");
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method mCurrentActivityThread = activityThread.getMethod("currentActivityThread");
            mCurrentActivityThread.setAccessible(true);
            Object currentActivityThread = mCurrentActivityThread.invoke(null);
            Object boundApplication = getField(
                    currentActivityThread, "mBoundApplication").get(currentActivityThread);
            getField(boundApplication, "providers").set(boundApplication, stashedContentProviders);
            if (stashedContentProviders != null) {
                Method mInstallContentProviders = activityThread.getDeclaredMethod(
                        "installContentProviders", Context.class, List.class);
                mInstallContentProviders.setAccessible(true);
                mInstallContentProviders.invoke(
                        currentActivityThread, realApplication, stashedContentProviders);
                stashedContentProviders = null;
            }
        } catch (Exception e) {
            if (stashedContentProviders != null) {
                Log.e(TAG, "ContentProviders stashed, but unable to restore");
                throw new IllegalStateException(e);
            }
        }
    }
    private void disableContentProviders() {
        Log.v(TAG, "disableContentProviders");
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method mCurrentActivityThread = activityThread.getMethod("currentActivityThread");
            mCurrentActivityThread.setAccessible(true);
            Object currentActivityThread = mCurrentActivityThread.invoke(null);
            Object boundApplication = getField(
                    currentActivityThread, "mBoundApplication").get(currentActivityThread);
            Field fProviders = getField(boundApplication, "providers");

            stashedContentProviders = fProviders.get(boundApplication);
            fProviders.set(boundApplication, null);
        } catch (Exception e) {
            Log.e(TAG, "Unable to inject Application for ContentProviders");
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        NotificationManager nm = (NotificationManager) base.getSystemService(
                NOTIFICATION_SERVICE);
        CharSequence name;
        try {
            name = base.getResources().getText(base.getApplicationInfo().labelRes);
        } catch (Resources.NotFoundException e) {
            name = base.getPackageName();
        }
        nm.notify(NOTIFICATION_ID, loadingNotification(
                base, "Protifying DEX for " + name));
        DexLoader.install(base);
        nm.cancel(NOTIFICATION_ID);

        createRealApplication();
        super.attachBaseContext(base);

        try {
            Method attachBaseContext = ContextWrapper.class.getDeclaredMethod(
                    "attachBaseContext", Context.class);
            attachBaseContext.setAccessible(true);
            attachBaseContext.invoke(realApplication, base);

            disableContentProviders();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        if (Build.VERSION.SDK_INT >= 14) {
            realApplication.registerActivityLifecycleCallbacks(
                    LifecycleListener.getInstance());
        }
    }

    @Override
    public void onCreate() {
        installRealApplication();
        installExternalResources(this);
        enableContentProviders();
        super.onCreate();
        realApplication.onCreate();
    }

    @SuppressWarnings("unchecked")
    private void installRealApplication() {
        // StubApplication is created by reflection in Application#handleBindApplication() ->
        // LoadedApk#makeApplication(), and its return value is used to set the Application field in all
        // sorts of Android internals.
        //
        // Fortunately, Application#onCreate() is called quite soon after, so what we do is monkey
        // patch in the real Application instance in StubApplication#onCreate().
        //
        // A few places directly use the created Application instance (as opposed to the fields it is
        // eventually stored in). Fortunately, it's easy to forward those to the actual real
        // Application class.
        try {
            // Find the ActivityThread instance for the current thread
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method m = activityThread.getMethod("currentActivityThread");
            m.setAccessible(true);
            Object currentActivityThread = m.invoke(null);

            // Find the mInitialApplication field of the ActivityThread to the real application
            Field mInitialApplication = activityThread.getDeclaredField("mInitialApplication");
            mInitialApplication.setAccessible(true);
            Application initialApplication = (Application) mInitialApplication.get(currentActivityThread);
            if (initialApplication == this) {
                mInitialApplication.set(currentActivityThread, realApplication);
            }

            // Replace all instance of the stub application in ActivityThread#mAllApplications with the
            // real one
            Field mAllApplications = activityThread.getDeclaredField("mAllApplications");
            mAllApplications.setAccessible(true);
            List<Application> allApplications = (List<Application>) mAllApplications
                    .get(currentActivityThread);
            for (int i = 0; i < allApplications.size(); i++) {
                if (allApplications.get(i) == this) {
                    allApplications.set(i, realApplication);
                }
            }

            // Figure out how loaded APKs are stored.

            // API version 8 has PackageInfo, 10 has LoadedApk. 9, I don't know.
            Class<?> loadedApkClass;
            try {
                loadedApkClass = Class.forName("android.app.LoadedApk");
            } catch (ClassNotFoundException e) {
                loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
            }
            Field mApplication = loadedApkClass.getDeclaredField("mApplication");
            mApplication.setAccessible(true);
            Field mResDir = loadedApkClass.getDeclaredField("mResDir");
            mResDir.setAccessible(true);

            // 10 doesn't have this field, 14 does. Fortunately, there are not many Honeycomb devices
            // floating around.
            Field mLoadedApk = null;
            try {
                mLoadedApk = Application.class.getDeclaredField("mLoadedApk");
            } catch (NoSuchFieldException e) {
                // According to testing, it's okay to ignore this.
            }

            // Enumerate all LoadedApk (or PackageInfo) fields in ActivityThread#mPackages and
            // ActivityThread#mResourcePackages and do two things:
            //   - Replace the Application instance in its mApplication field with the real one
            //   - Replace mResDir to point to the external resource file instead of the .apk. This is
            //     used as the asset path for new Resources objects.
            //   - Set Application#mLoadedApk to the found LoadedApk instance
            for (String fieldName : new String[] { "mPackages", "mResourcePackages" }) {
                Field field = activityThread.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(currentActivityThread);

                for (Map.Entry<String, WeakReference<?>> entry :
                        ((Map<String, WeakReference<?>>) value).entrySet()) {
                    Object loadedApk = entry.getValue().get();
                    if (loadedApk == null) {
                        continue;
                    }

                    if (mApplication.get(loadedApk) == this) {
                        File[] fs = ProtifyResources.getResourceFiles(this);
                        deleteDirtyExternalResources(this, fs);
                        mApplication.set(loadedApk, realApplication);
                        if (fs.length > 1) {
                            Field mSplitResDirs = loadedApkClass.getDeclaredField("mSplitResDirs");
                            mSplitResDirs.setAccessible(true);
                            String[] ss = new String[fs.length];
                            for (int i = 0; i < fs.length; i++) {
                                ss[i] = fs[i].getAbsolutePath();
                            }
                            mSplitResDirs.set(loadedApk, ss);
                        } else if (fs.length == 0 && fs[0].isFile()) {
                            Log.v(TAG, "Setting mResDir to: " + fs[0].getAbsolutePath());
                            mResDir.set(loadedApk, fs[0].getAbsolutePath());
                        }

                        if (mLoadedApk != null) {
                            mLoadedApk.set(realApplication, loadedApk);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteDirtyExternalResources(Context context, File[] fs) {
        ApplicationInfo info = context.getApplicationInfo();
        long sourceApkModified = new File(info.sourceDir).lastModified();
        boolean dirty = false;
        for (int i = 0; i < fs.length && !dirty; i++) {
            dirty = sourceApkModified > fs[i].lastModified();
        }
        if (dirty) {
            Log.v(TAG, "Deleting outdated external resources");
            for (File f : fs) f.delete();
        }
    }
    public static void installExternalResources(Context context) {
        File[] fs = ProtifyResources.getResourceFiles(context);
        deleteDirtyExternalResources(context, fs);
        boolean nonEmpty = true;
        for (int i = 0; i < fs.length && nonEmpty; i++) {
            nonEmpty = fs[i].isFile() && fs[i].length() > 0;
        }
        if (nonEmpty) {
            Log.v(TAG, "Installing external resource file: " + fs);
            if (Build.VERSION.SDK_INT >= 24)
                V24Resources.install(fs);
            else if (Build.VERSION.SDK_INT >= 18)
                V19Resources.install(fs);
            else
                V4Resources.install(fs);
            resourceInstallTime = System.currentTimeMillis();
        }
    }

    private static long resourceInstallTime = System.currentTimeMillis();

    public static long getResourceInstallTime() {
        return resourceInstallTime;
    }

    private String getResourceAsString(String resource) {
        InputStream resourceStream = null;
        // try-with-resources would be much nicer, but that requires SDK level 19, and we want this code
        // to be compatible with earlier Android versions
        try {
            resourceStream = getClass().getClassLoader().getResourceAsStream(resource);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = resourceStream.read(buffer)) != -1) {
                baos.write(buffer, 0, length);
            }

            return new String(baos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (resourceStream != null) {
                try {
                    resourceStream.close();
                } catch (IOException e) {
                    // Not much we can do here
                }
            }
        }
    }

    private void createRealApplication() {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Application> realClass =
                    (Class<? extends Application>) Class.forName(realApplicationClass);
            Constructor<? extends Application> ctor = realClass.getConstructor();
            realApplication = ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class V24Resources {
        @TargetApi(24)
        static void install(File[] externalResourceFiles) {
            try {
                AssetManager newAssetManager = createAssetManager(externalResourceFiles);

                // Find the singleton instance of ResourcesManager
                Class<?> clazz = Class.forName("android.app.ResourcesManager");
                Method mGetInstance = clazz.getDeclaredMethod("getInstance");
                mGetInstance.setAccessible(true);
                Object resourcesManager = mGetInstance.invoke(null);

                // Iterate over all known Resources objects
                Field mResourceReferences = clazz.getDeclaredField("mResourceReferences");
                mResourceReferences.setAccessible(true);
                @SuppressWarnings("unchecked")
                Collection<WeakReference<Resources>> references =
                        (Collection<WeakReference<Resources>>) mResourceReferences.get(resourcesManager);

                setAssetManager(references, newAssetManager);
            } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException |
                    ClassNotFoundException | InvocationTargetException | InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
    private static class V19Resources {
        @TargetApi(19)
        static void install(File[] externalResourceFiles) {
            try {
                AssetManager newAssetManager = createAssetManager(externalResourceFiles);

                // Find the singleton instance of ResourcesManager
                Class<?> clazz = Class.forName("android.app.ResourcesManager");
                Method mGetInstance = clazz.getDeclaredMethod("getInstance");
                mGetInstance.setAccessible(true);
                Object resourcesManager = mGetInstance.invoke(null);

                // Iterate over all known Resources objects
                Field fMActiveResources = clazz.getDeclaredField("mActiveResources");
                fMActiveResources.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<?, WeakReference<Resources>> arrayMap =
                        (Map<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
                setAssetManager(arrayMap, newAssetManager);
            } catch (IllegalAccessException | NoSuchFieldException | NoSuchMethodException |
                    ClassNotFoundException | InvocationTargetException | InstantiationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
    private static class V4Resources {
        static void install(File[] externalResourceFiles) {
            try {
                AssetManager newAssetManager = createAssetManager(externalResourceFiles);

                // Find the singleton instance of ResourcesManager
                Class<?> clazz = Class.forName("android.app.ActivityThread");
                Method mGetInstance = clazz.getDeclaredMethod("currentActivityThread");
                mGetInstance.setAccessible(true);
                Object resourcesManager = mGetInstance.invoke(null);

                // Iterate over all known Resources objects
                Field fMActiveResources = clazz.getDeclaredField("mActiveResources");
                fMActiveResources.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<?, WeakReference<Resources>> arrayMap =
                        (Map<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
                setAssetManager(arrayMap, newAssetManager);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

    }

    private static AssetManager createAssetManager(File[] externalResourceFiles)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, InstantiationException {
        // Create a new AssetManager instance and point it to the resources installed under
        // /sdcard
        AssetManager newAssetManager = AssetManager.class.getConstructor().newInstance();
        Method mAddAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        mAddAssetPath.setAccessible(true);
        for (File externalResourceFile : externalResourceFiles) {
            if (((int) mAddAssetPath.invoke(newAssetManager, externalResourceFile.getAbsolutePath())) == 0) {
                throw new IllegalStateException("Could not create new AssetManager");
            }
        }

        // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
        // in L, so we do it unconditionally.
        Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod("ensureStringBlocks");
        mEnsureStringBlocks.setAccessible(true);
        mEnsureStringBlocks.invoke(newAssetManager);
        return newAssetManager;
    }

    private static void setAssetManager(Map<?,WeakReference<Resources>> arrayMap, AssetManager newAssetManager) throws IllegalAccessException, NoSuchFieldException {
        setAssetManager(arrayMap.values(), newAssetManager);
    }

    private static void setAssetManager(Collection<WeakReference<Resources>> ress, AssetManager newAssetManager) throws IllegalAccessException, NoSuchFieldException {
        for (WeakReference<Resources> wr : ress) {
            Resources resources = wr.get();
            // Set the AssetManager of the Resources instance to our brand new one
            if (resources != null) {
                setAssetsField(resources, newAssetManager);
                resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
            }
        }
    }

    private static void setAssetsField(Resources resources, AssetManager newAssetManager) {
        Field mAssets;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                Field mResourcesImplField;
                mResourcesImplField = Resources.class.getDeclaredField("mResourcesImpl");
                mResourcesImplField.setAccessible(true);
                Object mResourceImpl = mResourcesImplField.get(resources);
                mAssets = mResourceImpl.getClass().getDeclaredField("mAssets");
                mAssets.setAccessible(true);
                mAssets.set(mResourceImpl, newAssetManager);
            } else {
                mAssets = Resources.class.getDeclaredField("mAssets");
                mAssets.setAccessible(true);
                mAssets.set(resources, newAssetManager);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
