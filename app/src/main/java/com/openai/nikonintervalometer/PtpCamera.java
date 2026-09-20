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
    private static final int PTP_USB_CONTAINER_RESPONSE = 3;
    private static final int PTP_OC_OPEN_SESSION = 0x1002;
    private static final int PTP_OC_CLOSE_SESSION = 0x1003;
    private static final int PTP_OC_INITIATE_CAPTURE = 0x100E;
    private static final int PTP_RC_OK = 0x2001;

    private final UsbManager manager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface ptpInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private int transactionId = 1;
    private boolean sessionOpen = false;

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
        if (r.code != PTP_RC_OK && r.code != 0x201E) {
            disconnect();
            throw new Exception(String.format("OpenSession failed: 0x%04X", r.code));
        }
        sessionOpen = true;
        return true;
    }

    public void capture() throws Exception {
        if (!isConnected()) throw new Exception("Camera not connected");
        PtpResponse r = transact(PTP_OC_INITIATE_CAPTURE, new int[]{0, 0});
        if (r.code != PTP_RC_OK) r = transact(PTP_OC_INITIATE_CAPTURE, new int[]{0xFFFFFFFF, 0});
        if (r.code != PTP_RC_OK) throw new Exception(String.format("Capture failed: PTP response 0x%04X", r.code));
    }

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
    }

    private PtpResponse transact(int operationCode, int[] params) throws Exception {
        int tid = transactionId++;
        int length = 12 + params.length * 4;
        ByteBuffer command = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(length);
        command.putShort((short) PTP_USB_CONTAINER_COMMAND);
        command.putShort((short) operationCode);
        command.putInt(tid);
        for (int p : params) command.putInt(p);

        byte[] bytes = command.array();
        int written = connection.bulkTransfer(bulkOut, bytes, bytes.length, 5000);
        if (written != bytes.length) throw new Exception("USB write failed");

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
            if (type == PTP_USB_CONTAINER_RESPONSE && returnedTid == tid) return new PtpResponse(code, containerLength);
        }
        throw new Exception("Timed out waiting for PTP response");
    }

    private static final class PtpResponse {
        final int code;
        final int length;
        PtpResponse(int code, int length) { this.code = code; this.length = length; }
    }
}
