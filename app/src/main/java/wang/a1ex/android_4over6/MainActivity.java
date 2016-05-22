package wang.a1ex.android_4over6;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    final static int REQUEST_EXTERNAL_STORAGE = 123;

    TextView vpnStatistics;
    FloatingActionButton fab;
    boolean isConnected = false;

    void updateConnectedStatus(boolean v) {
        isConnected = v;
        if (isConnected) {
            fab.setImageResource(android.R.drawable.ic_delete);
        }
        else {
            fab.setImageResource(android.R.drawable.ic_menu_upload);
            vpnStatistics.setText("Unconnected");
        }
    }

    void stopVpn() {
        Intent intent = new Intent(MainActivity.this, IVIVpnService.class);
        intent.putExtra(IVIVpnService.VPN_SERVICE_INTENT_KEY, IVIVpnService.VPN_SERVICE_DISCONNECT);
        startService(intent);
        updateConnectedStatus(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        vpnStatistics = (TextView) findViewById(R.id.textVpnStatistics);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        assert fab != null;

        fab.setImageResource(android.R.drawable.ic_menu_upload);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isConnected) {
                    stopVpn();
                    updateConnectedStatus(true);
                }
                else {
                    int permission = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

                    if (permission != PackageManager.PERMISSION_GRANTED) {
                        // We don't have permission so prompt the user
                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                                REQUEST_EXTERNAL_STORAGE
                        );
                    }
                    Intent intent = VpnService.prepare(MainActivity.this);
                    if (intent != null) {
                        startActivityForResult(intent, 0);
                    } else {
                        onActivityResult(0, RESULT_OK, null);
                    }
                    updateConnectedStatus(false);
                }
            }
        });

        Intent intent = new Intent(MainActivity.this, IVIVpnService.class);
        intent.putExtra(IVIVpnService.VPN_SERVICE_INTENT_KEY, IVIVpnService.VPN_SERVICE_GET_STATUS);
        startService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter counterActionFilter = new IntentFilter(BROADCAST_NAME);
        registerReceiver(counterActionReceiver, counterActionFilter);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (counterActionReceiver != null)
            unregisterReceiver(counterActionReceiver);
    }

    public static final String BROADCAST_NAME = "wang.a1ex.android_4over6.StatisticsBroadcast";
    public static final String BROADCAST_INTENT_STATISTICS = "2";
    public static final String BROADCAST_INTENT_STATUS = "3";
    public BroadcastReceiver counterActionReceiver = new BroadcastReceiver(){
        public void onReceive(Context context, Intent intent) {
            vpnStatistics.setText(intent.getStringExtra(BROADCAST_INTENT_STATISTICS));
            updateConnectedStatus(intent.getBooleanExtra(BROADCAST_INTENT_STATUS, false));
        }
    };

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
    protected void onActivityResult(int request, int result, Intent data) {
        if (result == RESULT_OK) {
            Intent intent = new Intent(MainActivity.this, IVIVpnService.class);
            intent.putExtra(IVIVpnService.VPN_SERVICE_INTENT_KEY, IVIVpnService.VPN_SERVICE_CONNECT);
            startService(intent);
        }
    }
}
