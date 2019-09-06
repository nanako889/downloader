package com.qbw.downloader;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.qbw.l.L;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author qinbaowei
 * @date 2017/7/28
 * @email qbaowei@qq.com
 */

public class D {
    private static Context sContext;
    private static D sInst;
    private static L sL;

    public static void init(Context context, boolean showLog) {
        sContext = context;
        sL = new L();
        sL.setFilterTag("[downloader]");
        sL.setEnabled(showLog);
    }

    private D() {
        HandlerThread thread = new HandlerThread("xdownloader");
        thread.start();
        mBackHandler = new Handler(thread.getLooper());
        mDownloadManager = (DownloadManager) sContext.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public static D getInstance() {
        if (sInst == null) {
            synchronized (D.class) {
                if (sInst == null) {
                    sInst = new D();
                }
            }
        }
        return sInst;
    }

    private List<Listener> mListeners = new ArrayList<>();

    private Handler mUiHandler = new Handler();
    private Handler mBackHandler;

    private DownloadManager mDownloadManager;

    private List<Task> mTasks = new ArrayList<>();

    public void addTask(final Task task) {
        mBackHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mTasks) {
                    if (!hasTask(task.getDownloadUrl())) {
                        try {
                            _addTask(task);
                        } catch (Exception e) {
                            e.printStackTrace();
                            sL.e(e);
                        }
                    }
                }
            }
        });
    }

    public boolean hasTask(String downloadUrl) {
        for (Task task : mTasks) {
            if (task.getDownloadUrl().equals(downloadUrl)) {
                return true;
            }
        }
        return false;
    }

    private void _addTask(Task task) {
        //sdcard的目录下的文件夹，必须设置
        String dir = TextUtils.isEmpty(task.getFileToSaveDir()) ?
                Environment.DIRECTORY_DOWNLOADS : task
                .getFileToSaveDir();
        task.mFileToSaveDir = dir;

        File file = new File(dir, task.getFileToSaveName());
        if (task.isRemoveIfExisted()) {
            if (file.exists()) {
                if (file.delete()) {
                    sL.i("remove old file [%s,%s] success", dir, task.getFileToSaveName());
                } else {
                    sL.e("remove old file [%s,%s] failed", dir, task.getFileToSaveName());
                }
            }
        } else {
            if (file.exists()) {
                notifyListener(new Status.Existed(task));
                return;
            }
        }

        DownloadManager.Request request =
                new DownloadManager.Request(Uri.parse(task.getDownloadUrl()));
        request.setAllowedOverRoaming(false);

        request.setMimeType(MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(
                                            task.getDownloadUrl())));

        //在通知栏中显示，默认就是显示的
        request.setNotificationVisibility(task.isShowNotification() ?
                                                  DownloadManager.Request.VISIBILITY_VISIBLE :
                                                  DownloadManager.Request.VISIBILITY_HIDDEN);
        request.setVisibleInDownloadsUi(task.isShowNotification());

        request.setDestinationInExternalPublicDir(dir, task.getFileToSaveName());

        task.mDownloadId = mDownloadManager.enqueue(request);
        mTasks.add(task);
        sL.d("add download task：%s", task.toString());
        checkMonitorDownloadStatus();
    }

    public void removeTask(final Task task, final boolean removeFile) {
        mBackHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mTasks) {
                    _removeTask(task, removeFile);
                }
            }
        });
    }

    private void _removeTask(Task task, boolean removeFile) {
        for (Task t : mTasks) {
            if (t.getDownloadId() == task.getDownloadId()) {
                mTasks.remove(t);
                if (removeFile) {
                    mDownloadManager.remove(t.getDownloadId());
                }
                sL.d("remove download task：%s", t.toString());
                break;
            }
        }
    }

    private void checkMonitorDownloadStatus() {
        mBackHandler.removeCallbacks(mCheckDownloadStatusRunn);
        if (mTasks.isEmpty()) {
            sL.w("no download task exist, not loop check downlaod status");
        } else {
            mBackHandler.postDelayed(mCheckDownloadStatusRunn, 1000);
        }
    }

    private Runnable mCheckDownloadStatusRunn = new Runnable() {
        @Override
        public void run() {
            synchronized (mTasks) {
                List<Task> tasksSuccess = new ArrayList<>();
                List<Task> tasksFailed = new ArrayList<>();
                DownloadManager.Query query = new DownloadManager.Query();
                for (Task task : mTasks) {
                    query.setFilterById(task.getDownloadId());
                    Cursor cursor = mDownloadManager.query(query);
                    if (cursor.moveToFirst()) {
                        int status =
                                cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        switch (status) {
                            case DownloadManager.STATUS_PAUSED:
                                sL.w("download paused:%s", task.toString());
                                break;
                            case DownloadManager.STATUS_PENDING:
                                sL.w("download delayed:%s", task.toString());
                                break;
                            case DownloadManager.STATUS_RUNNING:
                                int soFar =
                                        cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                int total =
                                        cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                sL.v("downloading:%s,progress:%d/%d",
                                     task.toString(),
                                     soFar,
                                     total);
                                notifyListener(new Status.Progress(task, soFar, total));
                                break;
                            case DownloadManager.STATUS_SUCCESSFUL:
                                sL.i("download success:%s", task.toString());
                                tasksSuccess.add(task);
                                notifyListener(new Status.Success(task));
                                break;
                            case DownloadManager.STATUS_FAILED:
                                sL.e("download failed:%s", task.toString());
                                tasksFailed.add(task);
                                notifyListener(new Status.Failed(task));
                                break;
                            default:
                                break;
                        }
                    }
                }
                for (Task task : tasksSuccess) {
                    _removeTask(task, false);
                }
                for (Task task : tasksFailed) {
                    _removeTask(task, true);
                }
                checkMonitorDownloadStatus();
            }
        }
    };

    public static class Task {

        public static Task createInstance(Context context, String downloadUrl,
                                          String fileNameToSave) {
            Task task = new Task(downloadUrl, context.getExternalFilesDir(
                    "downloader").getAbsolutePath(), fileNameToSave, false, true);
            return task;
        }

        /**
         * 下载id由DownloadManager生成
         */
        private long mDownloadId;
        /**
         * 下载地址
         */
        private String mDownloadUrl;
        /**
         * 保存文件的文件夹名字（保存在公共外部存储）
         */
        private String mFileToSaveDir;
        /**
         * 保存文件的文件名
         */
        private String mFileToSaveName;
        /**
         * 是否显示在通知栏
         */
        private boolean mShowNotification;
        /**
         * 下载之前如果存在则删除
         */
        private boolean mRemoveIfExisted;

        private int mType;

        public Task(String downloadUrl,
                    String fileToSaveDir,
                    String fileToSaveName,
                    boolean showNotification,
                    boolean removeIfExisted) {
            mDownloadUrl = downloadUrl;
            mFileToSaveDir = fileToSaveDir;
            mFileToSaveName = fileToSaveName;
            mShowNotification = showNotification;
            mRemoveIfExisted = removeIfExisted;
        }

        public long getDownloadId() {
            return mDownloadId;
        }

        public String getDownloadUrl() {
            return mDownloadUrl;
        }

        public String getFileToSaveDir() {
            return mFileToSaveDir;
        }

        public String getFileToSaveName() {
            return mFileToSaveName;
        }

        public boolean isShowNotification() {
            return mShowNotification;
        }

        public boolean isRemoveIfExisted() {
            return mRemoveIfExisted;
        }

        public int getType() {
            return mType;
        }

        public Task setType(int type) {
            mType = type;
            return this;
        }

        public String getFullPath() {
            return mFileToSaveDir + "/" + mFileToSaveName;
        }

        @Override
        public String toString() {
            return String.format(Locale.getDefault(), "download id[%d], download url[%s], " +
                                         "file path to save[%s]",
                                 mDownloadId, mDownloadUrl, getFullPath());
        }
    }

    public static class Status {

        private Task mTask;

        public Status(Task task) {
            mTask = task;
        }

        public static class Progress extends Status {

            private int mDownloadSize;
            private int mTotalSize;

            public Progress(Task task, int downloadSize, int totalSize) {
                super(task);
                mDownloadSize = downloadSize;
                mTotalSize = totalSize;
            }

            public int getDownloadSize() {
                return mDownloadSize;
            }

            public int getTotalSize() {
                return mTotalSize;
            }
        }

        public static class Success extends Status {

            public Success(Task task) {
                super(task);
            }
        }

        public static class Failed extends Status {

            public Failed(Task task) {
                super(task);
            }
        }

        public static class Existed extends Status {

            public Existed(Task task) {
                super(task);
            }
        }

        public Task getTask() {
            return mTask;
        }
    }

    public void addListener(Listener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    private void notifyListener(final Status status) {
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mListeners) {
                    int s = mListeners.size();
                    for (int i = 0; i < s; i++) {
                        if (mListeners.get(i).onDownload(status)) {
                            break;
                        }
                    }
                }
            }
        });
    }

    public interface Listener {
        boolean onDownload(Status status);
    }
}
