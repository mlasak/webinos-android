// Copyright (c) 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.webinos.android.wrt.ui;

import java.io.File;
import java.io.FileInputStream;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import org.chromium.base.MemoryPressureListener;
import org.chromium.content.app.LibraryLoader;
import org.chromium.content.browser.ActivityContentVideoViewClient;
import org.chromium.content.browser.BrowserStartupController;
import org.chromium.content.browser.ContentVideoViewClient;
import org.chromium.content.browser.ContentView;
import org.chromium.content.browser.ContentViewClient;
import org.chromium.content.browser.DeviceUtils;
import org.chromium.content.common.CommandLine;
import org.chromium.content.common.ProcessInitException;
import org.chromium.content_shell.Shell;
import org.chromium.content_shell.ShellManager;
import org.chromium.ui.base.WindowAndroid;

import org.webinos.android.R;

/**
 * Activity for managing the Content Shell.
 */
public class ContentShellActivity extends Activity {

    public static final String COMMAND_LINE_FILE = "/data/local/tmp/content-shell-command-line";
    private static final String TAG = "ContentShellActivity";

    private static final String ACTIVE_SHELL_URL_KEY = "activeUrl";
    public static final String COMMAND_LINE_ARGS_KEY = "commandLineArgs";

    private ShellManager mShellManager;
    private WindowAndroid mWindowAndroid;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i(TAG, "onCreate:");

        // Initializing the command line must occur before loading the library.
        if (!CommandLine.isInitialized()) {
            CommandLine.initFromFile(COMMAND_LINE_FILE);
            String[] commandLineParams = getCommandLineParamsFromIntent(getIntent());
            if (commandLineParams != null) {
                CommandLine.getInstance().appendSwitchesAndArguments(commandLineParams);
            }
            //enable autoplay video in html5 video/audio tags for Android
            CommandLine.getInstance().appendSwitch("disable-gesture-requirement-for-media-playback");

            //enable WebGL
            CommandLine.getInstance().appendSwitch("enable-webgl");
            
            //quick fix for certificate chain errors / missing warning ui
            CommandLine.getInstance().appendSwitch("ignore-certificate-errors");
        }
        waitForDebuggerIfNeeded();

        DeviceUtils.addDeviceSpecificUserAgentSwitch(this);
		try {
			if(!LibraryLoader.isInitialized()){
				LibraryLoader.ensureInitialized();
			}

        } catch (ProcessInitException e) {
            Log.e(TAG, "ContentView initialization failed.", e);
            finish();
            return;
        }

        setContentView(R.layout.content_shell_activity);
        mShellManager = (ShellManager) findViewById(R.id.shell_container);
        mWindowAndroid = new WindowAndroid(this);
        mWindowAndroid.restoreInstanceState(savedInstanceState);
        mShellManager.setWindow(mWindowAndroid);

        final String startupUrl = getUrlFromIntent(getIntent());
        if (!TextUtils.isEmpty(startupUrl)) {
            mShellManager.setStartupUrl(Shell.sanitizeUrl(startupUrl));
        }


        BrowserStartupController.get(this).startBrowserProcessesAsync(
                new BrowserStartupController.StartupCallback() {
            @Override
            public void onSuccess(boolean alreadyStarted) {
                finishInitialization(savedInstanceState,startupUrl);
            }

            @Override
            public void onFailure() {
                initializationFailed();
            }
        });

    }

    private void finishInitialization(Bundle savedInstanceState, String shellUrl) {
        mShellManager.launchShell(shellUrl);
        //Inject webinos APIs
        StringBuffer fileContent=null;
        try{
            File webinosJS = new File(ContentShellApplication.getAppContext().getFilesDir().getParentFile().getPath()+
            "/node_modules/webinos/node_modules/webinos-pzp/web_root/webinos.js");
            FileInputStream fis = new FileInputStream (webinosJS);
	        fileContent = new StringBuffer(((int) webinosJS.length())*4);
	        byte[] buffer = new byte[1024];
	        int len=fis.read(buffer);
	        while (len != -1) {
	            fileContent.append(new String(buffer,0,len));
	            len = fis.read(buffer);
	        }
	        fis.close();
        }catch(Exception e){
            Log.e(TAG, "Injection of webinos APIs failed.", e);
        }

        //FIXME: The evaluation of the injected script should be postponed until WebSocket communication is possible
        //getActiveContentView().getContentViewCore()
        //    .evaluateJavaScript(((fileContent!=null)?fileContent.toString():""), null);


        getActiveContentView().setContentViewClient(new ContentViewClient() {
            @Override
            public ContentVideoViewClient getContentVideoViewClient() {
                return new ActivityContentVideoViewClient(ContentShellActivity.this);
            }
        });
    }

    private void initializationFailed() {
        Log.e(TAG, "ContentView initialization failed.");
        Toast.makeText(ContentShellActivity.this,
                R.string.browser_process_initialization_failed,
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Shell activeShell = getActiveShell();
        if (activeShell != null) {
            outState.putString(ACTIVE_SHELL_URL_KEY, activeShell.getContentView().getUrl());
        }

        mWindowAndroid.saveInstanceState(outState);
    }

    private void waitForDebuggerIfNeeded() {
        if (CommandLine.getInstance().hasSwitch(CommandLine.WAIT_FOR_JAVA_DEBUGGER)) {
            Log.e(TAG, "Waiting for Java debugger to connect...");
            android.os.Debug.waitForDebugger();
            Log.e(TAG, "Java debugger connected. Resuming execution.");
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event);

        //Don't route the back event inside the shell?
        /*
        Shell activeView = getActiveShell();
        if (activeView != null && activeView.getContentView().canGoBack()) {
            activeView.getContentView().goBack();
            return true;
        }
        */

        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
    	
    	Log.i(TAG, "onNewIntent:");
        if (getCommandLineParamsFromIntent(intent) != null) {
            Log.i(TAG, "Ignoring command line params: can only be set when creating the activity.");
        }

        if (MemoryPressureListener.handleDebugIntent(this, intent.getAction())) return;

        String url = getUrlFromIntent(intent);
        if (!TextUtils.isEmpty(url)) {
            Shell activeView = getActiveShell();
            
            if (activeView != null) {
                activeView.loadUrl(url);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    	Log.i(TAG, "onActivityResult:");
        super.onActivityResult(requestCode, resultCode, data);
        mWindowAndroid.onActivityResult(requestCode, resultCode, data);
    }

    private static String getUrlFromIntent(Intent intent) {
    	Log.i(TAG, "Extra:"+intent.getExtras().get("id"));
        return (String) (intent != null ? intent.getExtras().get("id") : null);
    }

    private static String[] getCommandLineParamsFromIntent(Intent intent) {
        return intent != null ? intent.getStringArrayExtra(COMMAND_LINE_ARGS_KEY) : null;
    }

    /**
     * @return The {@link ShellManager} configured for the activity or null if it has not been
     *         created yet.
     */
    public ShellManager getShellManager() {
        return mShellManager;
    }

    /**
     * @return The currently visible {@link Shell} or null if one is not showing.
     */
    public Shell getActiveShell() {
        return mShellManager != null ? mShellManager.getActiveShell() : null;
    }

    /**
     * @return The {@link ContentView} owned by the currently visible {@link Shell} or null if one
     *         is not showing.
     */
    public ContentView getActiveContentView() {
        Shell shell = getActiveShell();
        return shell != null ? shell.getContentView() : null;
    }
}
