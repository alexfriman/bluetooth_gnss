package com.clearevo.bluetooth_gnss;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.net.Uri;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.clearevo.libbluetooth_gnss_service.bluetooth_gnss_service;
import com.clearevo.libbluetooth_gnss_service.ntrip_conn_mgr;
import com.clearevo.libbluetooth_gnss_service.rfcomm_conn_mgr;
import com.clearevo.libbluetooth_gnss_service.gnss_sentence_parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;


import androidx.annotation.NonNull;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.GeneratedPluginRegistrant;
import io.flutter.plugin.common.EventChannel;
import android.content.SharedPreferences;

public class MainActivity extends FlutterActivity implements gnss_sentence_parser.gnss_parser_callbacks, EventChannel.StreamHandler {

    private static final String ENGINE_METHOD_CHANNEL = "com.clearevo.bluetooth_gnss/engine";
    private static final String ENGINE_EVENTS_CHANNEL = "com.clearevo.bluetooth_gnss/engine_events";
    private static final String SETTINGS_EVENTS_CHANNEL = "com.clearevo.bluetooth_gnss/settings_events";
    public static final String MAIN_ACTIVITY_CLASSNAME = "com.clearevo.bluetooth_gnss.MainActivity";
    private static final int CHOOSE_FOLDER = 1;
    static final String TAG = "btgnss_mainactvty";
    EventChannel.EventSink m_events_sink;
    EventChannel.EventSink m_settings_events_sink;
    bluetooth_gnss_service m_service;
    boolean mBound = false;

    Handler m_handler;
    final int MESSAGE_PARAMS_MAP = 0;
    final int MESSAGE_SETTINGS_MAP = 1;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine);

        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), ENGINE_EVENTS_CHANNEL).setStreamHandler(
                MainActivity.this
        );

        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), SETTINGS_EVENTS_CHANNEL).setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object args, final EventChannel.EventSink events) {
                        m_settings_events_sink = events;
                        Log.d(TAG, "SETTINGS_EVENTS_CHANNEL added listener: " + events);
                    }

                    @Override
                    public void onCancel(Object args) {
                        m_settings_events_sink = null;
                        Log.d(TAG, "SETTINGS_EVENTS_CHANNEL cancelled listener");
                    }
                }
        );
        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), ENGINE_METHOD_CHANNEL)
                .setMethodCallHandler(
                        (call, result) -> {

                            if (call.method.equals("connect")) {
                                final GnssConnectionParams gnssConnectionParams = new GnssConnectionParams();
                                gnssConnectionParams.setBdaddr(call.argument("bdaddr"));
                                gnssConnectionParams.setSecure(Boolean.TRUE.equals(call.argument("secure")));
                                gnssConnectionParams.setReconnect(Boolean.TRUE.equals(call.argument("reconnect")));
                                gnssConnectionParams.setLogBtRx(Boolean.TRUE.equals(call.argument("log_bt_rx")));
                                gnssConnectionParams.setDisableNtrip(Boolean.TRUE.equals(call.argument("disable_ntrip")));
                                gnssConnectionParams.setDisableNtrip(Boolean.TRUE.equals(call.argument("disable_ntrip")));
                                gnssConnectionParams.setGapMode(Boolean.TRUE.equals(call.argument("ble_gap_scan_mode")));
                                gnssConnectionParams.setBleUartMode(Boolean.TRUE.equals(call.argument("ble_uart_mode")));
                                for (String pk : bluetooth_gnss_service.REQUIRED_INTENT_EXTRA_PARAM_KEYS) {
                                    gnssConnectionParams.getExtraParams().put(pk, call.argument(pk));
                                }

                                final Context context = getApplicationContext();

                                new Thread() {
                                    public void run() {
                                        try {
                                            //getcanonicalname somehow returns null, getname() would return something with $ at the end so wont work to launch the activity from the service notification, so just use a string literal here
                                            Util.connect(MAIN_ACTIVITY_CLASSNAME, context, gnssConnectionParams);
                                        } catch (Throwable tr) {
                                            Log.d(TAG, "connect() exception: "+Log.getStackTraceString(tr));
                                        }
                                    }
                                }.start();

                                int ret = 0;
                                Log.d(TAG, "connect() ret: "+ret);
                                result.success(true);
                            } else if (call.method.equals("get_mountpoint_list")) {
                                String host = call.argument("ntrip_host");
                                String port = call.argument("ntrip_port");
                                String user = call.argument("ntrip_user");
                                String pass = call.argument("ntrip_pass");
                                int ret_code = 0;
                                new Thread() {
                                    public void run() {
                                        ArrayList<String> ret = new ArrayList<String>(); //init with empty list in case get fails
                                        try {
                                            ret = get_mountpoint_list(host, Integer.parseInt(port), user, pass);
                                            if (ret == null) {
                                                ret = new ArrayList<String>(); //init with empty list in case get fails - can't push null into concurrenthashmap
                                            }
                                            Log.d(TAG, "get_mountpoint_list ret: " + ret);
                                        } catch (Exception e) {
                                            Log.d(TAG, "on_updated_nmea_params sink update exception: " + Log.getStackTraceString(e));
                                            toast("Get mountpoint_list fialed: " + e);
                                        }
                                        ConcurrentHashMap<String, Object> cbmap = new ConcurrentHashMap<String, Object>();
                                        cbmap.put("callback_src", "get_mountpoint_list");
                                        cbmap.put("callback_payload", ret);
                                        Message msg = m_handler.obtainMessage(MESSAGE_SETTINGS_MAP, cbmap);
                                        msg.sendToTarget();
                                    }
                                }.start();
                                result.success(true);
                            } else if (call.method.equals("toast")) {
                                String msg = call.argument("msg");
                                toast(msg);
                            } else if (call.method.equals("disconnect")) {
                                try {
                                    Log.d(TAG, "disconnect0");
                                    if (m_service != null && mBound) {
                                        Log.d(TAG, "disconnect1");
                                        m_service.stop_auto_reconnect_thread();
                                        m_service.close();
                                        result.success(true);
                                        Log.d(TAG, "disconnect2");
                                    }
                                    Log.d(TAG, "disconnect3");
                                    Intent intent = new Intent(getApplicationContext(), bluetooth_gnss_service.class);
                                    stopService(intent);
                                    Log.d(TAG, "disconnect4");
                                } catch (Exception e) {
                                    Log.d(TAG, "disconnect exception: " + Log.getStackTraceString(e));
                                }
                                result.success(false);
                            } else if (call.method.equals("get_bd_map")) {
                                result.success(rfcomm_conn_mgr.get_bd_map());
                            } else if (call.method.equals("is_bluetooth_on")) {
                                result.success(rfcomm_conn_mgr.is_bluetooth_on());
                            } else if (call.method.equals("is_ntrip_connected")) {
                                result.success(m_service != null && m_service.is_ntrip_connected());
                            } else if (m_service != null && call.method.equals("get_ntrip_cb_count")) {
                                result.success(m_service.get_ntrip_cb_count());
                            } else if (call.method.equals("is_bt_connected")) {
                                result.success(mBound && m_service != null && m_service.is_bt_connected());
                            } else if (call.method.equals("is_conn_thread_alive")) {
                                result.success(mBound && m_service != null && m_service.is_trying_bt_connect());
                            } else if (call.method.equals("open_phone_settings")) {
                                result.success(open_phone_settings());
                            } else if (call.method.equals("open_phone_developer_settings")) {
                                result.success(open_phone_developer_settings());
                            } else if (call.method.equals("open_phone_blueooth_settings")) {
                                result.success(open_phone_bluetooth_settings());
                            } else if (call.method.equals("open_phone_location_settings")) {
                                result.success(open_phone_location_settings());
                            } else if (call.method.equals("set_log_uri")) {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                // Optionally, specify a URI for the directory that should be opened in
                                // the system file picker when it loads.
                                //intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Do);
                                startActivityForResult(intent, CHOOSE_FOLDER);
                            } else if (call.method.equals("test_can_create_file_in_chosen_folder")) {
                                result.success(bluetooth_gnss_service.test_can_create_file_in_chosen_folder(getApplicationContext()));
                            } else if (call.method.equals("is_write_enabled")) {
                                Log.d(TAG, "is_write_enabled check start");
                                if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                    result.success(true);
                                } else {
                                    Log.d(TAG, "is_write_enabled check write permission not granted yet so requesting permission now");
                                    Toast.makeText(getApplicationContext(), "BluetoothGNSS needs external storage write permissions to log data - please allow...", Toast.LENGTH_LONG).show();
                                    new Thread() {
                                        public void run() {
                                            try {
                                                Thread.sleep(1000);
                                            } catch (Exception e) {
                                            }
                                            m_handler.post(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                            }, 1);
                                                        }
                                                    }
                                            );
                                        }
                                    }.start();
                                    result.success(false);
                                }
                            } else if (call.method.equals("is_mock_location_enabled")) {
                                result.success(bluetooth_gnss_service.is_mock_location_enabled(getApplicationContext(), android.os.Process.myUid(), BuildConfig.APPLICATION_ID));
                            } else if (call.method.equals("is_location_enabled")) {

                                Log.d(TAG, "is_location_enabled 0");
                                if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                                        ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                ) {

                                    Log.d(TAG, "is_location_enabled check locaiton permission already granted");

                                    if (call.method.equals("is_location_enabled")) {
                                        result.success(bluetooth_gnss_service.is_location_enabled(getApplicationContext()));
                                    } else if (call.method.equals("is_mock_location_enabled")) {
                                        result.success(bluetooth_gnss_service.is_mock_location_enabled(getApplicationContext(), android.os.Process.myUid(), BuildConfig.APPLICATION_ID));
                                    }
                                } else {
                                    Log.d(TAG, "is_location_enabled check locaiton permission not granted yet so requesting permission now");
                                    Toast.makeText(getApplicationContext(), "BluetoothGNSS needs to check location settings - please allow...", Toast.LENGTH_LONG).show();

                                    new Thread() {
                                        public void run() {
                                            try {
                                                Thread.sleep(1000);
                                            } catch (Exception e) {
                                            }
                                            m_handler.post(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                                            }, 1);
                                                        }
                                                    }
                                            );
                                        }
                                    }.start();
                                    result.success(false);
                                }

                            } else if (call.method.equals("is_coarse_location_enabled")) {

                                Log.d(TAG, "is_coarse_location_enabled 0");
                                if (
                                        ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                ) {

                                    Log.d(TAG, "is_coarse_location_enabled check locaiton permission already granted");
                                    result.success(true);
                                } else {
                                    Log.d(TAG, "is_coarse_location_enabled check locaiton permission not granted yet so requesting permission now");
                                    Toast.makeText(getApplicationContext(), "BluetoothGNSS needs to check coarse location settings - please allow...", Toast.LENGTH_LONG).show();

                                    new Thread() {
                                        public void run() {
                                            try {
                                                Thread.sleep(1000);
                                            } catch (Exception e) {
                                            }
                                            m_handler.post(
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                                            }, 1);
                                                        }
                                                    }
                                            );
                                        }
                                    }.start();
                                    result.success(false);
                                }

                            } else {
                                result.notImplemented();
                            }
                        }
                );

        create();
    }

    final String log_uri_pref_key = "flutter.pref_log_uri";
    final String log_enabled_pref_key = "flutter.log_bt_rx";

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {
        if (requestCode == CHOOSE_FOLDER) {
            Context context = getApplicationContext();
            final SharedPreferences prefs = context.getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            if (resultCode == this.RESULT_OK) {
                Uri uri = null;
                if (resultData != null) {
                    uri = resultData.getData();
                    if (uri != null && context != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        }
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString(log_uri_pref_key, uri.toString());
                        editor.putBoolean(log_enabled_pref_key, true);
                        editor.apply();
                        Log.d(TAG, "choose_folder ok so set log uri: " + log_uri_pref_key + " val: " + prefs.getString(log_uri_pref_key, ""));
                    }
                }
            } else {
                Log.d(TAG, "choose_folder not ok so disable log uri: " + log_uri_pref_key);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(log_uri_pref_key, "");
                editor.putBoolean(log_enabled_pref_key, false);
                editor.apply();
            }
        }
    }

    public void create() {
        Log.d(TAG, "create()");
        m_handler = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(Message inputMessage) {
                if (inputMessage.what == MESSAGE_PARAMS_MAP) {
                    Log.d(TAG, "mainactivity handler got params map");
                    try {
                        if (mBound == false || m_events_sink == null) {
                            Log.d(TAG, "mBound == false || m_events_sink == null so not delivering params_map");
                        } else {
                            Object params_map = inputMessage.obj;
                            if (params_map instanceof HashMap) {
                                /*
                        PREVENT BELOW: try clone the hashmap...
                        D/btgnss_mainactvty(15208): handlemessage exception: java.util.ConcurrentModificationException
D/btgnss_mainactvty(15208): 	at java.util.HashMap$HashIterator.nextNode(HashMap.java:1441)
D/btgnss_mainactvty(15208): 	at java.util.HashMap$EntryIterator.next(HashMap.java:1475)
D/btgnss_mainactvty(15208): 	at java.util.HashMap$EntryIterator.next(HashMap.java:1473)
D/btgnss_mainactvty(15208): 	at io.flutter.plugin.common.StandardMessageCodec.writeValue(StandardMessageCodec.java:289)
D/btgnss_mainactvty(15208): 	at io.flutter.plugin.common.StandardMethodCodec.encodeSuccessEnvelope(StandardMethodCodec.java:57)
D/btgnss_mainactvty(15208): 	at io.flutter.plugin.common.EventChannel$IncomingStreamRequestHandler$EventSinkImplementation.success(EventChannel.java:226)
D/btgnss_mainactvty(15208): 	at com.clearevo.bluetooth_gnss.MainActivity$1.handleMessage(MainActivity.java:64)
                        * */
                                //params_map = ((HashMap) params_map).clone();
                                //Log.d(TAG, "cloned HashMap to prevent ConcurrentModificationException...");
                            }
                            Log.d(TAG, "sending params map to m_events_sink start");
                            m_events_sink.success(params_map);
                            Log.d(TAG, "sending params map to m_events_sink done");
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "handlemessage MESSAGE_PARAMS_MAP exception: " + Log.getStackTraceString(e));
                    }
                } else if (inputMessage.what == MESSAGE_SETTINGS_MAP) {
                    Log.d(TAG, "mainactivity handler got settings map");
                    try {
                        if (mBound == false || m_settings_events_sink == null) {
                            Log.d(TAG, "mBound == false || m_settings_events_sink == null so not delivering params_map");
                        } else {
                            Object params_map = inputMessage.obj;
                            //this is already a concurrenthashmap - no need to clone
                            Log.d(TAG, "sending params map to m_settings_events_sink start");
                            m_settings_events_sink.success(params_map);
                            Log.d(TAG, "sending params map to m_settings_events_sink done");
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "handlemessage MESSAGE_SETTINGS_MAP exception: " + Log.getStackTraceString(e));
                    }
                }
            }
        };
    }

    @Override
    public void onListen(Object args, final EventChannel.EventSink events) {
        m_events_sink = events;
        Log.d(TAG, "ENGINE_EVENTS_CHANNEL added listener: " + events);
    }

    @Override
    public void onCancel(Object args) {
        m_events_sink = null;
        Log.d(TAG, "ENGINE_EVENTS_CHANNEL cancelled listener");
    }

    public ArrayList<String> get_mountpoint_list(String host, int port, String user, String pass) {
        ArrayList<String> ret = null;
        ntrip_conn_mgr mgr = null;
        try {
            mgr = new ntrip_conn_mgr(host, port, "", user, pass, null);
            ret = mgr.get_mount_point_list();
        } catch (Exception e) {
            Log.d(TAG, "get_mountpoint_list call exception: " + Log.getStackTraceString(e));
        } finally {
            if (mgr != null) {
                try {
                    mgr.close();
                } catch (Exception e) {
                }
            }
        }
        return ret;
    }

    public boolean open_phone_settings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "launch phone settings activity exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public boolean open_phone_bluetooth_settings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "launch phone settings activity exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public boolean open_phone_location_settings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "launch phone settings activity exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public boolean open_phone_developer_settings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            return true;
        } catch (Exception e) {
            Log.d(TAG, "launch phone settings activity exception: " + Log.getStackTraceString(e));
        }
        return false;
    }

    public void on_updated_nmea_params(HashMap<String, Object> params_map) {
        Log.d(TAG, "mainactivity on_updated_nmea_params()");
        try {
            Message msg = m_handler.obtainMessage(MESSAGE_PARAMS_MAP, params_map);
            msg.sendToTarget();
        } catch (Exception e) {
            Log.d(TAG, "on_updated_nmea_params sink update exception: " + Log.getStackTraceString(e));
        }
    }


    public void toast(String msg) {
        m_handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Log.d(TAG, "toast exception: " + Log.getStackTraceString(e));
                        }
                    }
                }
        );
    }

    public void stop_service_if_not_connected() {
        if (mBound && m_service != null && m_service.is_bt_connected()) {
            toast("Bluetooth GNSS running in backgroud...");
        } else {
            Intent intent = new Intent(getApplicationContext(), bluetooth_gnss_service.class);
            stopService(intent);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed()");
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();
        // Bind to LocalService
        Intent intent = new Intent(this, bluetooth_gnss_service.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }


    @Override
    protected void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        unbindService(connection);
        mBound = false;

    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();
    }

    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            Log.d(TAG, "onServiceConnected()");

            // We've bound to LocalService, cast the IBinder and get LocalService instance
            bluetooth_gnss_service.LocalBinder binder = (bluetooth_gnss_service.LocalBinder) service;
            m_service = binder.getService();
            mBound = true;
            m_service.set_callback((gnss_sentence_parser.gnss_parser_callbacks) MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d(TAG, "onServiceDisconnected()");
            mBound = false;
            m_service.set_callback((gnss_sentence_parser.gnss_parser_callbacks) null);
        }
    };

}
