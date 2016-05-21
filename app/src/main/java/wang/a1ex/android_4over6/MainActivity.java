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
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Text;

import java.lang.reflect.Method;

public class MainActivity extends AppCompatActivity {

    String mServerAddress = "2402:f000:1:4417::900";
    String mServerPort = "5678";
    final static int REQUEST_EXTERNAL_STORAGE = 123;

    TextView packetsSent;
    TextView bytesSent;
    TextView packetsReceived;
    TextView bytesReceived;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        packetsReceived = (TextView) findViewById(R.id.textPacketsReceived);
        packetsSent = (TextView) findViewById(R.id.textPacketsSent);
        bytesSent = (TextView) findViewById(R.id.textBytesSent);
        bytesReceived = (TextView) findViewById(R.id.textBytesReceived);

        IntentFilter counterActionFilter = new IntentFilter(BROADCAST_NAME);
        registerReceiver(counterActionReceiver, counterActionFilter);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        assert fab != null;
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
                //Toast.makeText(MainActivity.this, new VpnDevices().getString(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static final String BROADCAST_NAME = "wang.a1ex.android_4over6.StatisticsBroadcast";
    public static final String BROADCAST_INTENT_BYTES_SENT = "1";
    public static final String BROADCAST_INTENT_PACKETS_SEND = "2";
    public static final String BROADCAST_INTENT_BYTES_RECEIVED = "3";
    public static final String BROADCAST_INTENT_PACKETS_RECEIVED = "4";
    public BroadcastReceiver counterActionReceiver = new BroadcastReceiver(){
        public void onReceive(Context context, Intent intent) {
            packetsReceived.setText("packets received: " +
                    String.valueOf(intent.getIntExtra(BROADCAST_INTENT_PACKETS_RECEIVED, 0)));
            bytesReceived.setText("bytes received: " +
                    String.valueOf(intent.getIntExtra(BROADCAST_INTENT_BYTES_RECEIVED, 0)));
            packetsSent.setText("packets sent: " +
                    String.valueOf(intent.getIntExtra(BROADCAST_INTENT_PACKETS_SEND, 0)));
            bytesSent.setText("bytes sent: " +
                    String.valueOf(intent.getIntExtra(BROADCAST_INTENT_BYTES_SENT, 0)));
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
            String prefix = getPackageName();
            Intent intent = new Intent(this, IVIVpnService.class)
                    .putExtra(prefix + ".ADDRESS", mServerAddress)
                    .putExtra(prefix + ".PORT", mServerPort);
            startService(intent);
        }
    }
}
