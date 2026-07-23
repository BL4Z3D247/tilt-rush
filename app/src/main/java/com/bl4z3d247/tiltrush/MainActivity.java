package com.bl4z3d247.tiltrush;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class MainActivity extends Activity {
    private static final String TAG = "TiltRush";
    private TiltRushView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            gameView = new TiltRushView(this);
            setContentView(gameView);
            getWindow().getDecorView().post(this::hideSystemUiSafely);
        } catch (Throwable error) {
            showFatalError("Startup failure", error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUiSafely();
        if (gameView != null) {
            try {
                gameView.onHostResume();
            } catch (Throwable error) {
                showFatalError("Sensor startup failure", error);
            }
        }
    }

    @Override
    protected void onPause() {
        if (gameView != null) {
            try {
                gameView.onHostPause();
            } catch (Throwable error) {
                Log.e(TAG, "Pause cleanup failed", error);
            }
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUiSafely();
    }

    private void hideSystemUiSafely() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable error) {
            // Immersive mode is optional; never let it crash the game.
            Log.w(TAG, "Could not enter immersive mode", error);
        }
    }

    private void showFatalError(String heading, Throwable error) {
        Log.e(TAG, heading, error);
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        if (trace.length() > 3500) trace = trace.substring(0, 3500);

        TextView text = new TextView(this);
        text.setText(heading + "\n\n" + trace + "\n\nScreenshot this screen so the crash can be fixed.");
        text.setTextColor(Color.WHITE);
        text.setTextSize(14f);
        text.setGravity(Gravity.START);
        text.setPadding(36, 36, 36, 36);
        text.setBackgroundColor(Color.rgb(8, 11, 21));
        text.setTextIsSelectable(true);
        setContentView(text);
    }
}
