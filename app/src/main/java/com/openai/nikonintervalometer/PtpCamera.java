package com.openai.nikonintervalometer;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class PtpCamera {
    private static final int PTP_USB_CONTAINER_COMMAND = 1;
    private static final int PTP_USB_CONTAINER_DATA = 2;
    private static final int PTP_USB_CONTAINER_RESPONSE = 3;

    private static final int PTP_OC_OPEN_SESSION = 0x1002;
    private static final int PTP_OC_CLOSE_SESSION = 0x1003;
    private static final int PTP_OC_SET_DEVICE_PROP_VALUE = 0x1016;
    private static final int PTP_OC_INITIATE_CAPTURE = 0x100E;

    private static final int PTP_OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207;
    private static final int PTP_OC_NIKON_TERMINATE_CAPTURE = 0x920C;

    private static final int PTP_DPC_EXPOSURE_TIME = 0x500D;
    private static final int PTP_DPC_NIKON_EXPOSURE_TIME = 0xD100;
    private static final int PTP_EXPOSURE_BULB = 0xFFFFFFFF;

    private static final int PTP_RC_OK = 0x2001;
    private static final int PTP_RC_SESSION_ALREADY_OPEN = 0x201E;

    private final UsbManager manager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface ptpInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private int transactionId = 1;
    private boolean sessionOpen = false;
    private volatile boolean bulbOpen = false;

    public PtpCamera(UsbManager manager) { this.manager = manager; }

    public UsbDevice findStillImageDevice() {
        for (UsbDevice d : manager.getDeviceList().values()) {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) return d;
            }
        }
        return null;
    }

    public boolean connect(UsbDevice d) throws Exception {
        disconnect();
        if (d == null) throw new Exception("No USB camera found");
        if (!manager.hasPermission(d)) throw new SecurityException("USB permission not granted");

        UsbInterface found = null;
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
                }
            }
            if (in != null && out != null) { found = intf; break; }
        }
        if (found == null || in == null || out == null) throw new Exception("PTP bulk endpoints not found");

        UsbDeviceConnection c = manager.openDevice(d);
        if (c == null) throw new Exception("Could not open USB device");
        if (!c.claimInterface(found, true)) {
            c.close();
            throw new Exception("Could not claim camera interface");
        }

        device = d;
        connection = c;
        ptpInterface = found;
        bulkIn = in;
        bulkOut = out;
        transactionId = 1;

        PtpResponse r = transact(PTP_OC_OPEN_SESSION, new int[]{1});
        if (r.code != PTP_RC_OK && r.code != PTP_RC_SESSION_ALREADY_OPEN) {
            disconnect();
            throw new Exception(String.format("OpenSession failed: 0x%04X", r.code));
        }
        sessionOpen = true;
        return true;
    }

    public void capture() throws Exception {
        ensureConnected();
        PtpResponse r = transact(PTP_OC_INITIATE_CAPTURE, new int[]{0, 0});
        if (r.code != PTP_RC_OK) r = transact(PTP_OC_INITIATE_CAPTURE, new int[]{0xFFFFFFFF, 0});
        if (r.code != PTP_RC_OK) throw ptpError("Capture failed", r.code);
    }

    public boolean setBulbMode() throws Exception {
        ensureConnected();

        // Nikon exposes Bulb as the 32-bit exposure-time value 0xFFFFFFFF.
        // Try the standard ExposureTime property first, then Nikon's extended property.
        PtpResponse r = setUint32Property(PTP_DPC_EXPOSURE_TIME, PTP_EXPOSURE_BULB);
        if (r.code == PTP_RC_OK) return true;

        r = setUint32Property(PTP_DPC_NIKON_EXPOSURE_TIME, PTP_EXPOSURE_BULB);
        return r.code == PTP_RC_OK;
    }

    public void startBulb() throws Exception {
        ensureConnected();
        if (bulbOpen) throw new Exception("Bulb exposure is already running");

        // Nikon capture-to-card: no AF before capture, target = card.
        PtpResponse r = transact(PTP_OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                new int[]{0xFFFFFFFF, 0});

        // Some Nikon bodies accept the standard InitiateCapture path in Bulb instead.
        if (r.code != PTP_RC_OK) {
            r = transact(PTP_OC_INITIATE_CAPTURE, new int[]{0, 0});
        }

        if (r.code != PTP_RC_OK) {
            throw ptpError("Could not start Bulb (use camera M mode + Bulb)", r.code);
        }
        bulbOpen = true;
    }

    public void endBulb() throws Exception {
        ensureConnected();
        if (!bulbOpen) return;

        PtpResponse r = transact(PTP_OC_NIKON_TERMINATE_CAPTURE,
                new int[]{0xFFFFFFFF, 0});
        bulbOpen = false;

        if (r.code != PTP_RC_OK) {
            throw ptpError("Could not terminate Bulb", r.code);
        }
    }

    public boolean isBulbOpen() { return bulbOpen; }

    public boolean isConnected() {
        return connection != null && ptpInterface != null && bulkIn != null && bulkOut != null;
    }

    public String getDeviceName() {
        if (device == null) return "No camera";
        String product = device.getProductName();
        return product != null ? product : device.getDeviceName();
    }

    public void disconnect() {
        if (connection != null) {
            if (bulbOpen) {
                try { endBulb(); } catch (Exception ignored) {}
            }
            if (sessionOpen) {
                try { transact(PTP_OC_CLOSE_SESSION, new int[]{}); } catch (Exception ignored) {}
            }
            if (ptpInterface != null) {
                try { connection.releaseInterface(ptpInterface); } catch (Exception ignored) {}
            }
            try { connection.close(); } catch (Exception ignored) {}
        }
        device = null;
        connection = null;
        ptpInterface = null;
        bulkIn = null;
        bulkOut = null;
        sessionOpen = false;
        bulbOpen = false;
    }

    private void ensureConnected() throws Exception {
        if (!isConnected()) throw new Exception("Camera not connected");
    }

    private PtpResponse setUint32Property(int propertyCode, int value) throws Exception {
        int tid = transactionId++;
        sendCommand(PTP_OC_SET_DEVICE_PROP_VALUE, tid, new int[]{propertyCode});

        ByteBuffer data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(16);
        data.putShort((short) PTP_USB_CONTAINER_DATA);
        data.putShort((short) PTP_OC_SET_DEVICE_PROP_VALUE);
        data.putInt(tid);
        data.putInt(value);

        byte[] bytes = data.array();
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, 5000);
        if (written != bytes.length) throw new Exception("USB property-data write failed");

        return readResponse(tid);
    }

    private PtpResponse transact(int operationCode, int[] params) throws Exception {
        int tid = transactionId++;
        sendCommand(operationCode, tid, params);
        return readResponse(tid);
    }

    private void sendCommand(int operationCode, int tid, int[] params) throws Exception {
        int length = 12 + params.length * 4;
        ByteBuffer command = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(length);
        command.putShort((short) PTP_USB_CONTAINER_COMMAND);
        command.putShort((short) operationCode);
        command.putInt(tid);
        for (int p : params) command.putInt(p);

        byte[] bytes = command.array();
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, 5000);
        if (written != bytes.length) throw new Exception("USB command write failed");
    }

    private PtpResponse readResponse(int tid) throws Exception {
        byte[] response = new byte[512];
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            int n = connection.bulkTransfer(bulkIn, response, response.length, 3000);
            if (n < 12) continue;

            ByteBuffer b = ByteBuffer.wrap(response, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            int containerLength = b.getInt();
            int type = b.getShort() & 0xFFFF;
            int code = b.getShort() & 0xFFFF;
            int returnedTid = b.getInt();

            if (type == PTP_USB_CONTAINER_RESPONSE && returnedTid == tid) {
                return new PtpResponse(code, containerLength);
            }
            // Ignore event/data containers here; wait for the matching response.
        }
        throw new Exception("Timed out waiting for PTP response");
    }

    private Exception ptpError(String prefix, int code) {
        String hint = "";
        if (code == 0xA008) hint = " (camera reports Shutter Speed Bulb)";
        else if (code == 0x2005) hint = " (operation not supported)";
        else if (code == 0x2019) hint = " (device busy)";
        return new Exception(String.format("%s: PTP 0x%04X%s", prefix, code, hint));
    }

    private static final class PtpResponse {
        final int code;
        final int length;
        PtpResponse(int code, int length) { this.code = code; this.length = length; }
    }
}
