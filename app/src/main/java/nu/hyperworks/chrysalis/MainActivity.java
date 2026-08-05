package nu.hyperworks.chrysalis;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** M2 bring-up shell: prove the core loads, and install firmware from a PUP. */
public class MainActivity extends Activity {

    private static final String TAG = "chrysalis";
    private static final int PICK_PUP = 1;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setPadding(32, 32, 32, 32);
        status.setTextIsSelectable(true);

        Button install = new Button(this);
        install.setText("Install firmware (select PS3UPDAT.PUP)");
        install.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_PUP);
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(install);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll);
        setContentView(root);

        NativeCore.init(getFilesDir().getAbsolutePath());
        refreshStatus("Core loaded.");

        // Debug/frontend hook: install directly from a readable path, e.g.
        // adb shell am start -n nu.hyperworks.chrysalis/.MainActivity \
        //   --es pup_path /sdcard/Android/data/nu.hyperworks.chrysalis/files/PS3UPDAT.PUP
        String pupPath = getIntent().getStringExtra("pup_path");
        if (pupPath != null) {
            refreshStatus("Installing firmware from " + pupPath + "… (see logcat, tag rpcs3)");
            installFromFile(new File(pupPath), false);
        }
    }

    private void installFromFile(File pup, boolean deleteAfter) {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String error = NativeCore.installFirmware(pup.getAbsolutePath());
            long dt = System.currentTimeMillis() - t0;
            if (deleteAfter) {
                //noinspection ResultOfMethodCallIgnored
                pup.delete();
            }
            runOnUiThread(() -> refreshStatus(error == null || error.isEmpty()
                    ? "Firmware installed successfully in " + dt + " ms."
                    : "Firmware installation failed: " + error));
        }, "fw-install").start();
    }

    private void refreshStatus(String headline) {
        String fw = NativeCore.firmwareVersion();
        status.setText(headline + "\n\nInstalled PS3 firmware: "
                + (fw == null || fw.isEmpty() ? "none" : fw)
                + "\nData dir: " + getFilesDir().getAbsolutePath());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PUP || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        final Uri uri = data.getData();
        refreshStatus("Copying PUP…");

        new Thread(() -> {
            try {
                File pup = new File(getCacheDir(), "PS3UPDAT.PUP");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(pup)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                runOnUiThread(() -> refreshStatus("Installing firmware… (see logcat, tag rpcs3)"));
                installFromFile(pup, true);
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                runOnUiThread(() -> refreshStatus("Firmware installation failed: " + e));
            }
        }, "fw-install").start();
    }
}
