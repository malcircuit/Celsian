package net.theneophyte.celsian;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Formatter;

/**
 * Main Activity class for the Celsian app
 *
 * Created by Matt Sutter on 9/5/2016.
 */
public class MainActivity extends AppCompatActivity implements BleCelsian.BleCallback {
    private static final double VEML_A_COEF   =	3.33; 	// The default value for the UVA VIS coefficient
    private static final double VEML_B_COEF   =	2.5;	// The default value for the UVA IR coefficient
    private static final double VEML_C_COEF   =	3.66;	// The default value for the UVB VIS coefficient
    private static final double VEML_D_COEF   = 2.75;	// The default value for the UVB IR coefficient
    private static final double VEML_UVA_RESP = 0.0011;
    private static final double VEML_UVB_RESP = 0.00125;

    private static final int PERMISSIONS_ACCESS_COARSE_LOCATION = 1;

    private Handler mHandler;

    private BleCelsian mCelsian;

    private double  mplTemp  = Double.NaN,
                    shtTemp  = Double.NaN,
                    rh       = Double.NaN,
                    pressure = Double.NaN;

    private int     uva = Integer.MAX_VALUE,
                    uvb = Integer.MAX_VALUE,
                    uvd = Integer.MAX_VALUE,
                    uvcomp1 = Integer.MAX_VALUE,
                    uvcomp2 = Integer.MAX_VALUE;

    private TextView tempText, rhText, presText, uviText;

    private AsyncTask<Void, Void, Void> updateTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Make sure BLE is supported
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        tempText = (TextView)findViewById(R.id.temp_value);
        rhText   = (TextView)findViewById(R.id.rh_value);
        presText = (TextView)findViewById(R.id.pres_value);
        uviText  = (TextView)findViewById(R.id.uvi_value);
    }

    private void checkPermissionsAndConnect()
    {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);

        if (permissionCheck == PackageManager.PERMISSION_DENIED){
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSIONS_ACCESS_COARSE_LOCATION);
        }
        else
        {
            mCelsian.connect();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_ACCESS_COARSE_LOCATION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mCelsian.connect();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onStart() {
        super.onStart();

        mHandler = new Handler(Looper.getMainLooper());
        mCelsian = new BleCelsian(getApplicationContext(), mHandler, this);

        mplTemp  = Double.NaN;
        shtTemp  = Double.NaN;
        rh       = Double.NaN;
        pressure = Double.NaN;
        uva = Integer.MAX_VALUE;
        uvb = Integer.MAX_VALUE;
        uvd = Integer.MAX_VALUE;
        uvcomp1 = Integer.MAX_VALUE;
        uvcomp2 = Integer.MAX_VALUE;

        setTempText(Double.NaN);
        setRhText(Double.NaN);
        setPresText(Double.NaN);
        setUviText(Double.NaN);

        checkPermissionsAndConnect();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (updateTask != null && !updateTask.isCancelled()){
            updateTask.cancel(true);
        }

        mCelsian.disconnect();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
    }

    @Override
    public void onConnected() {
        Log.i("Celsian", "Device connected.");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Celsian connected!", Toast.LENGTH_SHORT).show();
                updateValues();
                if (updateTask == null || updateTask.isCancelled()){
                    updateTask = new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... params) {
                            while(!this.isCancelled()) {
                                long start = System.currentTimeMillis();
                                while (System.currentTimeMillis() <= start + 500);

                                updateValues();
                            }

                            return null;
                        }
                    };
                }

                updateTask.execute();
            }
        });
    }

    @Override
    public void onConnectFailed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Celsian connection failed!", Toast.LENGTH_SHORT).show();

                enableView(tempText, false);
                enableView(rhText, false);
                enableView(presText, false);
                enableView(uviText, false);

                if (updateTask != null && !updateTask.isCancelled()){
                    updateTask.cancel(true);
                }
            }
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Celsian disconnected", Toast.LENGTH_SHORT).show();

                enableView(tempText, false);
                enableView(rhText,   false);
                enableView(presText, false);
                enableView(uviText,  false);

                if (updateTask != null && !updateTask.isCancelled()){
                    updateTask.cancel(true);
                }
            }
        });
    }

    @Override
    public void onConnectionTimeout() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "No Celsian found", Toast.LENGTH_SHORT).show();

                enableView(tempText, false);
                enableView(rhText,   false);
                enableView(presText, false);
                enableView(uviText,  false);

                if (updateTask != null && !updateTask.isCancelled()){
                    updateTask.cancel(true);
                }
            }
        });
    }

    @Override
    public void onMplTempChange(final double value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mplTemp = value;
                setTempText(calculateTemp());
            }
        });
    }

    @Override
    public void onShtTempChange(final double value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                shtTemp = value;
                setTempText(calculateTemp());
            }
        });
    }

    @Override
    public void onRhChange(final double value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rh = value;
                setRhText(rh);
            }
        });
    }

    @Override
    public void onPresChange(final double value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pressure = value;
                setPresText(pressure);
            }
        });
    }

    @Override
    public void onUvaChange(final int value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uva = value;
                setUviText(calculateUvi());
            }
        });
    }

    @Override
    public void onUvbChange(final int value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uvb = value;
                setUviText(calculateUvi());
            }
        });
    }

    @Override
    public void onUvdChange(final int value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uvd = value;
                setUviText(calculateUvi());
            }
        });
    }

    @Override
    public void onUvcomp1Change(final int value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uvcomp1 = value;
                setUviText(calculateUvi());
            }
        });
    }

    @Override
    public void onUvcomp2Change(final int value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                uvcomp2 = value;
                setUviText(calculateUvi());
            }
        });
    }

    /**
     * Initiates an update to the displayed values
     */
    private void updateValues(){
        if (mCelsian != null) {
            mCelsian.readMplTemp();
            mCelsian.readShtTemp();
            mCelsian.readRh();
            mCelsian.readPres();
            mCelsian.readUvaValue();
            mCelsian.readUvbValue();
            mCelsian.readUvdValue();
            mCelsian.readUvcomp1Value();
            mCelsian.readUvcomp2Value();
        }
    }

    /**
     * Enables/Disables a specified View
     * @param view
     * @param enable
     */
    private void enableView(View view, boolean enable){
        if (view != null && (view.isEnabled() != enable))
            view.setEnabled(enable);
    }

    /**
     * Sets the text of the displayed temperature value
     * @param degreesC - new temperature (in degrees Celsius) to display
     */
    private void setTempText(double degreesC){
        String text;
        Formatter fmt = new Formatter();

        if (!((Double)degreesC).isNaN()) {
            text = fmt.format("%3.2f F", degreesC*1.8 + 32).out().toString();
            enableView(tempText, true);
        }
        else {
            text = "-- F";
            enableView(tempText, false);
        }

        tempText.setText(text);
    }

    /**
     * Sets the text of the displayed relative humidity value
     * @param percent - new RH percent to display
     */
    private void setRhText(double percent){
        String text;
        Formatter fmt = new Formatter();

        if (!((Double)percent).isNaN()) {
            text = fmt.format("%2.2f %%", percent).out().toString();
            enableView(rhText, true);
        }
        else {
            text = "-- %";
            enableView(rhText, false);
        }

        rhText.setText(text);
    }

    /**
     * Sets the text of the displayed air pressure value
     * @param pascals - new pressure (in pascals) to display
     */
    private void setPresText(double pascals){
        String text;
        Formatter fmt = new Formatter();

        if (!((Double)pascals).isNaN()) {
            text = fmt.format("%4.2f mb", pascals/100).out().toString();
            enableView(presText, true);
        }
        else {
            text = "-- mb";
            enableView(presText, false);
        }

        presText.setText(text);
    }

    /**
     * Sets the text of the displayed UV Index value
     * @param index - new UVI value
     */
    private void setUviText(double index){
        String text;
        Formatter fmt = new Formatter();

        if (!((Double)index).isNaN()) {
            text = fmt.format("%2.2f", index).out().toString();
            enableView(uviText, true);
        }
        else {
            text = "--";
            enableView(uviText, false);
        }

        uviText.setText(text);
    }

    /**
     * Calculates the UV Index from the UV irradiance values read from Celsian
     * @return - UVI
     */
    private double calculateUvi(){
        if (    uva != Integer.MAX_VALUE
                && uvb != Integer.MAX_VALUE
                && uvd != Integer.MAX_VALUE
                && uvcomp1 != Integer.MAX_VALUE
                && uvcomp2 != Integer.MAX_VALUE
                )
        {
            // These equations came from the "Designing the VEML6075 into an Application" document from Vishay
            double uvacomp = (uva - uvd) - VEML_A_COEF * (uvcomp1 - uvd) - VEML_B_COEF * (uvcomp2 - uvd);
            double uvbcomp = (uvb - uvd) - VEML_C_COEF * (uvcomp1 - uvd) - VEML_D_COEF * (uvcomp2 - uvd);

            return  ( (uvbcomp * VEML_UVB_RESP) + (uvacomp * VEML_UVA_RESP) ) / 2.0;
        }

        return Double.NaN;
    }

    /**
     * Takes the average of the MPL and SHT temperatures
     * @return - Tempurature (in degrees C)
     */
    private double calculateTemp(){
        if (!((Double)mplTemp).isNaN() && !((Double)shtTemp).isNaN()){
            return (mplTemp + shtTemp)/2;
        }

        return Double.NaN;
    }
}
