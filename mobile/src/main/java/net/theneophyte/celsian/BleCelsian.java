package net.theneophyte.celsian;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Class that handles the BLE connection to the Celsian device
 *
 * Created by Matt Sutter on 9/5/2016.
 */
public class BleCelsian extends BluetoothGattCallback{

    // UUIDs for Celsian BLE service and associated characteristics.
    public static final UUID CELSIAN_SERVICE_UUID       = UUID.fromString("2D040001-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_SERVICE_UUID_MASK  = UUID.fromString("FFFF0000-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    public static final UUID CELSIAN_MPL_TEMP_CHAR_UUID = UUID.fromString("2D040002-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_SHT_TEMP_CHAR_UUID = UUID.fromString("2D040003-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_RH_CHAR_UUID       = UUID.fromString("2D040004-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_PRES_CHAR_UUID     = UUID.fromString("2D040005-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_UVA_CHAR_UUID      = UUID.fromString("2D040006-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_UVB_CHAR_UUID      = UUID.fromString("2D040007-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_UVD_CHAR_UUID      = UUID.fromString("2D040008-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_UVCOMP1_CHAR_UUID  = UUID.fromString("2D040009-779E-4EFA-9E1B-351935C016DC");
    public static final UUID CELSIAN_UVCOMP2_CHAR_UUID  = UUID.fromString("2D04000A-779E-4EFA-9E1B-351935C016DC");

    // BLE scanning timeout
    private static final int SCAN_PERIOD = 10000;

    private final Context mContext;
    private final BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mGatt;
    private String mCelsianBleAddr = null;
    private final BluetoothGattCallback mGattCallback = this;
    private ScanCallback mLeScanCallback = new CelsianScanCallback();
    private BluetoothGattCharacteristic
            mplTempChar = null,
            shtTempChar = null,
            rhChar      = null,
            presChar    = null,
            uvaChar     = null,
            uvbChar     = null,
            uvdChar     = null,
            uvcomp1Char = null,
            uvcomp2Char = null;

    // Queues for characteristic read (synchronous)
    private Queue<BluetoothGattCharacteristic> readQueue;

    private final List<ScanFilter> mLeScanFilterList;
    private final ScanSettings mUartLeScanSettings;
    private final Handler mHandler;
    private final WeakReference<BleCallback> mCallback;
    private boolean mScanning = false, mConnected = false, mConnecting = false, mReadPending = false;

    /**
     * Callbacks for the UI
     */
    public interface BleCallback {

        /**
         * Called when there is a successful connection to Celsian
         */
        void onConnected();

        /**
         * Called when the connection to Celsian has failed
         */
        void onConnectFailed();

        /**
         * Called when Celsian has been disconnected
         */
        void onDisconnected();

        /**
         * Called when the connection to Celsian times out
         */
        void onConnectionTimeout();

        /**
         * Called when the MPL temperature is updated
         * @param value - new temperature value (in degrees C)
         */
        void onMplTempChange(final double value);

        /**
         * Called when the SHT temperature is updated
         * @param value - new temperature value (in degrees C)
         */
        void onShtTempChange(final double value);

        /**
         * Called when the relative humidity is updated
         * @param value - new RH value (percentage)
         */
        void onRhChange(final double value);

        /**
         * Called when the air pressure is updated
         * @param value - new pressure value (in pascals)
         */
        void onPresChange(final double value);

        /**
         * Called when the UVA irradiance is updated
         * @param value - new UVA value (unsigned 16-bit integer, no units)
         */
        void onUvaChange(final int value);

        /**
         * Called when the UVB irradiance is updated
         * @param value - new UVB value (unsigned 16-bit integer, no units)
         */
        void onUvbChange(final int value);

        /**
         * Called when the UV dummy irradiance is updated
         * @param value - new UVD value (unsigned 16-bit integer, no units)
         */
        void onUvdChange(final int value);

        /**
         * Called when the UVcomp1 irradiance is updated
         * @param value - new UVcomp1 value (unsigned 16-bit integer, no units)
         */
        void onUvcomp1Change(final int value);

        /**
         * Called when the UVcomp2 irradiance is updated
         * @param value - new UVcomp2 value (unsigned 16-bit integer, no units)
         */
        void onUvcomp2Change(final int value);
    }

    /**
     * Constructor for BleCelsian.
     * @param context Application context
     * @param handler UI thread handler
     * @param callback UI callback
     */
    public BleCelsian(Context context, Handler handler, BleCallback callback){
        mContext = context;
        mHandler = handler;
        mCallback = new WeakReference<BleCallback>(callback);
        mBluetoothAdapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        mUartLeScanSettings = (new ScanSettings.Builder()).setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(0).build();
        mLeScanFilterList = new ArrayList<ScanFilter>();
        mLeScanFilterList.add((new ScanFilter.Builder()).setServiceUuid(
                new ParcelUuid(CELSIAN_SERVICE_UUID),
                new ParcelUuid(CELSIAN_SERVICE_UUID_MASK)
        ).build());

        readQueue = new ConcurrentLinkedQueue<BluetoothGattCharacteristic>();
    }

    /**
     * Runnable to stop the BLE scan
     */
    private final Runnable stopLeScanRunner = new Runnable() {
        @Override
        public void run() {
            Log.d("Celsian", "Stoping scan...");
            mBluetoothAdapter.getBluetoothLeScanner().stopScan(mLeScanCallback);

            mScanning = false;

            if (!mConnecting && !mConnected) {
                notifyOnConnectionTimeout();
            }
        }
    };

    /**
     * Runnable to start a new BLE scan
     */
    public void startScan(){
        if (!mScanning) {
            Log.d("Celsian", "Starting scan...");
            mHandler.postDelayed(stopLeScanRunner, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.getBluetoothLeScanner().startScan(mLeScanFilterList, mUartLeScanSettings, mLeScanCallback);
        }
    }

    /**
     * Stops the BLE scan
     */
    public void stopScan(){
        if (mScanning) {
            mHandler.removeCallbacks(stopLeScanRunner);

            stopLeScanRunner.run();
        }
    }

    /**
     * Attempts to connect to Celsian
     */
    public void connect(){
        if (mConnected){
            Log.d("Celsian", "Already connected...");
            notifyOnConnected();
            return;
        }

        if (mGatt != null && mGatt.connect()){
            Log.d("Celsian", "Attempting to reconnect to GATT Server...");
            notifyOnConnected();
            return;
        }

        if (mCelsianBleAddr != null && mBluetoothAdapter != null) {
            Log.d("Celsian", "Attempting to reconnect directly to the device...");
            mBluetoothAdapter.getRemoteDevice(mCelsianBleAddr).connectGatt(mContext, false, mGattCallback);
        } else {
            startScan();
        }
    }

    /**
     * Disconnects from Celsian
     */
    public void disconnect(){
        stopScan();

        if (mGatt == null) {
            return;
        }

        Log.d("Celsian", "Disconnecting...");

        mGatt.disconnect();
        close();

        mGatt = null;

        notifyOnDisconnected();
    }

    /**
     * Closes the GATT connection to Celsian
     */
    public void close(){
        if (mGatt == null) {
            return;
        }

        mGatt.close();

        clearChars();

        mConnected = false;
    }

    /**
     * BleCallback for the BLE scanner
     */
    private class CelsianScanCallback extends ScanCallback{

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            final ScanRecord record = result.getScanRecord();
            if (record == null){
                return;
            }

            List<ParcelUuid> uuids = record.getServiceUuids();

            for (ParcelUuid uuid : uuids){
                if (uuid.getUuid().equals(CELSIAN_SERVICE_UUID)) {
                    if (!mConnecting) {
                        mConnecting = true;
                        stopScan();
                        result.getDevice().connectGatt(mContext, false, mGattCallback);
                        return;
                    }
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            notifyOnConnectFailed();
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState){
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Connected to device, start discovering services.
                if (!gatt.discoverServices()) {
                    // Error starting service discovery.
                    notifyOnConnectFailed();
                }
            }
            else {
                // Error connecting to device.
                notifyOnConnectFailed();
            }
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            // Disconnected, notify callbacks of disconnection.
            clearChars();

            readQueue.clear();

            mReadPending = false;
            mConnected = false;

            notifyOnDisconnected();
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){
        super.onCharacteristicRead(gatt, characteristic, status);

        if (status == BluetoothGatt.GATT_SUCCESS){
            updateCharValue(characteristic);

            final BluetoothGattCharacteristic nextRead = readQueue.poll();
            if (nextRead != null){
                mGatt.readCharacteristic(nextRead);
            } else {
                mReadPending = false;
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){
        super.onCharacteristicWrite(gatt, characteristic, status);

        if (status == BluetoothGatt.GATT_SUCCESS){
            updateCharValue(characteristic);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status){
        mGatt = gatt;

        if (gatt == null){
            mConnecting = false;
            mConnected = false;

            notifyOnConnectFailed();
            return;
        }

        mplTempChar = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_MPL_TEMP_CHAR_UUID);
        shtTempChar = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_SHT_TEMP_CHAR_UUID);
        rhChar      = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_RH_CHAR_UUID);
        presChar    = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_PRES_CHAR_UUID);
        uvaChar     = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_UVA_CHAR_UUID);
        uvbChar     = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_UVB_CHAR_UUID);
        uvdChar     = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_UVD_CHAR_UUID);
        uvcomp1Char = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_UVCOMP1_CHAR_UUID);
        uvcomp2Char = gatt.getService(CELSIAN_SERVICE_UUID).getCharacteristic(CELSIAN_UVCOMP2_CHAR_UUID);

        if (       (mplTempChar != null)
                && (shtTempChar != null)
                && (rhChar      != null)
                && (presChar    != null)
                && (uvaChar     != null)
                && (uvbChar     != null)
                && (uvdChar     != null)
                && (uvcomp1Char != null)
                && (uvcomp2Char != null)  ){


            mCelsianBleAddr = mGatt.getDevice().getAddress();

            mConnecting = false;
            mConnected = true;

            notifyOnConnected();
        } else {
            mConnecting = false;
            mConnected = false;

            notifyOnConnectFailed();
        }
    }

    /**
     * Clears the Characteristic values
     */
    private void clearChars(){
        mplTempChar = null;
        shtTempChar = null;
        rhChar      = null;
        presChar    = null;
        uvaChar     = null;
        uvbChar     = null;
        uvdChar     = null;
        uvcomp1Char = null;
        uvcomp2Char = null;
    }

    /**
     * Updates a Characteristic
     * @param characteristic
     */
    private void updateCharValue(BluetoothGattCharacteristic characteristic){

        final UUID char_uuid = characteristic.getUuid();

        if (mCallback.get() == null){
            return;
        }

        // Notify the UI of the new value
        if (char_uuid.equals(CELSIAN_MPL_TEMP_CHAR_UUID)){
            mplTempChar = characteristic;
            mCallback.get().onMplTempChange(getDoubleValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_SHT_TEMP_CHAR_UUID)){
            shtTempChar = characteristic;
            mCallback.get().onShtTempChange(getDoubleValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_RH_CHAR_UUID)){
            rhChar = characteristic;
            mCallback.get().onRhChange(getDoubleValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_PRES_CHAR_UUID)){
            presChar = characteristic;
            mCallback.get().onPresChange(getDoubleValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_UVA_CHAR_UUID)){
            uvaChar = characteristic;
            mCallback.get().onUvaChange(getIntValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_UVB_CHAR_UUID)){
            uvbChar = characteristic;
            mCallback.get().onUvbChange(getIntValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_UVD_CHAR_UUID)){
            uvdChar = characteristic;
            mCallback.get().onUvdChange(getIntValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_UVCOMP1_CHAR_UUID)){
            uvdChar = characteristic;
            mCallback.get().onUvcomp1Change(getIntValue(characteristic));
        } else if (char_uuid.equals(CELSIAN_UVCOMP2_CHAR_UUID)){
            uvdChar = characteristic;
            mCallback.get().onUvcomp2Change(getIntValue(characteristic));
        }
    }

    /**
     * Request to read a Characteristic over BLE. If there are other
     * read requests pending the read will be added to the read queue.
     * @param characteristic BLE Characteristic to be read
     * @return True if the request was accepted, false otherwise
     */
    private boolean requestRead(BluetoothGattCharacteristic characteristic){
        if (mConnected){
            if (!mReadPending) {
                mReadPending = mGatt.readCharacteristic(characteristic);
            } else {
                readQueue.add(characteristic);
            }
            return mReadPending;
        } else {
            return false;
        }
    }

    /**
     *
     */
    public void readMplTemp(){
        requestRead(mplTempChar);
    }

    /**
     *
     */
    public void readShtTemp(){
        requestRead(shtTempChar);
    }

    /**
     *
     */
    public void readRh(){
        requestRead(rhChar);
    }

    /**
     *
     */
    public void readPres(){
        requestRead(presChar);
    }

    /**
     *
     */
    public void readUvaValue(){
        requestRead(uvaChar);
    }

    /**
     *
     */
    public void readUvbValue(){
        requestRead(uvbChar);
    }

    /**
     *
     */
    public void readUvdValue(){
        requestRead(uvdChar);
    }
    public void readUvcomp1Value(){
        requestRead(uvcomp1Char);
    }
    public void readUvcomp2Value(){
        requestRead(uvcomp2Char);
    }


    private double getDoubleValue(BluetoothGattCharacteristic charact){
        byte[] value = charact.getValue();

        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    private int getIntValue(BluetoothGattCharacteristic charact){
        return charact.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0).byteValue();
    }

    /**
     * Notify the UI that the connection to Celsian has failed
     */
    private void notifyOnConnectFailed(){
        clearChars();

        if (mCallback != null) {
            mCallback.get().onConnectFailed();
        }
    }

    /**
     * Notify the UI that Celsian is connected
     */
    private void notifyOnConnected(){
        stopScan();

        if (mCallback != null) {
            mCallback.get().onConnected();
        }
    }

    /**
     * Notify the UI that Celsian has been disconnected
     */
    private void notifyOnDisconnected(){
        if (mCallback != null) {
            mCallback.get().onDisconnected();
        }
    }

    /**
     * Notify the UI that the Celsian connection has timed out
     */
    private void notifyOnConnectionTimeout(){
        if (mCallback != null){
            mCallback.get().onConnectionTimeout();
        }
    }

}
