package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 扫描 TVBox 推送端口二维码，识别成功后自动进入端口页面。 */
@SuppressWarnings("deprecation")
public class ScanActivity extends BaseActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private SurfaceView preview;
    private SurfaceHolder surfaceHolder;
    private TextView tvHint;
    private Camera camera;
    private boolean surfaceReady;
    private boolean cameraPermissionGranted;
    private boolean resultShown;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final MultiFormatReader reader = new MultiFormatReader();

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_qr_scan;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        preview = findViewById(R.id.scanPreview);
        surfaceHolder = preview.getHolder();
        surfaceHolder.addCallback(this);
        tvHint = findViewById(R.id.tvScanHint);
        findViewById(R.id.btnScanBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
        requestCameraPermission();
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraPermissionGranted = true;
            startCameraIfReady();
            return;
        }
        XXPermissions.with(this).permission(Permission.CAMERA).request(new OnPermissionCallback() {
            @Override
            public void onGranted(java.util.List<String> permissions, boolean all) {
                cameraPermissionGranted = true;
                startCameraIfReady();
            }

            @Override
            public void onDenied(java.util.List<String> permissions, boolean never) {
                Toast.makeText(ScanActivity.this, "需要相机权限才能扫描二维码", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraPermissionGranted && !resultShown) startCameraIfReady();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surfaceReady = true;
        startCameraIfReady();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        if (!resultShown) startCameraIfReady();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        releaseCamera();
    }

    private void startCameraIfReady() {
        if (!cameraPermissionGranted || !surfaceReady || resultShown || isFinishing() || camera != null) return;
        try {
            int cameraId = findBackCameraId();
            camera = cameraId >= 0 ? Camera.open(cameraId) : Camera.open();
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size best = choosePreviewSize(parameters.getSupportedPreviewSizes());
            if (best != null) parameters.setPreviewSize(best.width, best.height);
            if (parameters.getSupportedFocusModes() != null
                    && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (parameters.getSupportedFocusModes() != null
                    && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(parameters);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surfaceHolder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Throwable error) {
            releaseCamera();
            tvHint.setText("无法启动相机，请检查相机权限或设备相机是否可用");
            Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int findBackCameraId() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return count > 0 ? 0 : -1;
    }

    private Camera.Size choosePreviewSize(java.util.List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        int targetWidth = 1280;
        for (Camera.Size size : sizes) {
            if (size.width >= 640 && size.width <= targetWidth) {
                if (best == null || size.width > best.width) best = size;
            }
        }
        return best == null ? sizes.get(0) : best;
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera source) {
        if (data == null || resultShown || !decoding.compareAndSet(false, true)) return;
        final Camera.Size size = source.getParameters().getPreviewSize();
        if (size == null) {
            decoding.set(false);
            return;
        }
        final byte[] frame = data.clone();
        decodeExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
                            frame, size.width, size.height, 0, 0, size.width, size.height, false);
                    Result result = null;
                    try {
                        result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance)));
                    } catch (NotFoundException firstAttempt) {
                        // 部分手机的原始预览帧为横向，补一次旋转解码。
                        reader.reset();
                        if (luminance.isRotateSupported()) {
                            result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance.rotateCounterClockwise())));
                        }
                    }
                    if (result != null && !TextUtils.isEmpty(result.getText())) {
                        final String text = result.getText().trim();
                        mainHandler.post(new Runnable() {
                            @Override public void run() { onQrDecoded(text); }
                        });
                    }
                } catch (NotFoundException ignored) {
                } catch (Throwable ignored) {
                } finally {
                    reader.reset();
                    decoding.set(false);
                }
            }
        });
    }

    private void onQrDecoded(String value) {
        if (resultShown || TextUtils.isEmpty(value)) return;
        String portUrl = PortActivity.normalizePortUrl(value);
        if (TextUtils.isEmpty(portUrl)) {
            Toast.makeText(this, "二维码内容不是有效的 TVBox 端口地址", Toast.LENGTH_LONG).show();
            tvHint.setText("未识别到有效端口地址，请重新扫描 TVBox 端口二维码");
            return;
        }

        resultShown = true;
        releaseCamera();
        Intent intent = new Intent(this, PortActivity.class);
        intent.putExtra(PortActivity.EXTRA_PORT_URL, portUrl);
        startActivity(intent);
        finish();
    }

    private void releaseCamera() {
        if (camera != null) {
            try { camera.setPreviewCallback(null); } catch (Throwable ignored) { }
            try { camera.stopPreview(); } catch (Throwable ignored) { }
            try { camera.release(); } catch (Throwable ignored) { }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        decodeExecutor.shutdownNow();
        super.onDestroy();
    }
}
