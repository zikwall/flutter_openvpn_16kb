package id.laskarmedia.openvpn_flutter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import de.blinkt.openvpn.OnVPNStatusChangeListener;
import de.blinkt.openvpn.VPNHelper;
import de.blinkt.openvpn.core.OpenVPNService;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

/**
 * OpenvpnFlutterPlugin
 */
public class OpenVPNFlutterPlugin implements FlutterPlugin, ActivityAware {
    private MethodChannel vpnControlMethod;
    private EventChannel vpnStageEvent;
    //    private EventChannel vpnStatusEvent;
    private EventChannel.EventSink vpnStageSink;
//    private EventChannel.EventSink vpnStatusSink;

    private static final String EVENT_CHANNEL_VPN_STAGE = "id.laskarmedia.openvpn_flutter/vpnstage";
    //    private static final String EVENT_CHANNEL_VPN_STATUS = "id.laskarmedia.openvpn_flutter/vpnstatus";
    private static final String METHOD_CHANNEL_VPN_CONTROL = "id.laskarmedia.openvpn_flutter/vpncontrol";

    private static String config = "", username = "", password = "", name = "";

    private static ArrayList<String> bypassPackages;
    @SuppressLint("StaticFieldLeak")
    private static VPNHelper vpnHelper;
    private Activity activity;
    private ActivityPluginBinding activityBinding;
    private MethodChannel.Result pendingPermissionResult;
    private final PluginRegistry.ActivityResultListener activityResultListener = this::onActivityResult;

    Context mContext;


    public static void connectWhileGranted(boolean granted) {
        // Permission grant alone must not start VPN. The Dart side starts VPN
        // explicitly after request_permission returns true.
    }

    private static void notifyStage(String stage) {
        if (VPNHelper.listener != null) {
            VPNHelper.listener.onVPNStatusChanged(stage);
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent = new EventChannel(binding.getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlMethod = new MethodChannel(binding.getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);

        vpnStageEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                if (vpnStageSink != null) vpnStageSink.endOfStream();
            }
        });

        vpnControlMethod.setMethodCallHandler((call, result) -> {

            switch (call.method) {
                case "status":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    result.success(vpnHelper.status.toString());
                    break;
                case "initialize":
                    vpnHelper = new VPNHelper(activity);
                    vpnHelper.setOnVPNStatusChangeListener(new OnVPNStatusChangeListener() {
                        @Override
                        public void onVPNStatusChanged(String status) {
                            updateStage(status);
                        }

                        @Override
                        public void onConnectionStatusChanged(String duration, String lastPacketReceive, String byteIn, String byteOut) {

                        }
                    });
                    result.success(updateVPNStages());
                    break;
                case "disconnect":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }

                    vpnHelper.stopVPN();
                    updateStage("disconnected");
                    break;
                case "connect":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }

                    config = call.argument("config");
                    name = call.argument("name");
                    username = call.argument("username");
                    password = call.argument("password");
                    bypassPackages = call.argument("bypass_packages");

                    if (config == null) {
                        result.error("-2", "OpenVPN Config is required", "");
                        return;
                    }

                    final Intent permission = VpnService.prepare(activity);
                    if (permission != null) {
                        result.error("-3", "OpenVPN permission is required before connect", "");
                        return;
                    }
                    vpnHelper.startVPN(config, username, password, name, bypassPackages);
                    break;
                case "stage":
                    if (vpnHelper == null) {
                        result.error("-1", "VPNEngine need to be initialize", "");
                        return;
                    }
                    result.success(updateVPNStages());
                    break;
                case "request_permission":
                    final Intent request = VpnService.prepare(activity);
                    if (request != null) {
                        if (pendingPermissionResult != null) {
                            result.error("-4", "OpenVPN permission request is already running", "");
                            return;
                        }
                        pendingPermissionResult = result;
                        activity.startActivityForResult(request, 24);
                        return;
                    }
                    result.success(true);
                    break;

                default:
            }
        });
        mContext = binding.getApplicationContext();
    }

    public void updateStage(String stage) {
        if (stage == null) stage = "idle";
        if (vpnStageSink != null) vpnStageSink.success(stage.toLowerCase());
    }

    private boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != 24) {
            return false;
        }

        final boolean granted = resultCode == Activity.RESULT_OK;
        final MethodChannel.Result result = pendingPermissionResult;
        pendingPermissionResult = null;

        if (result != null) {
            result.success(granted);
        }
        if (!granted) {
            notifyStage("denied");
        }
        return true;
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        vpnStageEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
//        vpnStatusEvent.setStreamHandler(null);
    }


    private String updateVPNStages() {
        if (OpenVPNService.getStatus() == null) {
            OpenVPNService.setDefaultStatus();
        }
        updateStage(OpenVPNService.getStatus());
        return OpenVPNService.getStatus();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        activity = binding.getActivity();
        binding.addActivityResultListener(activityResultListener);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        detachActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activityBinding = binding;
        activity = binding.getActivity();
        binding.addActivityResultListener(activityResultListener);
    }

    @Override
    public void onDetachedFromActivity() {
        detachActivity();
    }

    private void detachActivity() {
        if (activityBinding != null) {
            activityBinding.removeActivityResultListener(activityResultListener);
            activityBinding = null;
        }
        if (pendingPermissionResult != null) {
            pendingPermissionResult.error("-5", "Activity detached before OpenVPN permission result", "");
            pendingPermissionResult = null;
        }
        activity = null;
    }
}
