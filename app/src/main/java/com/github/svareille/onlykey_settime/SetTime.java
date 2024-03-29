package com.github.svareille.onlykey_settime;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import androidx.annotation.RequiresApi;

import static android.app.PendingIntent.FLAG_MUTABLE;

public class SetTime extends Service {

    private final ArrayList<Pair<Integer, Integer>> DEVICE_IDS = new ArrayList<>();

    private UsbManager manager;
    private UsbDevice device = null;

    private final Byte[] MESSAGE_HEADER = {(byte)0xff, (byte)0xff, (byte)0xff, (byte)0xff};

    private final Byte OKSETTIME = (byte) 0xe4;

    private static final String ACTION_USB_PERMISSION = "com.github.svareille.USB_PERMISSION";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            Log.d("usbReceiver.onReceive", "Permission granted");
                            setTime();
                            stopService();
                        }
                    }
                    else {
                        Log.d("usbReceiver.onReceive", "Permission denied for device " + device);
                        stopService();
                    }
                }
            }
        }
    };


    public SetTime() {
        DEVICE_IDS.add(new Pair<>(5824, 1158));
        DEVICE_IDS.add(new Pair<>(7504, 24828));
    }

    private void setTime() {
        UsbInterface usbInterface = device.getInterface(1);
        UsbEndpoint endpoint = usbInterface.getEndpoint(1);

        UsbDeviceConnection connection = manager.openDevice(device);
        connection.claimInterface(usbInterface, true);

        long unixTime = System.currentTimeMillis() / 1000;
        // Unix time is 64bit long, but OnlyKey takes a 32bits integer.
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int)unixTime);

        ArrayList<Byte> message = new ArrayList<>(Arrays.asList(MESSAGE_HEADER));
        message.add(OKSETTIME);
        for (byte b: payload.array()) {
            message.add(b);
        }

        byte[] msg = new byte[message.size()];
        for (int i=0; i<message.size(); i++ ) {
            msg[i] = message.get(i);
        }
        connection.bulkTransfer(endpoint, msg, message.size(), 0);
        connection.releaseInterface(usbInterface);
        connection.close();
    }

    /** Check if the provided UsbDevice is an OnlyKey
     *
     * @param device The device to check
     * @return The UsbDevice if an OnlyKey, null otherwise.
     */
    private UsbDevice isOnlykey(UsbDevice device) {
        if (DEVICE_IDS.contains(new Pair<>(device.getVendorId(), device.getProductId()))) {
            if (device.getSerialNumber().equals("1000000000")) {
                return device;
            }
            else if (device.getInterfaceCount() == 1) {
                return device;
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private PendingIntent createPermissionIntentSplus() {
        return PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), FLAG_MUTABLE);
    }
    @SuppressLint("UnspecifiedImmutableFlag")
    private PendingIntent createPermissionIntentOld() {
        return PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service is starting, due to a call to startService()

        manager = (UsbManager)getSystemService(USB_SERVICE);

        if (intent.getAction().equals(UsbEventReceiverActivity.ACTION_USB_DEVICE_ATTACHED)) {
            device = isOnlykey(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
        } else if (intent.getAction().equals(MainActivity.ACTION_MANUAL_SET_TIME)) {
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            for (UsbDevice usbDevice : deviceList.values()) {
                UsbDevice device = isOnlykey(usbDevice);
                if (device != null) {
                    this.device = device;
                    break;
                }
            }

        }

        PendingIntent permissionIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionIntent = createPermissionIntentSplus();
        } else {
            permissionIntent = createPermissionIntentOld();
        }
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);


        if (device != null) {
            manager.requestPermission(device, permissionIntent);
        }

        return Service.START_NOT_STICKY;
    }

    public void stopService() {
        unregisterReceiver(usbReceiver);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}