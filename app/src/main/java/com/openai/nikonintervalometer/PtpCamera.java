package com.openai.nikonintervalometer;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NikonBulbRemote {
    private static final int CONTAINER_COMMAND = 1;
    private static final int CONTAINER_RESPONSE = 3;

    private static final int OC_OPEN_SESSION = 0x1002;
    private static final int OC_CLOSE_SESSION = 0x1003;

    // Nikon vendor operations used by libgphoto2 for Nikon DSLR capture.
    private static final int OC_NIKON_DEVICE_READY = 0x90C8;
    private static final int OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207;
    private static final int OC_NIKON_TERMINATE_CAPTURE = 0x920C;

    private static final int RC_OK = 0x2001;
    private static final int RC_DEVICE_BUSY = 0x2019;
    private static final int RC_SESSION_ALREADY_OPEN = 0x201E;

    // Nikon 0x9207 parameter 1:
    // 0xFFFFFFFF = do NOT autofocus before capture
    // 0xFFFFFFFE = autofocus before capture
    private static final int NIKON_NO_AF = 0xFFFFFFFF;

    // Capture target: card.
    private static final int NIKON_CARD = 0;

    private final UsbManager manager;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface cameraInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;

    private int transactionId = 1;
    private boolean sessionOpen = false;
    private volatile boolean captureOpen = false;

    public NikonBulbRemote(UsbManager manager) {
        this.manager = manager;
    }

    public UsbDevice findCamera() {
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            for (int i = 0; i < candidate.getInterfaceCount(); i++) {
                if (candidate.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public void connect(UsbDevice candidate) throws Exception {
        disconnect();

        if (candidate == null) throw new Exception("No camera found");
        if (!manager.hasPermission(candidate)) throw new Exception("USB permission not granted");

        UsbInterface foundInterface = null;
        UsbEndpoint foundIn = null;
        UsbEndpoint foundOut = null;

        for (int i = 0; i < candidate.getInterfaceCount(); i++) {
            UsbInterface intf = candidate.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;

                if (ep.getDirection() == UsbConstants.USB_DIR_IN) foundIn = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) foundOut = ep;
            }

            if (foundIn != null && foundOut != null) {
                foundInterface = intf;
                break;
            }
        }

        if (foundInterface == null || foundIn == null || foundOut == null) {
            throw new Exception("PTP bulk endpoints not found");
        }

        UsbDeviceConnection c = manager.openDevice(candidate);
        if (c == null) throw new Exception("Could not open USB camera");

        if (!c.claimInterface(foundInterface, true)) {
            c.close();
            throw new Exception("Could not claim camera interface");
        }

        device = candidate;
        connection = c;
        cameraInterface = foundInterface;
        bulkIn = foundIn;
        bulkOut = foundOut;
        transactionId = 1;

        Response open = transact(OC_OPEN_SESSION, new int[]{1}, 10000);
        if (open.code != RC_OK && open.code != RC_SESSION_ALREADY_OPEN) {
            disconnect();
            throw ptpException("OpenSession", open.code);
        }

        sessionOpen = true;
        waitUntilReady(15000);
    }

    public void startCaptureNoAf() throws Exception {
        ensureConnected();
        if (captureOpen) throw new Exception("Capture is already open");

        waitUntilReady(15000);

        // Deliberately no fallback to generic InitiateCapture.
        // We want Nikon's no-AF path only.
        Response response = transact(
                OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                new int[]{NIKON_NO_AF, NIKON_CARD},
                15000);

        if (response.code != RC_OK) {
            throw ptpException("START rejected", response.code);
        }

        captureOpen = true;
    }

    public void stopCapture() throws Exception {
        ensureConnected();
        if (!captureOpen) return;

        // Nikon TerminateCapture takes the same capture selector/target pair on these DSLRs.
        Response response = transact(
                OC_NIKON_TERMINATE_CAPTURE,
                new int[]{NIKON_NO_AF, NIKON_CARD},
                15000);

        // Clear locally even if the camera returns an error; do not trap the app in "open".
        captureOpen = false;

        if (response.code != RC_OK) {
            throw ptpException("STOP rejected", response.code);
        }

        // The camera can remain busy briefly while finishing/writing the image.
        waitUntilReady(30000);
    }

    public boolean isCaptureOpen() {
        return captureOpen;
    }

    public boolean isConnected() {
        return connection != null && cameraInterface != null && bulkIn != null && bulkOut != null;
    }

    public String getDeviceName() {
        if (device == null) return "Camera";
        String product = device.getProductName();
        return product != null ? product : device.getDeviceName();
    }

    public void disconnect() {
        if (connection != null) {
            if (captureOpen) {
                try { stopCapture(); } catch (Exception ignored) {}
            }

            if (sessionOpen) {
                try { transact(OC_CLOSE_SESSION, new int[]{}, 5000); } catch (Exception ignored) {}
            }

            if (cameraInterface != null) {
                try { connection.releaseInterface(cameraInterface); } catch (Exception ignored) {}
            }

            try { connection.close(); } catch (Exception ignored) {}
        }

        device = null;
        connection = null;
        cameraInterface = null;
        bulkIn = null;
        bulkOut = null;
        transactionId = 1;
        sessionOpen = false;
        captureOpen = false;
    }

    private void waitUntilReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            Response response = transact(OC_NIKON_DEVICE_READY, new int[]{}, 5000);

            if (response.code == RC_OK) return;

            if (response.code != RC_DEVICE_BUSY) {
                // Some D3xxx bodies hide/partially implement DeviceReady.
                // If unsupported, do not let that prevent the remote START/STOP commands.
                if (response.code == 0x2005 || response.code == 0x2006) return;
                throw ptpException("Camera readiness check", response.code);
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while waiting for camera");
            }
        }

        throw new Exception("Camera stayed busy too long");
    }

    private Response transact(int operationCode, int[] params, int timeoutMs) throws Exception {
        int tid = transactionId++;

        int commandLength = 12 + params.length * 4;
        ByteBuffer command = ByteBuffer.allocate(commandLength).order(ByteOrder.LITTLE_ENDIAN);
        command.putInt(commandLength);
        command.putShort((short) CONTAINER_COMMAND);
        command.putShort((short) operationCode);
        command.putInt(tid);
        for (int p : params) command.putInt(p);

        byte[] out = command.array();
        int written = connection.bulkTransfer(bulkOut, out, out.length, timeoutMs);
        if (written != out.length) throw new Exception("USB command write failed");

        byte[] input = new byte[512];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int n = connection.bulkTransfer(bulkIn, input, input.length, Math.min(3000, remaining));
            if (n < 12) continue;

            ByteBuffer response = ByteBuffer.wrap(input, 0, n).order(ByteOrder.LITTLE_ENDIAN);
            response.getInt(); // container length
            int type = response.getShort() & 0xFFFF;
            int code = response.getShort() & 0xFFFF;
            int responseTid = response.getInt();

            if (type == CONTAINER_RESPONSE && responseTid == tid) {
                return new Response(code);
            }

            // Ignore event/data packets and keep waiting for this transaction's response.
        }

        throw new Exception(String.format("PTP 0x%04X timed out", operationCode));
    }

    private void ensureConnected() throws Exception {
        if (!isConnected()) throw new Exception("Camera not connected");
    }

    private Exception ptpException(String operation, int code) {
        String hint = "";
        if (code == RC_DEVICE_BUSY) hint = " — camera busy";
        else if (code == 0x2005) hint = " — operation not supported";
        else if (code == 0x200A) hint = " — invalid parameter";
        else if (code == 0xA008) hint = " — Nikon Bulb/shutter state";
        return new Exception(String.format("%s: PTP 0x%04X%s", operation, code, hint));
    }

    private static final class Response {
        final int code;
        Response(int code) { this.code = code; }
    }
}
