package cn.bluemobi.dylan.servicetask;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v7.app.NotificationCompat;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.orhanobut.logger.Logger;
import com.yyh.lib.bsdiff.DiffUtils;
import com.yyh.lib.bsdiff.PatchUtils;

import org.xutils.common.Callback;
import org.xutils.http.RequestParams;
import org.xutils.x;

import java.io.File;
import java.io.IOException;

/**
 * Created by yuandl on 2016-12-19.
 */

public class TaskService extends Service {

    // 成功
    private static final int WHAT_SUCCESS = 1;
    // 失败
    private static final int WHAT_FAIL_PATCH = 0;
    //旧包地址
    private String srcDir = Environment.getExternalStorageDirectory().toString() + "/droidUpdate-debug.apk";
    //新包地址
    private String destDir1 = Environment.getExternalStorageDirectory().toString() + "/droidUpdate-debug-1.1.apk";
    //合成包地址
    private String destDir2 = Environment.getExternalStorageDirectory().toString() + "/droidUpdate-debug-1.2.apk";
    //patch
    private String patchDir = Environment.getExternalStorageDirectory().toString() + "/newVersion.patch";
    //更新
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    Toast.makeText(getApplicationContext(), "copy successed", Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    Toast.makeText(getApplicationContext(), "copy failured", Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Toast.makeText(getApplicationContext(), "bsdiff successed", Toast.LENGTH_SHORT).show();
                    break;
                case 3:
                    Toast.makeText(getApplicationContext(), "bsdiff failured", Toast.LENGTH_SHORT).show();
                    break;
                case 4:
                    Toast.makeText(getApplicationContext(), "更新包下载成功", Toast.LENGTH_SHORT).show();
                    install(destDir2);
                    break;
                case 5:
                    Toast.makeText(getApplicationContext(), "patch failures", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
        }
    };



    /****
     * 发送广播的请求码
     */
    private final int REQUEST_CODE_BROADCAST = 0X0001;
    /****
     * 发送广播的action
     */
    private final String BROADCAST_ACTION_CLICK = "servicetask";
    /**
     * 通知
     */
    private Notification notification;
    /**
     * 通知的Id
     */
    private final int NOTIFICATION_ID = 1;
    /**
     * 通知管理器
     */
    private NotificationManager notificationManager;
    /**
     * 通知栏的远程View
     */
    private RemoteViews mRemoteViews;
    /**
     * 下载是否可取消
     */
    private Callback.Cancelable cancelable;
    /**
     * 自定义保存路径，Environment.getExternalStorageDirectory()：SD卡的根目录
     */
    private String filePath = Environment.getExternalStorageDirectory().toString();
    private File file;

    /**
     * 通知栏操作的四种状态
     */
    private enum Status {
        DOWNLOADING, PAUSE, FAIL, SUCCESS
    }

    /**
     * 当前在状态 默认正在下载中
     */
    private Status status = Status.DOWNLOADING;
    private MyBroadcastReceiver myBroadcastReceiver;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        registerBroadCast();
        download();
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 注册按钮点击广播*
     */
    private void registerBroadCast() {
        myBroadcastReceiver = new MyBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_ACTION_CLICK);
        registerReceiver(myBroadcastReceiver, filter);
    }


    /**
     * 更新通知界面的按钮的广播
     */
    private class MyBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(BROADCAST_ACTION_CLICK)) {
                return;
            }
            Logger.d("status=" + status);
            switch (status) {
                case DOWNLOADING:
                    /**当在下载中点击暂停按钮**/
                    cancelable.cancel();
                    mRemoteViews.setTextViewText(R.id.bt, "下载");
                    mRemoteViews.setTextViewText(R.id.tv_message, "暂停中...");
                    status = Status.PAUSE;
                    notificationManager.notify(NOTIFICATION_ID, notification);
                    break;
                case SUCCESS:
                    /**当下载完成点击完成按钮时关闭通知栏**/
                    notificationManager.cancel(NOTIFICATION_ID);
                    break;
                case FAIL:
                case PAUSE:
                    /**当在暂停时点击下载按钮**/
                    download();
                    mRemoteViews.setTextViewText(R.id.bt, "暂停");
                    mRemoteViews.setTextViewText(R.id.tv_message, "下载中...");
                    status = Status.DOWNLOADING;
                    notificationManager.notify(NOTIFICATION_ID, notification);
                    break;
            }
        }
    }

    /**
     * 下载文件
     */
    private void download() {
        final String url = "http://10.58.178.89:8081/logistics/newVersion.patch";
        RequestParams requestParams = new RequestParams(url);
        String fileName = url.substring(url.lastIndexOf("/") + 1);
        file = new File(filePath, fileName);
        showNotificationProgress(TaskService.this);
        showFileName(fileName);
        requestParams.setSaveFilePath(file.getPath());
        /**自动为文件命名**/
        requestParams.setAutoRename(true);
        /**自动为文件断点续传**/
        requestParams.setAutoResume(true);

        cancelable = x.http().get(requestParams, new Callback.ProgressCallback<File>() {
            @Override
            public void onSuccess(File result) {
                Logger.d("下载完成");
                Logger.d("onSuccess");
                Logger.d("result=" + result.getPath());
                downloadSuccess();
                bspatch();
            }

            @Override
            public void onError(Throwable ex, boolean isOnCallback) {
                Logger.d("下载异常");
                downloadFail();
            }

            @Override
            public void onCancelled(CancelledException cex) {
                Logger.d("下载已取消");
            }

            @Override
            public void onFinished() {

            }

            @Override
            public void onWaiting() {

            }

            @Override
            public void onStarted() {
            }

            @Override
            public void onLoading(long total, long current, boolean isDownloading) {
                Logger.d("total=" + total + "--" + "current=" + current);
                updateNotification(total, current);
            }
        });
    }

    /**
     * 显示一个下载带进度条的通知
     *
     * @param context 上下文
     */
    public void showNotificationProgress(Context context) {
        /**进度条通知构建**/
        NotificationCompat.Builder builderProgress = new NotificationCompat.Builder(context);
        /**设置为一个正在进行的通知**/
        builderProgress.setOngoing(true);
        /**设置小图标**/
        builderProgress.setSmallIcon(R.mipmap.ic_launcher);

        /**新建通知自定义布局**/
        mRemoteViews = new RemoteViews(context.getPackageName(), R.layout.notification);
        /**进度条ProgressBar**/
        mRemoteViews.setProgressBar(R.id.pb, 100, 0, false);
        /**提示信息的TextView**/
        mRemoteViews.setTextViewText(R.id.tv_message, "下载中...");
        /**操作按钮的Button**/
        mRemoteViews.setTextViewText(R.id.bt, "暂停");
        /**设置左侧小图标*/
        mRemoteViews.setImageViewResource(R.id.iv, R.mipmap.ic_launcher);
        /**设置通过广播形式的PendingIntent**/
        Intent intent = new Intent(BROADCAST_ACTION_CLICK);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_BROADCAST, intent, 0);
        mRemoteViews.setOnClickPendingIntent(R.id.bt, pendingIntent);
        /**设置自定义布局**/
        builderProgress.setContent(mRemoteViews);
        /**设置滚动提示**/
        builderProgress.setTicker("开始下载...");
        notification = builderProgress.build();
        /**设置不可手动清除**/
        notification.flags = Notification.FLAG_NO_CLEAR;
        /**获取通知管理器**/
        notificationManager = (NotificationManager) context.getSystemService(context.NOTIFICATION_SERVICE);
        /**发送一个通知**/
        notificationManager.notify(NOTIFICATION_ID, notification);
    }


    /**
     * 在通知栏显示文件名
     *
     * @param url 下载地址
     */
    private void showFileName(String url) {
        mRemoteViews.setTextViewText(R.id.tv_name, url.substring(url.lastIndexOf("/") + 1));
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 下载更改进度
     *
     * @param total   总大小
     * @param current 当前已下载大小
     */
    private void updateNotification(long total, long current) {
        mRemoteViews.setTextViewText(R.id.tv_size, formatSize(current) + "/" + formatSize(total));
        int result = Math.round((float) current / (float) total * 100);
        mRemoteViews.setTextViewText(R.id.tv_progress, result + "%");
        mRemoteViews.setProgressBar(R.id.pb, 100, result, false);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 下载失败
     */
    private void downloadFail() {
        status = Status.FAIL;
        if (!cancelable.isCancelled()) {
            cancelable.cancel();
        }
        mRemoteViews.setTextViewText(R.id.bt, "重试");
        mRemoteViews.setTextViewText(R.id.tv_message, "下载失败");
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * 下载成功
     */
    private void downloadSuccess() {
        status = Status.SUCCESS;
        mRemoteViews.setTextViewText(R.id.bt, "完成");
        mRemoteViews.setTextViewText(R.id.tv_message, "下载完成");
        notificationManager.notify(NOTIFICATION_ID, notification);

        Logger.d("downloadSuccess");
    }

    /**
     * 格式化文件大小
     *
     * @param size
     * @return
     */
    private String formatSize(long size) {
        String format;
        if (size >= 1024 * 1024) {
            format = byteToMB(size) + "M";
        } else if (size >= 1024) {
            format = byteToKB(size) + "k";
        } else {
            format = size + "b";
        }
        return format;
    }

    /**
     * byte转换为MB
     *
     * @param bt 大小
     * @return MB
     */
    private float byteToMB(long bt) {
        int mb = 1024 * 1024;
        float f = (float) bt / (float) mb;
        float temp = (float) Math.round(f * 100.0F);
        return temp / 100.0F;
    }

    /**
     * byte转换为KB
     *
     * @param bt 大小
     * @return K
     */
    private int byteToKB(long bt) {
        return Math.round((bt / 1024));
    }

    /**
     * 销毁时取消下载，并取消注册广播，防止内存溢出
     */
    @Override
    public void onDestroy() {
        if (cancelable != null && !cancelable.isCancelled()) {
            cancelable.cancel();
        }
        if (myBroadcastReceiver != null) {
            unregisterReceiver(myBroadcastReceiver);
        }
        super.onDestroy();
    }




    public void bspatch() {
        Logger.d("OnStart");
        new PatchTask().execute();
    }
    /**
     * 差分包合成APK
     *
     * @author yuyuhang
     * @date 2016-1-25 下午12:24:34
     */
    private class PatchTask extends AsyncTask<String, Void, Integer> {

        @Override
        protected Integer doInBackground(String... params) {

            try {

                int result = PatchUtils.getInstance().patch(srcDir, destDir2, patchDir);
                if (result == 0) {
                    handler.obtainMessage(4).sendToTarget();
                    return WHAT_SUCCESS;
                } else {
                    handler.obtainMessage(5).sendToTarget();
                    return WHAT_FAIL_PATCH;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return WHAT_FAIL_PATCH;
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
        }
    }

    private void install(String dir) {
        String command = "chmod 777 " + dir;
        Runtime runtime = Runtime.getRuntime();
        try {
            runtime.exec(command); // 可执行权限
        } catch (IOException e) {
            e.printStackTrace();
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(Uri.parse("file://" + dir), "application/vnd.android.package-archive");
        startActivity(intent);
    }

    //声称差分包
    public void bsdiff(View view) {
        new DiffTask().execute();
    }

    /**
     * 生成差分包
     *
     * @author yuyuhang
     * @date 2016-1-25 下午12:24:34
     */
    private class DiffTask extends AsyncTask<String, Void, Integer> {

        @Override
        protected Integer doInBackground(String... params) {

            try {
                int result = DiffUtils.getInstance().genDiff(srcDir, destDir1, patchDir);
                if (result == 0) {
                    handler.obtainMessage(2).sendToTarget();
                    return WHAT_SUCCESS;
                } else {
                    handler.obtainMessage(3).sendToTarget();
                    return WHAT_FAIL_PATCH;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return WHAT_FAIL_PATCH;
        }

        @Override
        protected void onPostExecute(Integer integer) {
            super.onPostExecute(integer);
        }
    }

}
