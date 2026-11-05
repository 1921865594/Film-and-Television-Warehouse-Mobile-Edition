package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.adapter.BackupAdapter;
import com.github.tvbox.osc.util.FileUtils;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

public class BackupDialog extends BaseDialog {

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_backup);
        TvRecyclerView tvRecyclerView = ((TvRecyclerView) findViewById(R.id.list));
        BackupAdapter adapter = new BackupAdapter();
        tvRecyclerView.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    restore((String) adapter.getItem(position));
                }
                else if (view.getId() == R.id.tvDel) {
                    delete((String) adapter.getItem(position));
                    adapter.setNewData(allBackup());
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backup();
                adapter.setNewData(allBackup());
            }
        });
        findViewById(R.id.storagePermission).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (XXPermissions.isGranted(getContext(), Permission.Group.STORAGE)) {
                    Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                } else {
                    XXPermissions.with(getContext())
                            .permission(Permission.Group.STORAGE)
                            .request(new OnPermissionCallback() {
                                @Override
                                public void onGranted(List<String> permissions, boolean all) {
                                    if (all) {
                                        adapter.setNewData(allBackup());
                                        Toast.makeText(getContext(), "已获得存储权限", Toast.LENGTH_SHORT).show();
                                    }
                                }

                                @Override
                                public void onDenied(List<String> permissions, boolean never) {
                                    if (never) {
                                        Toast.makeText(getContext(), "获取存储权限失败,请在系统设置中开启", Toast.LENGTH_SHORT).show();
                                        XXPermissions.startPermissionActivity((Activity) getContext(), permissions);
                                    } else {
                                        Toast.makeText(getContext(), "获取存储权限失败", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                }
            }
        });
    }

    List<String> allBackup() {
        ArrayList<String> result = new ArrayList<>();
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            File[] list = file.listFiles();
            Arrays.sort(list, new Comparator<File>() {
                @Override
                public int compare(File o1, File o2) {
                    if (o1.isDirectory() && o2.isFile()) return -1;
                    return o1.isFile() && o2.isDirectory() ? 1 : o2.getName().compareTo(o1.getName());
                }
            });
            if (file.exists()) {
                for (File f : list) {
                    if (result.size() > 10) {
                        FileUtils.recursiveDelete(f);
                        continue;
                    }
                    if (f.isDirectory()) {
                        result.add(f.getName());
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return result;
    }

    void restore(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            if (!backup.exists()) return;

            byte[] data = FileUtils.readSimple(new File(backup, "hawk"));
            if (data == null) {
                Toast.makeText(getContext(), "Hawk恢复失败!", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject jsonObject = new JSONObject(new String(data, "UTF-8"));
            // 先完成配置文件解析，再恢复数据库，避免损坏的配置文件造成不完整导入。
            Iterator<String> validate = jsonObject.keys();
            while (validate.hasNext()) {
                Object value = jsonObject.opt(validate.next());
                if (value == null || value == JSONObject.NULL) {
                    throw new IllegalArgumentException("Hawk备份包含空值");
                }
            }

            File db = new File(backup, "sqlite");
            if (!AppDataManager.restore(db)) {
                Toast.makeText(getContext(), "DB文件恢复失败!", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences hawkPrefs = App.getInstance()
                    .getSharedPreferences("Hawk2", Context.MODE_PRIVATE);
            SharedPreferences cryptoPrefs = App.getInstance()
                    .getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE);
            SharedPreferences.Editor hawkEditor = hawkPrefs.edit().clear();
            SharedPreferences.Editor cryptoEditor = cryptoPrefs.edit().clear();
            Iterator<String> it = jsonObject.keys();
            while (it.hasNext()) {
                String key = it.next();
                String value = jsonObject.getString(key);
                if ("cipher_key".equals(key)) {
                    cryptoEditor.putString(key, value);
                } else {
                    hawkEditor.putString(key, value);
                }
            }
            boolean hawkSaved = hawkEditor.commit();
            boolean cryptoSaved = cryptoEditor.commit();
            if (!hawkSaved || !cryptoSaved) {
                Toast.makeText(getContext(), "配置写入失败!", Toast.LENGTH_SHORT).show();
                return;
            }

            File wallpaperBackup = new File(backup, "wp");
            File wallpaperTarget = new File(App.getInstance().getFilesDir(), "wp");
            if (wallpaperBackup.isFile()) {
                copyFile(wallpaperBackup, wallpaperTarget);
            } else if (wallpaperTarget.exists() && !wallpaperTarget.delete()) {
                Toast.makeText(getContext(), "壁纸恢复失败!", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(getContext(), "恢复成功,即将自动重启应用!", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    restartApp();
                }
            }, 3000);
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "恢复失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void restartApp() {
        Context context = getContext();
        if (context != null) {
            Intent i = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(i);
                System.exit(0);
            }
        }
    }
    void backup() {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            if (!file.exists())
                file.mkdirs();
            Date now = new Date();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd-HHmmss");
            File backup = new File(file, f.format(now));
            backup.mkdirs();
            File db = new File(backup, "sqlite");
            if (AppDataManager.backup(db)) {
                SharedPreferences sharedPreferences = App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE);
                JSONObject jsonObject = new JSONObject();
                for (String key : sharedPreferences.getAll().keySet()) {
                    jsonObject.put(key, sharedPreferences.getString(key, ""));
                }
                sharedPreferences = App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE);
                for (String key : sharedPreferences.getAll().keySet()) {
                    jsonObject.put(key, sharedPreferences.getString(key, ""));
                }
                if (!FileUtils.writeSimple(jsonObject.toString().getBytes("UTF-8"), new File(backup, "hawk"))) {
                    backup.delete();
                    Toast.makeText(getContext(), "备份Hawk失败!", Toast.LENGTH_SHORT).show();
                } else {
                    File wallpaper = new File(App.getInstance().getFilesDir(), "wp");
                    if (wallpaper.isFile()) copyFile(wallpaper, new File(backup, "wp"));
                    Toast.makeText(getContext(), "备份成功!", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getContext(), "DB文件不存在!", Toast.LENGTH_SHORT).show();
                backup.delete();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "备份失败!", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建备份目录");
        }
        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = new FileOutputStream(destination);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        } finally {
            try { input.close(); } finally { output.close(); }
        }
    }

    void delete(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            FileUtils.recursiveDelete(backup);
            Toast.makeText(getContext(), "删除成功!", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}