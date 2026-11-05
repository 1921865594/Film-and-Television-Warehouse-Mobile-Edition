package com.github.tvbox.osc.base;

import android.app.Activity;
import android.content.Context;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.HomeSkeletonCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.activity.CloudCredentialStore;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.GlobalCrashLogger;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    protected void attachBaseContext(Context base) {
        // Install diagnostics before MultiDexApplication.attachBaseContext and before onCreate.
        // This also gives the logger a chance to record failures during MultiDex installation.
        GlobalCrashLogger.initialize(base);
        super.attachBaseContext(base);
        GlobalCrashLogger.attachApplication(this);
        GlobalCrashLogger.mark("Application.attachBaseContext completed");
    }

    @Override
    public void onCreate() {
        GlobalCrashLogger.mark("App.onCreate entered");
        super.onCreate();
        GlobalCrashLogger.mark("MultiDexApplication.onCreate completed");
        instance = this;
        CloudCredentialStore.init(this);
        GlobalCrashLogger.mark("initParams begin");
        initParams();
        GlobalCrashLogger.mark("initParams completed");
        // 必须在任何 Activity 创建之前把明暗模式定下来，否则首个页面会先按默认色渲染再重建
        GlobalCrashLogger.mark("ThemeUtil.applyMode begin");
        com.github.tvbox.osc.util.ThemeUtil.applyMode();
        GlobalCrashLogger.mark("ThemeUtil.applyMode completed");
        // OKGo
        GlobalCrashLogger.mark("OkGoHelper.init begin");
        OkGoHelper.init(); //台标获取
        GlobalCrashLogger.mark("OkGoHelper.init completed");
        GlobalCrashLogger.mark("EpgUtil.init begin");
        EpgUtil.init();
        GlobalCrashLogger.mark("EpgUtil.init completed");
        // 初始化Web服务器
        GlobalCrashLogger.mark("ControlManager.init begin");
        ControlManager.init(this);
        GlobalCrashLogger.mark("ControlManager.init completed");
        //初始化数据库
        GlobalCrashLogger.mark("AppDataManager.init begin");
        AppDataManager.init();
        GlobalCrashLogger.mark("AppDataManager.init completed");
        GlobalCrashLogger.mark("LoadSir initialization begin");
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .addCallback(new HomeSkeletonCallback())
                .commit();
        GlobalCrashLogger.mark("LoadSir initialization completed");
        // 手机端：关闭 MM 子系统，使用原生 dp/sp，并由 AutoSize 按 360 宽基准做响应式缩放
        GlobalCrashLogger.mark("AutoSize initialization begin");
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(true)
                .setSupportSP(true)
                .setSupportSubunits(Subunits.NONE);
        GlobalCrashLogger.mark("AutoSize initialization completed");
        GlobalCrashLogger.mark("PlayerHelper.init begin");
        PlayerHelper.init();
        GlobalCrashLogger.mark("PlayerHelper.init completed");
        GlobalCrashLogger.mark("FileUtils.cleanPlayerCache begin");
        FileUtils.cleanPlayerCache();
        GlobalCrashLogger.mark("FileUtils.cleanPlayerCache completed; Application startup finished");
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        } else if (Hawk.get(HawkConfig.PLAY_TYPE, 2) == 0) {
            Hawk.put(HawkConfig.PLAY_TYPE, 2);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        GlobalCrashLogger.mark("Application.onTerminate");
        GlobalCrashLogger.flush();
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}