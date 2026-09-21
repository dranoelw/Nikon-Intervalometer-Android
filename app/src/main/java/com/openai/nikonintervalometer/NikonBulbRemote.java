package com.openai.nikonintervalometer;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

public final class NikonBulbRemote {
    private static final int NIKON_VENDOR_ID = 0x04B0;

    private static final int CONTAINER_COMMAND = 1;
    private static final int CONTAINER_DATA = 2;
    private static final int CONTAINER_RESPONSE = 3;

    private static final int OC_OPEN_SESSION = 0x1002;
    private static final int OC_CLOSE_SESSION = 0x1003;
    private static final int OC_GET_DEVICE_PROP_DESC = 0x1014;
    private static final int OC_GET_DEVICE_PROP_VALUE = 0x1015;
    private static final int OC_SET_DEVICE_PROP_VALUE = 0x1016;
    private static final int OC_GET_OBJECT_HANDLES = 0x1007;
    private static final int OC_GET_THUMB = 0x100A;

    private static final int OC_NIKON_DEVICE_READY = 0x90C8;
    private static final int OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207;
    private static final int OC_NIKON_TERMINATE_CAPTURE = 0x920C;

    private static final int PROP_BATTERY_LEVEL = 0x5001;
    private static final int PROP_FOCUS_MODE = 0x500A;
    private static final int PROP_EXPOSURE_TIME = 0x500D;
    private static final int PROP_EXPOSURE_PROGRAM = 0x500E;
    private static final int PROP_EXPOSURE_DELAY_MODE = 0xD06A;

    private static final int EXPOSURE_PROGRAM_MANUAL = 0x0001;
    private static final int FOCUS_MODE_MANUAL = 0x0001;
    private static final long EXPOSURE_TIME_BULB = 0xFFFFFFFFL;

    private static final int RC_OK = 0x2001;
    private static final int RC_DEVICE_BUSY = 0x2019;
    private static final int RC_SESSION_ALREADY_OPEN = 0x201E;
    private static final int RC_NIKON_BULB_RELEASE_BUSY = 0xA200;
    private static final int RC_NIKON_SILENT_RELEASE_BUSY = 0xA201;

    private static final int NIKON_NO_AF = 0xFFFFFFFF;
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
            if (candidate.getVendorId() != NIKON_VENDOR_ID) continue;
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
        if (candidate.getVendorId() != NIKON_VENDOR_ID) {
            throw new Exception("Connected USB camera is not a Nikon");
        }
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
        waitUntilReady(30000);
    }

    public CameraSetup readCameraSetup() throws Exception {
        ensureConnected();

        int batteryLevel = getUint8Property(PROP_BATTERY_LEVEL);
        int exposureProgram = getUint16Property(PROP_EXPOSURE_PROGRAM);
        long exposureTime = getUint32Property(PROP_EXPOSURE_TIME);
        int focusMode = getUint16Property(PROP_FOCUS_MODE);

        return new CameraSetup(
                exposureProgram == EXPOSURE_PROGRAM_MANUAL,
                exposureTime == EXPOSURE_TIME_BULB,
                focusMode == FOCUS_MODE_MANUAL,
                batteryLevel);
    }


    public ExposureDelayInfo readExposureDelayInfo() {
        if (!isConnected()) return ExposureDelayInfo.unsupported("Camera not connected");

        try {
            DevicePropertyDescriptor desc = getDevicePropertyDescriptor(PROP_EXPOSURE_DELAY_MODE);
            Integer twoSecondRaw = chooseTwoSecondExposureDelayValue(desc);
            return new ExposureDelayInfo(
                    true,
                    desc.writable,
                    desc.currentValue,
                    twoSecondRaw,
                    twoSecondRaw != null
                            ? "Automatic 2 s mirror delay available"
                            : "Exposure delay available; set 2 s in the camera menu");
        } catch (Exception e) {
            return ExposureDelayInfo.unsupported("USB exposure-delay control unavailable");
        }
    }

    public ExposureDelaySession prepareTwoSecondExposureDelay() {
        if (!isConnected()) {
            return ExposureDelaySession.notConfigured("Camera not connected");
        }

        try {
            DevicePropertyDescriptor desc = getDevicePropertyDescriptor(PROP_EXPOSURE_DELAY_MODE);
            if (!desc.writable) {
                return ExposureDelaySession.notConfigured(
                        "Exposure delay is read-only; use the camera menu");
            }

            Integer target = chooseTwoSecondExposureDelayValue(desc);
            if (target == null) {
                return ExposureDelaySession.notConfigured(
                        "Set Exposure delay mode to 2 s in the camera menu");
            }

            long previous = desc.currentValue;
            if (previous != target) {
                setDevicePropertyValue(PROP_EXPOSURE_DELAY_MODE, desc.dataType, target);
            }

            return new ExposureDelaySession(
                    true,
                    previous != target,
                    previous,
                    desc.dataType,
                    "2 s mirror delay");
        } catch (Exception e) {
            return ExposureDelaySession.notConfigured(
                    "Automatic mirror delay unavailable: " + e.getMessage());
        }
    }

    public void restoreExposureDelay(ExposureDelaySession session) {
        if (session == null || !session.configured || !session.changed || !isConnected()) return;
        try {
            setDevicePropertyValue(
                    PROP_EXPOSURE_DELAY_MODE,
                    session.dataType,
                    session.previousValue);
        } catch (Exception ignored) {
            // Best effort only. Never turn a completed interval sequence into an error
            // just because the original menu setting could not be restored.
        }
    }

    public int[] getImageHandles() throws Exception {
        ensureConnected();
        waitUntilReady(30000);

        byte[] data = dataOperation(
                OC_GET_OBJECT_HANDLES,
                new int[]{0xFFFFFFFF, 0, 0},
                30000);

        if (data.length < 4) return new int[0];
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long countLong = b.getInt() & 0xFFFFFFFFL;
        int count = (int)Math.min(countLong, (data.length - 4) / 4);
        int[] handles = new int[count];
        for (int i = 0; i < count; i++) handles[i] = b.getInt();
        return handles;
    }

    public byte[] getThumbnail(int objectHandle) throws Exception {
        ensureConnected();
        waitUntilReady(30000);
        return dataOperation(OC_GET_THUMB, new int[]{objectHandle}, 30000);
    }

    public void startCaptureNoAf() throws Exception {
        ensureConnected();
        if (captureOpen) throw new Exception("Capture is already open");

        waitUntilReady(30000);

        long deadline = System.currentTimeMillis() + 30000;
        Response response;

        do {
            response = transact(
                    OC_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                    new int[]{NIKON_NO_AF, NIKON_CARD},
                    15000);

            if (response.code == RC_OK) {
                captureOpen = true;
                return;
            }

            if (!isBusy(response.code)) {
                throw ptpException("START rejected", response.code);
            }

            waitUntilReady(Math.max(1000, deadline - System.currentTimeMillis()));
        } while (System.currentTimeMillis() < deadline);

        throw ptpException("START stayed busy", response.code);
    }

    public void stopCapture() throws Exception {
        ensureConnected();
        if (!captureOpen) return;

        Response response = transact(
                OC_NIKON_TERMINATE_CAPTURE,
                new int[]{NIKON_NO_AF, NIKON_CARD},
                15000);

        captureOpen = false;

        if (response.code != RC_OK) {
            throw ptpException("STOP rejected", response.code);
        }

        waitUntilReady(30000);
    }

    public boolean isCaptureOpen() {
        return captureOpen;
    }

    public boolean isConnected() {
        return connection != null && cameraInterface != null && bulkIn != null && bulkOut != null;
    }

    public String getDeviceName() {
        if (device == null) return "Nikon Camera";
        String product = device.getProductName();
        if (product != null && !product.trim().isEmpty()) return product;
        return "Nikon Camera";
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

    private int getUint8Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 1) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return data[0] & 0xFF;
    }

    private int getUint16Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 2) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private long getUint32Property(int propertyCode) throws Exception {
        byte[] data = getPropertyData(propertyCode);
        if (data.length < 4) throw new Exception(String.format("Property 0x%04X returned no value", propertyCode));
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private byte[] getPropertyData(int propertyCode) throws Exception {
        int tid = transactionId++;
        sendCommand(OC_GET_DEVICE_PROP_VALUE, tid, new int[]{propertyCode}, 5000);

        byte[] value = null;
        byte[] input = new byte[1024];
        long deadline = System.currentTimeMillis() + 8000;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int n = connection.bulkTransfer(bulkIn, input, input.length, Math.min(3000, remaining));
            if (n < 12) continue;

            int offset = 0;
            while (offset + 12 <= n) {
                ByteBuffer header = ByteBuffer.wrap(input, offset, n - offset).order(ByteOrder.LITTLE_ENDIAN);
                int length = header.getInt();
                int type = header.getShort() & 0xFFFF;
                int code = header.getShort() & 0xFFFF;
                int responseTid = header.getInt();

                if (length < 12 || offset + length > n) break;

                if (responseTid == tid && type == CONTAINER_DATA && code == OC_GET_DEVICE_PROP_VALUE) {
                    int payloadLength = length - 12;
                    value = new byte[payloadLength];
                    System.arraycopy(input, offset + 12, value, 0, payloadLength);
                } else if (responseTid == tid && type == CONTAINER_RESPONSE) {
                    if (code != RC_OK) throw ptpException("Read camera property", code);
                    if (value == null) throw new Exception("Camera property returned no data");
                    return value;
                }

                offset += length;
            }
        }

        throw new Exception(String.format("Property 0x%04X timed out", propertyCode));
    }


    private DevicePropertyDescriptor getDevicePropertyDescriptor(int propertyCode) throws Exception {
        ensureConnected();
        waitUntilReady(10000);

        byte[] data = dataOperation(
                OC_GET_DEVICE_PROP_DESC,
                new int[]{propertyCode},
                10000);

        if (data.length < 8) {
            throw new Exception(String.format(
                    Locale.US, "Property 0x%04X returned an invalid descriptor", propertyCode));
        }

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int returnedCode = b.getShort() & 0xFFFF;
        int dataType = b.getShort() & 0xFFFF;
        boolean writable = (b.get() & 0xFF) != 0;

        if (returnedCode != propertyCode) {
            throw new Exception("Camera returned the wrong property descriptor");
        }

        long factoryDefault = readPtpInteger(b, dataType);
        long currentValue = readPtpInteger(b, dataType);
        if (!b.hasRemaining()) {
            return new DevicePropertyDescriptor(
                    dataType, writable, factoryDefault, currentValue,
                    0, Long.MIN_VALUE, Long.MIN_VALUE, 0, new long[0]);
        }

        int formFlag = b.get() & 0xFF;
        long min = Long.MIN_VALUE;
        long max = Long.MIN_VALUE;
        long step = 0;
        long[] values = new long[0];

        if (formFlag == 1) {
            min = readPtpInteger(b, dataType);
            max = readPtpInteger(b, dataType);
            step = readPtpInteger(b, dataType);
        } else if (formFlag == 2) {
            if (b.remaining() < 2) throw new Exception("Invalid property enumeration");
            int count = b.getShort() & 0xFFFF;
            values = new long[count];
            for (int i = 0; i < count; i++) {
                values[i] = readPtpInteger(b, dataType);
            }
        }

        return new DevicePropertyDescriptor(
                dataType, writable, factoryDefault, currentValue,
                formFlag, min, max, step, values);
    }

    private Integer chooseTwoSecondExposureDelayValue(DevicePropertyDescriptor desc) {
        if (!desc.writable) return null;

        String model = getDeviceName().toUpperCase(Locale.US);

        // The D750 generation has Nikon SDK documentation using the reverse
        // 0=3 s, 1=2 s, 2=1 s, 3=Off encoding. Keep that known exception
        // separate from the more common Nikon PTP encoding.
        if (model.contains("D750") && desc.supports(1)) {
            return 1;
        }

        // Most Nikon DSLR PTP descriptors that expose selectable delay lengths
        // include raw value 2 for the 2-second choice. We only use it when the
        // camera itself advertises that exact value.
        if (desc.supports(2)) {
            return 2;
        }

        // On/off-only bodies do not expose a selectable 2-second value over PTP.
        return null;
    }

    private long readPtpInteger(ByteBuffer b, int dataType) throws Exception {
        switch (dataType) {
            case 0x0001:
                requireRemaining(b, 1);
                return b.get();
            case 0x0002:
                requireRemaining(b, 1);
                return b.get() & 0xFFL;
            case 0x0003:
                requireRemaining(b, 2);
                return b.getShort();
            case 0x0004:
                requireRemaining(b, 2);
                return b.getShort() & 0xFFFFL;
            case 0x0005:
                requireRemaining(b, 4);
                return b.getInt();
            case 0x0006:
                requireRemaining(b, 4);
                return b.getInt() & 0xFFFFFFFFL;
            default:
                throw new Exception(String.format(
                        Locale.US, "Unsupported PTP property datatype 0x%04X", dataType));
        }
    }

    private byte[] encodePtpInteger(int dataType, long value) throws Exception {
        ByteBuffer b;
        switch (dataType) {
            case 0x0001:
            case 0x0002:
                return new byte[]{(byte)(value & 0xFF)};
            case 0x0003:
            case 0x0004:
                b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
                b.putShort((short)(value & 0xFFFF));
                return b.array();
            case 0x0005:
            case 0x0006:
                b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt((int)(value & 0xFFFFFFFFL));
                return b.array();
            default:
                throw new Exception(String.format(
                        Locale.US, "Unsupported PTP property datatype 0x%04X", dataType));
        }
    }

    private void requireRemaining(ByteBuffer b, int bytes) throws Exception {
        if (b.remaining() < bytes) throw new Exception("Truncated PTP property descriptor");
    }

    private void setDevicePropertyValue(int propertyCode, int dataType, long value) throws Exception {
        ensureConnected();
        waitUntilReady(10000);

        int tid = transactionId++;
        sendCommand(
                OC_SET_DEVICE_PROP_VALUE,
                tid,
                new int[]{propertyCode},
                5000);

        byte[] payload = encodePtpInteger(dataType, value);
        int length = 12 + payload.length;
        ByteBuffer data = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(length);
        data.putShort((short) CONTAINER_DATA);
        data.putShort((short) OC_SET_DEVICE_PROP_VALUE);
        data.putInt(tid);
        data.put(payload);

        byte[] out = data.array();
        int written = connection.bulkTransfer(bulkOut, out, out.length, 5000);
        if (written != out.length) throw new Exception("USB property write failed");

        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            UsbContainer response = readContainer(
                    Math.max(1, deadline - System.currentTimeMillis()));
            if (response.transactionId != tid || response.type != CONTAINER_RESPONSE) continue;
            if (response.code != RC_OK) {
                throw ptpException("Set exposure delay", response.code);
            }
            waitUntilReady(10000);
            return;
        }

        throw new Exception("Set exposure delay timed out");
    }

    private byte[] dataOperation(int operationCode, int[] params, int timeoutMs) throws Exception {
        int tid = transactionId++;
        sendCommand(operationCode, tid, params, timeoutMs);

        byte[] payload = null;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            UsbContainer container = readContainer(Math.max(1, deadline - System.currentTimeMillis()));
            if (container.transactionId != tid) continue;

            if (container.type == CONTAINER_DATA && container.code == operationCode) {
                payload = container.payload;
            } else if (container.type == CONTAINER_RESPONSE) {
                if (container.code != RC_OK) {
                    throw ptpException("PTP data operation", container.code);
                }
                if (payload == null) return new byte[0];
                return payload;
            }
        }

        throw new Exception(String.format("PTP 0x%04X timed out", operationCode));
    }

    private UsbContainer readContainer(long timeoutMs) throws Exception {
        byte[] first = new byte[16384];
        int timeout = (int)Math.min(Integer.MAX_VALUE, Math.max(1, timeoutMs));

        int n;
        do {
            n = connection.bulkTransfer(bulkIn, first, first.length, Math.min(3000, timeout));
            if (n < 0) throw new Exception("USB read failed");
        } while (n < 12);

        ByteBuffer header = ByteBuffer.wrap(first, 0, n).order(ByteOrder.LITTLE_ENDIAN);
        int length = header.getInt();
        int type = header.getShort() & 0xFFFF;
        int code = header.getShort() & 0xFFFF;
        int tid = header.getInt();

        if (length < 12 || length > 32 * 1024 * 1024) {
            throw new Exception("Invalid PTP container length");
        }

        byte[] container = new byte[length];
        int copied = Math.min(n, length);
        System.arraycopy(first, 0, container, 0, copied);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (copied < length && System.currentTimeMillis() < deadline) {
            int want = Math.min(16384, length - copied);
            byte[] chunk = new byte[want];
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int got = connection.bulkTransfer(bulkIn, chunk, want, Math.min(3000, remaining));
            if (got <= 0) continue;
            System.arraycopy(chunk, 0, container, copied, got);
            copied += got;
        }

        if (copied < length) throw new Exception("PTP data transfer timed out");

        return new UsbContainer(
                type,
                code,
                tid,
                length > 12 ? Arrays.copyOfRange(container, 12, length) : new byte[0]);
    }

    private void waitUntilReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Response last = null;

        while (System.currentTimeMillis() < deadline) {
            last = transact(OC_NIKON_DEVICE_READY, new int[]{}, 5000);

            if (last.code == RC_OK) return;

            if (!isBusy(last.code)) {
                throw ptpException("Camera readiness check", last.code);
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted while waiting for camera");
            }
        }

        if (last != null) throw ptpException("Camera stayed busy too long", last.code);
        throw new Exception("Camera readiness check timed out");
    }

    private boolean isBusy(int code) {
        return code == RC_DEVICE_BUSY
                || code == RC_NIKON_BULB_RELEASE_BUSY
                || code == RC_NIKON_SILENT_RELEASE_BUSY;
    }

    private Response transact(int operationCode, int[] params, int timeoutMs) throws Exception {
        int tid = transactionId++;
        sendCommand(operationCode, tid, params, timeoutMs);

        byte[] input = new byte[512];
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int remaining = (int)Math.max(1, deadline - System.currentTimeMillis());
            int n = connection.bulkTransfer(bulkIn, input, input.length, Math.min(3000, remaining));
            if (n < 12) continue;

            int offset = 0;
            while (offset + 12 <= n) {
                ByteBuffer response = ByteBuffer.wrap(input, offset, n - offset).order(ByteOrder.LITTLE_ENDIAN);
                int length = response.getInt();
                int type = response.getShort() & 0xFFFF;
                int code = response.getShort() & 0xFFFF;
                int responseTid = response.getInt();

                if (length < 12 || offset + length > n) break;

                if (type == CONTAINER_RESPONSE && responseTid == tid) {
                    return new Response(code);
                }
                offset += length;
            }
        }

        throw new Exception(String.format("PTP 0x%04X timed out", operationCode));
    }

    private void sendCommand(int operationCode, int tid, int[] params, int timeoutMs) throws Exception {
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
    }

    private void ensureConnected() throws Exception {
        if (!isConnected()) throw new Exception("Camera not connected");
    }

    private Exception ptpException(String operation, int code) {
        String hint = "";
        if (code == RC_DEVICE_BUSY) hint = " — camera busy";
        else if (code == RC_NIKON_BULB_RELEASE_BUSY) hint = " — Nikon Bulb release busy";
        else if (code == RC_NIKON_SILENT_RELEASE_BUSY) hint = " — Nikon silent release busy";
        else if (code == 0x2005) hint = " — operation not supported";
        else if (code == 0x200A) hint = " — invalid parameter";
        return new Exception(String.format("%s: PTP 0x%04X%s", operation, code, hint));
    }


    public static final class ExposureDelayInfo {
        public final boolean supported;
        public final boolean writable;
        public final long currentValue;
        public final Integer twoSecondRawValue;
        public final String message;

        ExposureDelayInfo(
                boolean supported,
                boolean writable,
                long currentValue,
                Integer twoSecondRawValue,
                String message) {
            this.supported = supported;
            this.writable = writable;
            this.currentValue = currentValue;
            this.twoSecondRawValue = twoSecondRawValue;
            this.message = message;
        }

        static ExposureDelayInfo unsupported(String message) {
            return new ExposureDelayInfo(false, false, -1, null, message);
        }

        public boolean canAutoConfigureTwoSeconds() {
            return supported && writable && twoSecondRawValue != null;
        }
    }

    public static final class ExposureDelaySession {
        public final boolean configured;
        public final boolean changed;
        public final long previousValue;
        public final int dataType;
        public final String message;

        ExposureDelaySession(
                boolean configured,
                boolean changed,
                long previousValue,
                int dataType,
                String message) {
            this.configured = configured;
            this.changed = changed;
            this.previousValue = previousValue;
            this.dataType = dataType;
            this.message = message;
        }

        static ExposureDelaySession notConfigured(String message) {
            return new ExposureDelaySession(false, false, -1, 0, message);
        }
    }

    private static final class DevicePropertyDescriptor {
        final int dataType;
        final boolean writable;
        final long factoryDefault;
        final long currentValue;
        final int formFlag;
        final long min;
        final long max;
        final long step;
        final long[] values;

        DevicePropertyDescriptor(
                int dataType,
                boolean writable,
                long factoryDefault,
                long currentValue,
                int formFlag,
                long min,
                long max,
                long step,
                long[] values) {
            this.dataType = dataType;
            this.writable = writable;
            this.factoryDefault = factoryDefault;
            this.currentValue = currentValue;
            this.formFlag = formFlag;
            this.min = min;
            this.max = max;
            this.step = step;
            this.values = values;
        }

        boolean supports(long value) {
            if (formFlag == 1) {
                if (min == Long.MIN_VALUE || max == Long.MIN_VALUE || step <= 0) return false;
                return value >= min && value <= max && ((value - min) % step == 0);
            }
            if (formFlag == 2) {
                for (long candidate : values) {
                    if (candidate == value) return true;
                }
                return false;
            }
            return currentValue == value || factoryDefault == value;
        }
    }

    public static final class CameraSetup {
        public final boolean manualMode;
        public final boolean bulb;
        public final boolean manualFocus;
        public final int batteryLevel;

        CameraSetup(boolean manualMode, boolean bulb, boolean manualFocus, int batteryLevel) {
            this.manualMode = manualMode;
            this.bulb = bulb;
            this.manualFocus = manualFocus;
            this.batteryLevel = batteryLevel;
        }
    }

    private static final class UsbContainer {
        final int type;
        final int code;
        final int transactionId;
        final byte[] payload;

        UsbContainer(int type, int code, int transactionId, byte[] payload) {
            this.type = type;
            this.code = code;
            this.transactionId = transactionId;
            this.payload = payload;
        }
    }

    private static final class Response {
        final int code;
        Response(int code) { this.code = code; }
    }
}
